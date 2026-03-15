# imageio-native

[![CI](https://github.com/ghosthack/imageio-native/actions/workflows/ci.yml/badge.svg)](https://github.com/ghosthack/imageio-native/actions/workflows/ci.yml) [![Javadocs](https://javadoc.io/badge/io.github.ghosthack/imageio-native.svg)](https://javadoc.io/doc/io.github.ghosthack/imageio-native) [![Maven Central](https://img.shields.io/maven-central/v/io.github.ghosthack/imageio-native)](https://central.sonatype.com/artifact/io.github.ghosthack/imageio-native)

Java ImageIO readers that delegate to **platform-native image decoding APIs** via [Project Panama](https://openjdk.org/jeps/454) (Foreign Function & Memory API, Java 22+).

Drop the JAR on your classpath and `ImageIO.read()` gains support for **HEIC, AVIF, WEBP, JPEG 2000, JPEG XL, camera RAW, PSD, EXR**, and more. No JNI, no native builds, no manual SPI wiring.

Decode only. Still images only (video files yield a single poster frame). All modules are pure Java — they compile on any OS and auto-detect the platform at runtime.

## Quick start

Add the dependency and the JVM flag:

```xml
<dependency>
    <groupId>io.github.ghosthack</groupId>
    <artifactId>imageio-native</artifactId>
    <version>1.0.2</version>
</dependency>
```

```
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

<details>
<summary>Gradle</summary>

```kotlin
implementation("io.github.ghosthack:imageio-native:1.0.2")
```

</details>

## Format configuration

This implementation has modules for two native APIs:

| Module | Platform | Native API | Formats |
|--------|----------|------------|---------|
| `imageio-native-apple` | macOS | CGImageSource (Apple ImageIO framework) | 60+ |
| `imageio-native-windows` | Windows 10+ | Windows Imaging Component (WIC) | 30+ |

Controlled by the system property `imageio.native.formats`:

| Value | Behaviour |
|-------|-----------|
| `supplemental` (default) | Only formats Java can't decode natively. JPEG/PNG/GIF/BMP/TIFF are left to Java's built-in readers. |
| `all` | Every format the platform can decode, including JPEG/PNG/GIF/BMP/TIFF. |
| `none` | Disabled entirely. |
| comma-separated list | Explicit whitelist, e.g. `heic,avif,webp,jp2`. |

### Supported formats (supplemental defaults)

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

To detect a specific platform module, check its SPI class:

```java
// macOS (imageio-native-apple)
Class.forName("io.github.ghosthack.imageio.apple.AppleImageReaderSpi");

// Windows (imageio-native-windows)
Class.forName("io.github.ghosthack.imageio.windows.WicImageReaderSpi");
```

## Optional backends

The `imageio-native-vips` module is an optional backend that delegates to [libvips](https://www.libvips.org/) for image decoding. It is **not** included in the `imageio-native` aggregator -- add it explicitly to opt in.

```xml
<dependency>
    <groupId>io.github.ghosthack</groupId>
    <artifactId>imageio-native-vips</artifactId>
    <version>1.0.2</version>
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

The vips backend adds cross-platform support (macOS, Linux, Windows) for HEIC, AVIF, WebP, JPEG 2000, PDF, SVG, EXR, FITS, Netpbm, HDR, and more -- depending on the libvips build configuration. It respects the `imageio.native.formats` property (supplemental mode by default).

The SPI declares a fixed set of common formats. Formats not in the list but supported by the installed libvips can still be decoded via the direct `VipsNative` API -- they just won't be auto-discovered by `ImageIO.read()`.

### Backend priority

When multiple backends are on the classpath (e.g. platform-native + vips), the consumer controls which backend handles each format via system properties:

```
# Global ordering (left = highest priority). Default: native,vips,magick
-Dimageio.native.backend.priority=native,vips,magick

# Per-format override
-Dimageio.native.backend.priority.jpeg=vips,native
-Dimageio.native.backend.priority.tiff=vips
```

With no properties set, the default ordering is: platform-native first, then vips. This means existing users see no change when adding `imageio-native-vips` to the classpath -- it only activates for formats the platform-native backend can't handle.

## Video poster frames

The optional `imageio-native-video` module extracts a **single still image** from a video file -- the same way the image modules decode a still image from a HEIC or WebP file. The output is always a `BufferedImage`; no video playback, no audio, no frame sequences.

This means `ImageIO.read(new File("clip.mp4"))` works exactly like `ImageIO.read(new File("photo.heic"))` -- same API, same result type.

```xml
<dependency>
    <groupId>io.github.ghosthack</groupId>
    <artifactId>imageio-native-video</artifactId>
    <version>1.0.2</version>
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
| `imageio-native-video-windows` | Windows 10+ | Media Foundation (IMFSourceReader) | *In progress* |

The Windows video backend is not yet complete -- `isAvailable()` returns `false` until the implementation is finished. See `TODO-windows.md` for details.

## Architecture

```
ImageIO.read(file)
    │
    ▼
ImageReaderSpi              one universal SPI per platform
    │ canDecodeInput():
    │   1. skip Java-native formats in "supplemental" mode
    │   2. probe via native API (CGImageSource / WIC)
    ▼
ImageReader                 lazy decode + cache
    │
    ▼
┌─────────────────────┬──────────────────────────────┐
│ macOS               │ Windows                      │
│ AppleNative         │ WicNative                    │
│ Panama downcalls    │ Panama COM vtable dispatch   │
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

One universal SPI per platform means `canDecodeInput` delegates to the native API to probe headers, so any format the OS adds in a future update works automatically. Both modules compile on all OSes; native loading is guarded by OS checks.

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
├── scripts/                         test fixture generators
└── example-consumer/                standalone demo (not in reactor)
```

## Building

Requires Java 22+ and Maven 3.9+.

```sh
mvn clean test                        # compile + test
mvn install -DskipTests               # install to local repo
mvn -f example-consumer/pom.xml test  # example-consumer

swift scripts/generate-heic-avif-cgimage.swift   # HEIC + AVIF (macOS CGImage)
./scripts/generate-png-webp-chrome.sh            # PNG + WebP  (Chrome headless)
python scripts/generate-all-pillow.py            # all formats (pip: pillow + plugins)
swift scripts/generate-video-fixtures.swift      # video fixtures (macOS AVFoundation)
./scripts/generate-video-bframes.sh              # B-frame video fixture (ffmpeg)
```

## Releasing

1. Set the release version in `pom.xml` (remove `-SNAPSHOT`)
2. Commit, push, and merge via PR
3. CI detects the version change, creates a GitHub release, and deploys to Maven Central
4. Publish the deployment at https://central.sonatype.com/publishing/deployments

## License

[MIT](LICENSE)
