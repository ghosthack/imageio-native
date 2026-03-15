package io.github.ghosthack.imageio.video.windows;

import io.github.ghosthack.imageio.video.VideoFrameExtractorProvider;
import io.github.ghosthack.imageio.video.VideoInfo;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;

/**
 * Windows implementation of {@link VideoFrameExtractorProvider}.
 * <p>
 * Uses two native APIs:
 * <ul>
 *   <li><b>IShellItemImageFactory</b> — for quick thumbnail extraction (poster frame)</li>
 *   <li><b>IMFSourceReader</b> — for time-based frame extraction at arbitrary positions</li>
 * </ul>
 * Both are COM-based, using the same vtable dispatch pattern as the still-image WicNative backend.
 */
public class WindowsVideoFrameExtractor implements VideoFrameExtractorProvider {

    private static final boolean IS_WINDOWS = System.getProperty("os.name", "")
            .toLowerCase(Locale.ROOT).startsWith("win");

    /**
     * Returns {@code false} — the Windows video backend is not yet complete.
     * <p>
     * {@link #extractFrame} is still a stub (IMFSourceReader read-sample loop
     * and pixel copy are not implemented).  Returning {@code false} prevents
     * {@link io.github.ghosthack.imageio.video.VideoFrameExtractor} from
     * selecting this provider via ServiceLoader.
     */
    @Override
    public boolean isAvailable() {
        // TODO: change to `return IS_WINDOWS;` once extractFrame is implemented
        return false;
    }

    @Override
    public BufferedImage extractFrame(Path videoFile, Duration time) throws IOException {
        if (!IS_WINDOWS) throw new UnsupportedOperationException("Requires Windows");

        try (Arena arena = Arena.ofConfined()) {
            int hrInit = WindowsVideoNative.comInit();
            boolean weInitCom = (hrInit == WindowsVideoNative.S_OK);
            try {
                WindowsVideoNative.mfStartup();
                try {
                    // Create source reader from file path
                    MemorySegment reader = MemorySegment.NULL;
                    MemorySegment mediaType = MemorySegment.NULL;
                    try {
                        reader = WindowsVideoNative.createSourceReader(arena, videoFile.toString());

                        // Create output media type requesting RGB32 uncompressed output
                        mediaType = WindowsVideoNative.createMediaType(arena);

                        // Set major type = Video
                        int hr = (int) WindowsVideoNative.IMFMediaType_SetGUID.invokeExact(
                                WindowsVideoNative.vtable(mediaType, 24),
                                mediaType,
                                WindowsVideoNative.MF_MT_MAJOR_TYPE,
                                WindowsVideoNative.MFMediaType_Video);
                        WindowsVideoNative.check(hr, "IMFMediaType::SetGUID(MF_MT_MAJOR_TYPE) failed");

                        // Set subtype = RGB32
                        hr = (int) WindowsVideoNative.IMFMediaType_SetGUID.invokeExact(
                                WindowsVideoNative.vtable(mediaType, 24),
                                mediaType,
                                WindowsVideoNative.MF_MT_SUBTYPE,
                                WindowsVideoNative.MFVideoFormat_RGB32);
                        WindowsVideoNative.check(hr, "IMFMediaType::SetGUID(MF_MT_SUBTYPE) failed");

                        // Set on the source reader for the first video stream
                        hr = (int) WindowsVideoNative.IMFSourceReader_SetCurrentMediaType.invokeExact(
                                WindowsVideoNative.vtable(reader, 7),
                                reader,
                                WindowsVideoNative.MF_SOURCE_READER_FIRST_VIDEO_STREAM,
                                MemorySegment.NULL, // reserved
                                mediaType);
                        WindowsVideoNative.check(hr, "IMFSourceReader::SetCurrentMediaType failed");

                        // TODO: If time > 0, seek via IMFSourceReader::SetCurrentPosition
                        //   using a PROPVARIANT with VT_I8 containing 100-ns units
                        //   PROPVARIANT pv; pv.vt = VT_I8; pv.hVal.QuadPart = time * 10_000_000;
                        //   reader->SetCurrentPosition(GUID_NULL, pv);

                        // TODO: ReadSample loop:
                        //   hr = reader->ReadSample(MF_SOURCE_READER_FIRST_VIDEO_STREAM,
                        //       0, &actualIndex, &flags, &timestamp, &sample);
                        //   if sample != NULL:
                        //     sample->ConvertToContiguousBuffer(&buffer)
                        //     buffer->Lock(&data, &maxLen, &curLen)
                        //     // Copy RGB32 pixel data (bottom-up) to BufferedImage
                        //     // Get dimensions from the actual output media type
                        //     buffer->Unlock()

                        // TODO: Create BufferedImage from RGB32/BGRA pixel data
                        //   - RGB32 in MF is actually BGRX (B in low byte)
                        //   - Bottom-up scanlines need flipping
                        //   - Width/height from MF_MT_FRAME_SIZE on actual output type

                        throw new UnsupportedOperationException(
                                "IMFSourceReader frame extraction not yet tested on Windows");
                    } finally {
                        WindowsVideoNative.release(mediaType);
                        WindowsVideoNative.release(reader);
                    }
                } finally {
                    WindowsVideoNative.mfShutdown();
                }
            } finally {
                if (weInitCom) WindowsVideoNative.comUninit();
            }
        } catch (IOException e) {
            throw e;
        } catch (UnsupportedOperationException e) {
            throw e;
        } catch (Throwable t) {
            throw new javax.imageio.IIOException("Video frame extraction failed", t);
        }
    }

