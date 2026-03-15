# TODO: Video Architecture Refactor + FFmpeg Backend

## Overview

Refactor the video backend architecture to mirror the image backend pattern
(per-backend SPIs with BackendPriority), then add FFmpeg as a cross-platform
video backend.

---

## Part 1: Video Architecture Refactor

### Current architecture (old pattern)

```
VideoFrameReaderSpi (single SPI in video-common)
  -> VideoFrameReader -> VideoFrameExtractor (facade, first-wins discovery)
      -> VideoFrameExtractorProvider (ServiceLoader)
          |-- AppleVideoFrameExtractor
          |-- WindowsVideoFrameExtractor
```

### Target architecture (mirrors image pattern)

```
NativeVideoReaderSpi (base in video-common, mirrors NativeImageReaderSpi)
  |-- AppleVideoReaderSpi (video-apple)     -> NativeVideoReader + AppleVideoFrameExtractor
  |-- WindowsVideoReaderSpi (video-windows) -> NativeVideoReader + WindowsVideoFrameExtractor
  |-- FFmpegVideoReaderSpi (video-ffmpeg)   -> NativeVideoReader + FFmpegVideoFrameExtractor

VideoFrameExtractor (direct API, sorted by BackendPriority)
  -> VideoFrameExtractorProvider (ServiceLoader, now with backendName + priority)
```

### Step 1: Changes to video-common

| File | Action | Description |
|------|--------|-------------|
| NativeVideoReaderSpi.java | NEW | Abstract base: canDecodeInput (PathAwareImageInputStream + VideoFormatDetector + BackendPriority), onRegistration (priority ordering), abstract isAvailable/createReaderInstance, backendName hook |
| NativeVideoReader.java | NEW | Concrete ImageReader: takes VideoFrameExtractorProvider, delegates read() to provider.extractFrame(path, ZERO), delegates getWidth/Height to provider.getInfo(path), caches info, extracts path from PathAwareImageInputStream |
| VideoFormatRegistry.java | NEW | Shared video format list (MP4, MOV, M4V, WebM, MKV, AVI, WMV, 3GP). No supplemental mode -- Java has no built-in video readers |
| VideoFrameExtractorProvider.java | MODIFY | Add `default String backendName() { return "native"; }` |
| VideoFrameExtractor.java | MODIFY | Sort providers by BackendPriority instead of first-wins. Remove OS-specific reflective fallback |
| VideoFrameReaderSpi.java | REMOVE | Replaced by per-backend SPIs |
| VideoFrameReader.java | REMOVE | Replaced by NativeVideoReader |
| META-INF/services/javax.imageio.spi.ImageReaderSpi | REMOVE | No longer registered from video-common |

### Step 2: Changes to video-apple

| File | Action |
|------|--------|
| AppleVideoReaderSpi.java | NEW -- extends NativeVideoReaderSpi, isAvailable=IS_MACOS, creates NativeVideoReader + AppleVideoFrameExtractor |
| AppleVideoFrameExtractor.java | ADD backendName() returning "native" |
| META-INF/services/javax.imageio.spi.ImageReaderSpi | NEW -- registers AppleVideoReaderSpi |

### Step 3: Changes to video-windows

| File | Action |
|------|--------|
| WindowsVideoReaderSpi.java | NEW -- extends NativeVideoReaderSpi |
| WindowsVideoFrameExtractor.java | ADD backendName() returning "native" |
| META-INF/services/javax.imageio.spi.ImageReaderSpi | NEW -- registers WindowsVideoReaderSpi |

### Step 4: Changes to common

| File | Action |
|------|--------|
| BackendPriority.java | Change default ordering to `native,vips,magick,ffmpeg` |

### Step 5: Verify existing 160 tests pass

---

## Part 2: FFmpeg Backend

### New module: imageio-native-video-ffmpeg

Dependencies: imageio-native-video-common, imageio-native-video-common (test-jar), junit

Not included in imageio-native-video aggregator -- opt-in only.

### FFmpegNative.java

Libraries to load: libavutil, libavcodec, libavformat, libswscale

Library discovery:
1. System property `imageio.native.ffmpeg.lib.dir` (directory override)
2. `/opt/local/lib/` (MacPorts)
3. `/usr/local/lib/` (Homebrew)
4. `/opt/homebrew/lib/` (Homebrew Apple Silicon)
5. `/usr/lib/x86_64-linux-gnu/` and `/usr/lib/aarch64-linux-gnu/` (Debian)
6. System.loadLibrary fallback

~25 downcall handles across 4 libraries:
- avformat: avformat_open_input, avformat_find_stream_info, av_find_best_stream,
  av_seek_frame, av_read_frame, avformat_close_input
- avcodec: avcodec_find_decoder, avcodec_alloc_context3, avcodec_parameters_to_context,
  avcodec_open2, avcodec_send_packet, avcodec_receive_frame, avcodec_free_context,
  av_packet_alloc, av_packet_free, av_packet_unref, avcodec_version
- avutil: av_frame_alloc, av_frame_free, av_image_get_buffer_size, av_image_fill_arrays
- swscale: sws_getContext, sws_scale, sws_freeContext

### FFmpegStructs.java

Struct offset tables selected at runtime by avcodec_version():

FFmpeg 4.x (libavcodec major 58, measured on this system):

