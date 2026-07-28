# imageio-native

[![CI](https://github.com/ghosthack/imageio-native/actions/workflows/ci.yml/badge.svg)](https://github.com/ghosthack/imageio-native/actions/workflows/ci.yml) [![Javadocs](https://javadoc.io/badge/io.github.ghosthack/imageio-native.svg)](https://javadoc.io/doc/io.github.ghosthack/imageio-native) [![Maven Central](https://img.shields.io/maven-central/v/io.github.ghosthack/imageio-native)](https://central.sonatype.com/artifact/io.github.ghosthack/imageio-native)

Java ImageIO readers that delegate to **platform-native image decoding APIs** via [Project Panama](https://openjdk.org/jeps/454) (Foreign Function & Memory API, Java 26+).

Drop the JAR on your classpath and `ImageIO.read()` gains support for **HEIC, AVIF, WEBP, JPEG 2000, JPEG XL, camera RAW, PSD, EXR**, and more. No JNI, no native builds, no manual SPI wiring.

Decode only. Still images only (video files yield a single poster frame). All modules are pure Java — they compile on any OS and auto-detect the platform at runtime.

## Quick start

Add the dependency:

```xml
<dependency>
    <groupId>io.github.ghosthack</groupId>
    <artifactId>imageio-native</artifactId>
    <version>2.0.0</version>
</dependency>
```

For a class-path application, grant native access to the unnamed module:

```text
--enable-native-access=ALL-UNNAMED
```

Then use standard ImageIO:

```java
BufferedImage img = ImageIO.read(new File("photo.heic"));
BufferedImage img = ImageIO.read(new File("photo.avif"));
BufferedImage img = ImageIO.read(new File("photo.webp"));
```

All standard lookup methods work: `getImageReadersByFormatName`, `getImageReadersByMIMEType`, `getImageReadersBySuffix`.

The `imageio-native` aggregator pulls in both platform modules and auto-selects at runtime. You can also depend on `imageio-native-apple` or `imageio-native-windows` directly.

### `ImageReadParam`

Native readers implement source regions, subsampling and subsampling offsets,
destination images and offsets, source and destination bands, destination
types, and source render sizes. Progressive-pass selection is not silently
ignored: requesting it fails with `IIOException`, because the native decoder
APIs do not expose compatible progressive passes.

Source render sizing is pushed into Apple, WIC, libvips, ImageMagick, AVFoundation,
and FFmpeg native processing. libvips and ImageMagick also crop before
materializing the Java image; the shared layer applies any remaining operations
with the same normalized ImageIO regions for every backend.

Readers reject a required native or Java intermediate larger than 512 MiB
instead of unexpectedly allocating it. This includes full-source ImageMagick
processing and the worst-case thumbnail surfaces used for exact Apple/WIC
render sizes. Override the limit in bytes with
`-Dimageio.native.maxIntermediateBytes=<bytes>`.

<details>
<summary>Gradle</summary>

```kotlin
implementation("io.github.ghosthack:imageio-native:2.0.0")
```

</details>

### JPMS module path

Every artifact is an explicit JPMS module. A modular application can require
the cross-platform aggregators:

```java
module com.example.app {
    requires io.github.ghosthack.imageio;
    requires io.github.ghosthack.imageio.video;
}
```

Native access can then be granted only to the backend modules used on the host,
rather than to every class-path dependency:

| Backend | `--enable-native-access` value |
|---|---|
| Apple images | `io.github.ghosthack.imageio.apple` |
| Apple video | `io.github.ghosthack.imageio.apple,io.github.ghosthack.imageio.video.apple` |
| Windows images | `io.github.ghosthack.panama.media.core,io.github.ghosthack.panama.media.comruntime,io.github.ghosthack.panama.media.wic` |
| Windows video | `io.github.ghosthack.panama.media.core,io.github.ghosthack.panama.media.comruntime,io.github.ghosthack.panama.media.mediafoundation` |
| libvips | `io.github.ghosthack.imageio.vips` |
| ImageMagick | `io.github.ghosthack.imageio.magick` |
| FFmpeg | `io.github.ghosthack.imageio.video.ffmpeg` |

For example, a modular macOS image application can launch with:

```sh
java --enable-native-access=io.github.ghosthack.imageio.apple \
     --module-path lib \
     --module com.example.app/com.example.Main
```

The existing `META-INF/services` registrations remain in the JARs for
class-path compatibility; equivalent `uses` and `provides` declarations enable
the same ImageIO and backend discovery on the module path.

## Routing

This implementation has modules for two native APIs:

| Module | Platform | Native API | Formats |
|--------|----------|------------|---------|
| `imageio-native-apple` | macOS | CGImageSource (Apple ImageIO framework) | 60+ |
| `imageio-native-windows` | Windows 10+ | Windows Imaging Component (WIC) | 30+ |

The host JDK is unchanged when no added backend can decode an input. Installing
a backend opts into every format that backend declares and can actually decode,
including intersections such as JPEG and PNG. A single capable backend therefore
owns the input ahead of the JDK reader.

When multiple added backends can decode the same input, portable/software
backends win by default over platform-native backends; stable backend IDs break
ties. Applications can own intersection choices explicitly during startup:

```java
import io.github.ghosthack.imageio.common.ImageIoRouting;

ImageIoRouting.configure(routes -> routes
        .prefer("jpeg", "windows")
        .prefer("heic", "windows")
        .prefer("mkv", "ffmpeg")
        .preferHost("png"));
```

Valid backend IDs are `apple`, `windows`, `vips`, `magick`, and `ffmpeg` when
the corresponding module is installed. Configuration freezes on the first
routed operation. A selected decoder is invoked once; decode failure is returned
to the caller without retrying another backend. Direct backend APIs bypass the
router.

See [ROUTING.md](ROUTING.md) for the complete ownership contract.

### Supported formats

**Both platforms:** HEIC, HEIF, AVIF, WebP, DNG, CR2, CR3, NEF, ARW, ICO, CUR, DDS, and many more camera RAW formats

**Apple-only:** JPEG 2000, JPEG XL, PSD, OpenEXR, Radiance HDR, DICOM, ICNS, TGA, SGI, PBM/PGM/PPM, PICT, MPO, KTX, KTX2, ASTC, PVR, ATX

**Windows-only:** JPEG-XR (JXR/WDP/HDP)

### Windows codec requirements

| Format | Requirement |
|--------|-------------|
| HEIC/HEIF | [HEVC Video Extensions](https://apps.microsoft.com/detail/9nmzlz57r3t7) from Microsoft Store |
| AVIF | [AV1 Video Extensions](https://apps.microsoft.com/detail/9mvzqvxjbq9v) from Microsoft Store |
| WebP | Built-in (Windows 10 1809+) |
| JPEG-XR | Built-in |

To check whether the required codecs are already installed:

```powershell
Get-AppxPackage -Name *hevc*   # HEVC (for HEIC/HEIF)
Get-AppxPackage -Name *av1*    # AV1 (for AVIF)
```

> **Minimum image dimensions:** The HEVC and AV1 codec extensions cannot decode very small images. HEIC/HEIF requires at least 8×8 pixels and AVIF requires at least 8×8 pixels. Smaller images will fail with `E_INVALIDARG` during pixel decoding even though header parsing and format detection succeed. This is a limitation of the Windows codec extensions, not of WIC or this library.

## Runtime detection

To check at runtime whether imageio-native is on the classpath (e.g. when it's an optional dependency), probe a class from the `imageio-native-common` module — it's a transitive dependency of every platform module, so it's always present regardless of which artifact was included:

```java
boolean available = false;
try {
    Class.forName("io.github.ghosthack.imageio.common.FormatRegistry");
    available = true;
} catch (ClassNotFoundException ignored) { }
```

To detect a specific platform module, check its backend service class:

```java
// macOS (imageio-native-apple)
Class.forName("io.github.ghosthack.imageio.apple.AppleImageBackend");

// Windows (imageio-native-windows)
Class.forName("io.github.ghosthack.imageio.windows.WindowsImageBackend");
```

## Optional backends

The `imageio-native-vips` module is an optional backend that delegates to [libvips](https://www.libvips.org/) for image decoding. It is **not** included in the `imageio-native` aggregator -- add it explicitly to opt in.

```xml
<dependency>
    <groupId>io.github.ghosthack</groupId>
    <artifactId>imageio-native-vips</artifactId>
    <version>2.0.0</version>
</dependency>
```

Requires libvips installed on the system:

```sh
# macOS (MacPorts)
sudo port install vips

# macOS (Homebrew)
brew install vips

# Debian/Ubuntu
sudo apt install libvips-dev
```

The vips backend adds cross-platform support (macOS, Linux, Windows) for HEIC, AVIF, WebP, JPEG 2000, PDF, SVG, EXR, FITS, Netpbm, HDR, and more -- depending on the libvips build configuration.

The SPI declares a fixed set of common formats. Formats not in the list but supported by the installed libvips can still be decoded via the direct `VipsNative` API -- they just won't be auto-discovered by `ImageIO.read()`.

### ImageMagick

The `imageio-native-magick` module is an optional backend that delegates to [ImageMagick 7](https://imagemagick.org/) (MagickWand C API). Supports 200+ formats including EPS, PSD, XCF, DPX, TGA, PCX, XBM/XPM, and more.

```xml
<dependency>
    <groupId>io.github.ghosthack</groupId>
    <artifactId>imageio-native-magick</artifactId>
    <version>2.0.0</version>
</dependency>
```

Requires ImageMagick 7 installed on the system. Both Q16 and Q16HDRI builds are supported.

```sh
# macOS (MacPorts)
sudo port install ImageMagick7

# macOS (Homebrew)
brew install imagemagick

# Debian/Ubuntu
sudo apt install libmagickwand-7-dev
```

## Video poster frames

The optional `imageio-native-video` module extracts a **single still image** from a video file -- the same way the image modules decode a still image from a HEIC or WebP file. The output is always a `BufferedImage`; no video playback, no audio, no frame sequences.

This means `ImageIO.read(new File("clip.mp4"))` works exactly like `ImageIO.read(new File("photo.heic"))` -- same API, same result type.

```xml
<dependency>
    <groupId>io.github.ghosthack</groupId>
    <artifactId>imageio-native-video</artifactId>
    <version>2.0.0</version>
</dependency>
```

Through the standard ImageIO SPI:

```java
// Poster frame via ImageIO -- identical to reading any image
BufferedImage poster = ImageIO.read(new File("clip.mp4"));
```

Or through the direct API for more control:

```java
// Thumbnail (poster frame at or near t=0)
BufferedImage thumb = VideoFrameExtractor.extractThumbnail(Path.of("clip.mp4"));

// Frame at a specific time
BufferedImage frame = VideoFrameExtractor.extractFrame(
        Path.of("clip.mp4"), Duration.ofSeconds(30));

// Video metadata (dimensions, duration, codec, frame rate)
VideoInfo info = VideoFrameExtractor.getInfo(Path.of("clip.mp4"));
```

| Module | Platform | Native API | Containers |
|--------|----------|------------|------------|
| `imageio-native-video-apple` | macOS | AVFoundation (AVAssetImageGenerator) | MP4, MOV, M4V, 3GP |
| `imageio-native-video-windows` | Windows 10+ | Media Foundation (IMFSourceReader) | Media Foundation-supported containers |
| `imageio-native-video-ffmpeg` | macOS ARM64, Windows x64, Linux x64 (optional) | Bundled FFmpeg 8.1.2 via `ffmpeg-ffm` | All bundled FFmpeg-supported containers |

The Windows still-image and video backends use the published
[`panama-media`](https://github.com/ghosthack/panama-media) WIC and Media
Foundation bindings. Codec and container support ultimately depends on the
media components installed in Windows.

### FFmpeg video backend

The `imageio-native-video-ffmpeg` module is an optional, self-contained
cross-platform video backend. It uses the published
[`ffmpeg-ffm`](https://github.com/ghosthack/ffmpeg-ffm) jextract bindings and
matching FFmpeg 8.1.2 native libraries. The appropriate native classifier is
selected automatically for macOS ARM64, Windows x64, and Linux x64; users do
not need to install FFmpeg separately. It is the only video backend available
on Linux.

```xml
<dependency>
    <groupId>io.github.ghosthack</groupId>
    <artifactId>imageio-native-video-ffmpeg</artifactId>
    <version>2.0.0</version>
</dependency>
```

The native libraries are LGPL-only builds and are extracted to the
`ffmpeg-ffm` cache on first use. For LGPL relinking or a custom deployment,
override the bundled libraries with `-Dffmpegffm.libdir=/path/to/libs` or the
`FFMPEG_FFM_LIBDIR` environment variable. An override must provide an
ABI-compatible FFmpeg 8.x build (`libavformat` major 62).

## Architecture

```
ImageIO.read(file)
    │
    ▼
RoutingImageReaderSpi       one SPI for all still-image backends
    │
    ├── detect format once
    ├── discover installed backend services
    ├── probe actual input capability
    └── resolve exactly one owner
    ▼
Backend ImageReader         one decode attempt, lazy decode + cache
    │
    ▼
┌─────────────────────┬──────────────────────────────┐
│ macOS               │ Windows                      │
│ AppleNative         │ panama-media WIC             │
│ Panama downcalls    │ shared COM/FFM bindings      │
│ CoreGraphics +      │ ole32 + windowscodecs        │
│   ImageIO.framework │ IWICImagingFactory →         │
│ CGImageSource →     │   IWICStream → Decoder →     │
│   CGBitmapContext → │   FormatConverter →          │
│   pixel copy        │   CopyPixels                 │
└─────────────────────┴──────────────────────────────┘
    │
    ▼
BufferedImage (TYPE_INT_ARGB_PRE)
```

Both platforms output BGRA premultiplied pixels that map directly to `TYPE_INT_ARGB_PRE` when read as little-endian ints — zero pixel conversion overhead.

Backend modules register `ImageDecoderBackend` services rather than competing
`ImageReaderSpi` implementations. The routing SPI declines inputs with no
capable added backend, allowing the host ImageIO readers to proceed normally.
Both platform modules compile on all operating systems; native loading remains
guarded by availability checks.

## EXIF orientation

Images from phones and cameras often carry an EXIF orientation tag (values 1-8) that describes how the sensor image should be rotated or flipped for correct display. Both backends apply this transform automatically during decode so the returned `BufferedImage` is always display-ready.

| EXIF value | Transform |
|------------|-----------|
| 1 | None (identity) |
| 2 | Flip horizontal |
| 3 | Rotate 180 |
| 4 | Flip vertical |
| 5 | Rotate 90 + flip horizontal |
| 6 | Rotate 90 |
| 7 | Rotate 270 + flip horizontal |
| 8 | Rotate 270 |

**macOS (CGImageSource):** Uses `CGImageSourceCreateThumbnailAtIndex` with `kCGImageSourceCreateThumbnailWithTransform = true` and the thumbnail size set to the full image dimensions. CoreGraphics applies the EXIF transform internally during hardware-accelerated decode -- same decode path, same performance, correct orientation.

**Windows (WIC):** Reads the EXIF orientation tag via `IWICMetadataQueryReader`, then inserts an `IWICBitmapFlipRotator` between the frame decoder and the format converter. The flip-rotator is a zero-copy coordinate remap -- it transforms pixel coordinates during `CopyPixels` rather than allocating a second buffer. For orientation 1 (no rotation), the flip-rotator is skipped entirely.

Both `getSize()` and `decode()` are orientation-aware: dimensions are swapped for orientations 5-8 (90/270 rotations), so width and height always reflect the display-oriented image.

**Performance impact:** Negligible. Both platforms apply the transform as part of the existing decode pipeline with no extra buffer allocations or pixel copies. The macOS path creates a small options dictionary per decode call; the Windows flip-rotator is a zero-copy coordinate remap. Orientation 1 (the common case for non-phone images) skips the transform entirely.

## Project structure

```
├── pom.xml                          parent POM (reactor)
├── imageio-native-common/           shared format registry & detection
├── imageio-native-apple/            macOS image module
├── imageio-native-windows/          Windows image module
├── imageio-native/                  cross-platform image aggregator
├── imageio-native-video-common/     shared video SPI & format detection
├── imageio-native-video-apple/      macOS video module (AVFoundation)
├── imageio-native-video-windows/    Windows video module (Media Foundation)
├── imageio-native-video/            cross-platform video aggregator
├── imageio-native-vips/             optional libvips backend
├── imageio-native-magick/           optional ImageMagick 7 backend
├── imageio-native-video-ffmpeg/     optional FFmpeg video backend
├── scripts/                         test fixture generators
└── example-consumer/                standalone demo (not in reactor)
```

## Building

Requires Java 26+ and Maven 3.9+.

```sh
mvn clean test                        # compile + test
mvn install -DskipTests               # install to local repo
mvn -f example-consumer/pom.xml test  # example-consumer

swift scripts/generate-heic-avif-cgimage.swift   # HEIC + AVIF (macOS + heif-enc)
./scripts/generate-png-webp-chrome.sh            # PNG + WebP  (Chrome headless)
python scripts/generate-all-pillow.py            # all formats (pip: pillow + plugins)
swift scripts/generate-video-fixtures.swift      # video fixtures (macOS AVFoundation)
./scripts/generate-video-bframes.sh              # B-frame video fixture (ffmpeg)
```

## Releasing

1. Set the release version in `pom.xml` (remove `-SNAPSHOT`)
2. Commit, push, and merge via PR; wait for CI to pass on the release commit
3. Publish a non-prerelease GitHub Release tagged `v<version>` and targeting that commit
4. The release workflow validates the tag, reruns CI at the tag, signs the artifacts,
   and publishes them to Maven Central

Maven Central publication is automatic; no separate portal approval is required.

## License

[MIT](LICENSE)