    @Override
    public VideoInfo getInfo(Path videoFile) throws IOException {
        if (!IS_WINDOWS) throw new UnsupportedOperationException("Requires Windows");

        try (Arena arena = Arena.ofConfined()) {
            int hrInit = WindowsVideoNative.comInit();
            boolean weInitCom = (hrInit == WindowsVideoNative.S_OK);
            try {
                WindowsVideoNative.mfStartup();
                try {
                    MemorySegment reader = MemorySegment.NULL;
                    MemorySegment nativeType = MemorySegment.NULL;
                    try {
                        reader = WindowsVideoNative.createSourceReader(arena, videoFile.toString());

                        // Get the native (compressed) media type for the first video stream
                        MemorySegment ppType = arena.allocate(ValueLayout.ADDRESS);
                        int hr = (int) WindowsVideoNative.IMFSourceReader_GetNativeMediaType.invokeExact(
                                WindowsVideoNative.vtable(reader, 5),
                                reader,
                                WindowsVideoNative.MF_SOURCE_READER_FIRST_VIDEO_STREAM,
                                0, // first type index
                                ppType);
                        WindowsVideoNative.check(hr, "IMFSourceReader::GetNativeMediaType failed");
                        nativeType = ppType.get(ValueLayout.ADDRESS, 0);

                        // Read frame size: MF_MT_FRAME_SIZE is a UINT64 packed as (width << 32 | height)
                        MemorySegment pFrameSize = arena.allocate(ValueLayout.JAVA_LONG);
                        hr = (int) WindowsVideoNative.IMFMediaType_GetUINT64.invokeExact(
                                WindowsVideoNative.vtable(nativeType, 15),
                                nativeType,
                                WindowsVideoNative.MF_MT_FRAME_SIZE,
                                pFrameSize);
                        WindowsVideoNative.check(hr, "GetUINT64(MF_MT_FRAME_SIZE) failed");
                        long frameSize = pFrameSize.get(ValueLayout.JAVA_LONG, 0);
                        int width = (int) (frameSize >>> 32);
                        int height = (int) (frameSize & 0xFFFFFFFFL);

                        // Read frame rate: MF_MT_FRAME_RATE is UINT64 packed as (numerator << 32 | denominator)
                        double frameRate = 0.0;
                        MemorySegment pFrameRate = arena.allocate(ValueLayout.JAVA_LONG);
                        hr = (int) WindowsVideoNative.IMFMediaType_GetUINT64.invokeExact(
                                WindowsVideoNative.vtable(nativeType, 15),
                                nativeType,
                                WindowsVideoNative.MF_MT_FRAME_RATE,
                                pFrameRate);
                        if (!WindowsVideoNative.failed(hr)) {
                            long rate = pFrameRate.get(ValueLayout.JAVA_LONG, 0);
                            long numerator = rate >>> 32;
                            long denominator = rate & 0xFFFFFFFFL;
                            if (denominator > 0) {
                                frameRate = (double) numerator / denominator;
                            }
                        }

                        // TODO: Read duration from presentation descriptor
                        //   IMFSourceReader::GetPresentationAttribute(
                        //       MF_SOURCE_READER_MEDIASOURCE, MF_PD_DURATION, &propvar)
                        //   Duration is in 100-ns units as VT_UI8
                        Duration duration = Duration.ZERO;

                        // TODO: Read codec subtype GUID and map to string
                        //   GetGUID(MF_MT_SUBTYPE) → compare against known FourCC GUIDs
                        String codec = null;

                        return new VideoInfo(width, height, duration, codec, frameRate);
                    } finally {
                        WindowsVideoNative.release(nativeType);
                        WindowsVideoNative.release(reader);
                    }
                } finally {
                    WindowsVideoNative.mfShutdown();
                }
            } finally {
                if (weInitCom) WindowsVideoNative.comUninit();
            }
        } catch (IOException e) {
            throw e;
        } catch (UnsupportedOperationException e) {
            throw e;
        } catch (Throwable t) {
            throw new javax.imageio.IIOException("Video info extraction failed", t);
        }
    }
}