| Struct.Field | Offset |
|---|---|
| AVFormatContext.nb_streams | 44 |
| AVFormatContext.streams | 48 |
| AVFormatContext.duration | 1096 |
| AVStream.time_base | 24 |
| AVStream.r_frame_rate | 192 |
| AVStream.codecpar | 208 |
| AVCodecParameters.codec_id | 4 |
| AVCodecParameters.format | 28 |
| AVCodecParameters.width | 56 |
| AVCodecParameters.height | 60 |
| AVFrame.data | 0 |
| AVFrame.linesize | 64 |
| AVFrame.width | 104 |
| AVFrame.height | 108 |
| AVFrame.format | 116 |
| AVFrame.pts | 136 |
| AVPacket.stream_index | 36 |

Unknown major version -> isAvailable() returns false with clear message.
FFmpeg 5.x/6.x/7.x offset tables to be added when needed.

### FFmpegVideoFrameExtractor.java

Implements VideoFrameExtractorProvider. backendName() = "ffmpeg".

extractFrame(Path, Duration):
```
1. avformat_open_input(path)
2. avformat_find_stream_info
3. av_find_best_stream(VIDEO) -> stream_index
4. Read codecpar -> avcodec_find_decoder -> alloc context -> open
5. If time > 0: av_seek_frame(stream_index, target_ts, AVSEEK_FLAG_BACKWARD)
6. Decode loop: av_read_frame -> send_packet -> receive_frame
   - Skip non-video packets
   - Stop at first frame with pts >= target (or first frame after seek)
7. sws_getContext(frame_format -> AV_PIX_FMT_RGBA)
8. sws_scale -> RGBA buffer
9. Repack RGBA -> 0xAARRGGBB int[] -> BufferedImage(TYPE_INT_ARGB_PRE)
10. Cleanup all resources in finally blocks
```

getInfo(Path):
```
1. avformat_open_input(path)
2. avformat_find_stream_info
3. Read AVFormatContext.duration / AV_TIME_BASE -> Duration
4. Find video stream -> AVCodecParameters: width, height, codec_id
5. AVStream.r_frame_rate -> fps
6. Map codec_id -> string (AV_CODEC_ID_H264=27 -> "h264", etc.)
7. Cleanup
```

Constants:
```
AV_TIME_BASE = 1000000
AVMEDIA_TYPE_VIDEO = 0
AV_PIX_FMT_RGBA = 26
SWS_BILINEAR = 2
AVSEEK_FLAG_BACKWARD = 1
AVERROR_EAGAIN = -11 (platform-dependent, check at runtime)
```

### FFmpegVideoReaderSpi.java

Extends NativeVideoReaderSpi. backendName() = "ffmpeg".
isAvailable() delegates to FFmpegNative.isAvailable().
createReaderInstance() returns NativeVideoReader(this, new FFmpegVideoFrameExtractor()).

### Service files

META-INF/services/io.github.ghosthack.imageio.video.VideoFrameExtractorProvider:
  io.github.ghosthack.imageio.video.ffmpeg.FFmpegVideoFrameExtractor

META-INF/services/javax.imageio.spi.ImageReaderSpi:
  io.github.ghosthack.imageio.video.ffmpeg.FFmpegVideoReaderSpi

### Tests

Using test-video-3s.mp4 and test-video-3s-bframes.mp4 from video-common test-jar:
- isAvailableWhenInstalled
- extractFrameAtZero (16x16 red frame)
- extractFrameAtOneSecond (16x16 green frame)
- getInfoReturnsCorrectMetadata (16x16, ~3s, h264, 1fps or 30fps)
- extractThumbnailFromBframeVideo (the B-frame fixture)
- All gated by assumeTrue(FFmpegNative.isAvailable())

---

## Execution Order

1. Refactor video-common
2. Refactor video-apple
3. Refactor video-windows
4. Update BackendPriority
5. Verify existing 160 tests pass
6. Add imageio-native-video-ffmpeg module
7. Verify all tests pass
8. Update README

---

## All files touched

| File | Change |
|------|--------|
| **video-common** | |
| NativeVideoReaderSpi.java | NEW |
| NativeVideoReader.java | NEW |
| VideoFormatRegistry.java | NEW |
| VideoFrameExtractorProvider.java | MODIFY -- add backendName() |
| VideoFrameExtractor.java | MODIFY -- priority-sorted discovery |
| VideoFrameReaderSpi.java | REMOVE |
| VideoFrameReader.java | REMOVE |
| services/javax.imageio.spi.ImageReaderSpi | REMOVE |
| **video-apple** | |
| AppleVideoReaderSpi.java | NEW |
| AppleVideoFrameExtractor.java | MODIFY -- add backendName() |
| services/javax.imageio.spi.ImageReaderSpi | NEW |
| **video-windows** | |
| WindowsVideoReaderSpi.java | NEW |
| WindowsVideoFrameExtractor.java | MODIFY -- add backendName() |
| services/javax.imageio.spi.ImageReaderSpi | NEW |
| **common** | |
| BackendPriority.java | MODIFY -- add ffmpeg to default |
| **imageio-native-video-ffmpeg** (all NEW) | |
| pom.xml | |
| FFmpegNative.java | |
| FFmpegStructs.java | |
| FFmpegVideoFrameExtractor.java | |
| FFmpegVideoReaderSpi.java | |
| services (2 files) | |
| FFmpegVideoFrameExtractorTest.java | |
| **parent** | |
| pom.xml | ADD module |
| README.md | ADD ffmpeg to video section |
