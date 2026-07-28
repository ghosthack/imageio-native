# imageio-native

[![CI](https://github.com/ghosthack/imageio-native/actions/workflows/ci.yml/badge.svg)](https://github.com/ghosthack/imageio-native/actions/workflows/ci.yml) [![Javadocs](https://javadoc.io/badge/io.github.ghosthack/imageio-native.svg)](https://javadoc.io/doc/io.github.ghosthack/imageio-native) [![Maven Central](https://img.shields.io/maven-central/v/io.github.ghosthack/imageio-native)](https://central.sonatype.com/artifact/io.github.ghosthack/imageio-native)

Java ImageIO readers that delegate to **platform-native image decoding APIs** via [Project Panama](https://openjdk.org/jeps/454) (Foreign Function & Memory API, Java 26+).

Add the JAR to your module path (or class path) and `ImageIO.read()` gains support for **HEIC, AVIF, WEBP, JPEG 2000, JPEG XL, camera RAW, PSD, EXR**, and more. No JNI, no native builds, no manual SPI wiring.

Image decoding only—no encoding. Optional video modules return a single still
frame, not playback, audio, or frame sequences. The Java modules compile on any
OS and detect compatible backends at runtime.

## Quick start

Add the dependency:

```xml
<dependency>
    <groupId>io.github.ghosthack</groupId>
    <artifactId>imageio-native</artifactId>
    <version>2.0.0</version>
</dependency>
```

<details>
<summary>Gradle</summary>

```kotlin
implementation("io.github.ghosthack:imageio-native:2.0.0")
```

</details>

For a modular application, require the cross-platform image aggregator:

```java
module com.example.app {
    requires io.github.ghosthack.imageio;
}
```

Grant native access only to the backend modules used on the host. For example,
a modular macOS application can launch with:

```sh
java --module-path lib \
     --enable-native-access=io.github.ghosthack.imageio.apple \
     --module com.example.app/com.example.Main
```

Then use standard ImageIO:

```java
BufferedImage img = ImageIO.read(new File("photo.heic"));
BufferedImage img = ImageIO.read(new File("photo.avif"));
BufferedImage img = ImageIO.read(new File("photo.webp"));
```

All standard lookup methods work: `getImageReadersByFormatName`, `getImageReadersByMIMEType`, `getImageReadersBySuffix`.

The `imageio-native` aggregator pulls in both platform modules and auto-selects at runtime. You can also depend on `imageio-native-apple` or `imageio-native-windows` directly.

## Platform and format support

The main aggregator provides native backends for macOS and Windows:

| Module | Platform | Native API | Formats |
|--------|----------|------------|---------|
| `imageio-native-apple` | macOS | CGImageSource (Apple ImageIO framework) | 60+ |
| `imageio-native-windows` | Windows 10+ | Windows Imaging Component (WIC) | 30+ |

- **Both platforms:** HEIC, HEIF, AVIF, WebP, DNG, CR2, CR3, NEF, ARW, ICO,
  CUR, DDS, and many more camera RAW formats.

- **Apple-only:** JPEG 2000, JPEG XL, PSD, OpenEXR, Radiance HDR, DICOM, ICNS,
  TGA, SGI, PBM/PGM/PPM, PICT, MPO, KTX, KTX2, ASTC, PVR, and ATX.

- **Windows-only:** JPEG-XR (JXR/WDP/HDP).

Actual support depends on the codecs installed on the host. The
[optional libvips and ImageMagick backends](#optional-backends) add Linux support
and additional formats; the [optional video modules](#video-poster-frames) add
poster-frame extraction.

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

> **Minimum image dimensions:** The HEVC and AV1 codec extensions cannot decode
> images smaller than 8×8 pixels. Header parsing and format detection may still
> succeed, but pixel decoding fails with `E_INVALIDARG`. This is a limitation of
> the Windows codec extensions, not WIC or this library.

## Native access

Every artifact is an explicit named JPMS module. Applications that also decode
video can require the video aggregator alongside the image aggregator:

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
| FFmpeg | `ffmpeg.ffm` |

### Class-path applications

When the application and its dependencies run on the class path, they belong to
the unnamed module. Grant native access with:

```text
--enable-native-access=ALL-UNNAMED
```

The existing `META-INF/services` registrations remain in the JARs for
class-path compatibility. Equivalent `uses` and `provides` declarations enable
the same ImageIO and backend discovery on the module path. Shaded or fat JARs
that do not preserve a named module also use the class-path form.

## Backend selection and routing

The host Java runtime is the baseline. With no imageio-native backend modules
installed, ImageIO behaves exactly as supplied by that runtime.

Adding a backend module is an explicit opt-in to that backend. If exactly one
installed backend can decode an input, that backend owns the input, including
formats that the JDK can also decode.

For example, adding the Windows module means WIC owns every input that WIC can
actually decode. If WIC can decode a JPEG, the JPEG is routed to WIC rather than
the JDK reader. Applications that want the JDK reader for a particular
intersection can explicitly route that format back to the host.

<details>
<summary>Detailed routing contract and configuration</summary>

### Principles

1. **Preserve the host by default.** With no added backend capable of decoding
   an input, the routing SPI declines it and the host ImageIO implementation
   proceeds normally.
2. **Module presence is authorization.** Adding a backend module opts into all
   formats that backend can actually decode. There is no implicit
   "supplemental formats only" mode.
3. **A single added backend overrides the JDK.** If one installed backend is
   capable, it owns the input even when a host JDK reader is also capable.
4. **Capability is input-specific.** Declaring support for a format or container
   only makes a backend a candidate. A lightweight probe must confirm that the
   backend on this machine can open the actual input. This accounts for optional
   Windows codecs and for different codecs inside the same video container.
5. **Non-intersecting capability sets do not collide.** A format supported by
   only one added backend belongs to that backend without consulting collision
   policy.
6. **Intersections have one deterministic owner.** When multiple added backends
   are capable, a portable/software-codec backend wins by default over a
   platform-native backend. This is a predictability and portability policy,
   not a claim that one implementation is inherently more secure.
7. **Applications control intersections explicitly.** A small,
   application-owned Java configuration can select a different backend—or the
   host JDK—for any intersecting format.
8. **Routing is not decode-time fallback.** The router filters candidates using
   capability probes, selects exactly one owner, and invokes it once. If the
   selected decoder subsequently fails, its error is returned; the router does
   not silently retry another implementation.
9. **Configuration is stable.** Application routing must be installed before
   the first routed ImageIO operation. The configuration freezes on first use,
   making behavior independent of class-loading or SPI-registration timing.
10. **Direct backend APIs bypass routing.** Calling a WIC, Media Foundation,
    FFmpeg, libvips, or ImageMagick API directly always invokes that backend.

### Ownership algorithm

For an input `x` with detected format `f`:

```text
candidates = installed backends
    that declare f
    and whose lightweight probe accepts x

if candidates is empty:
    decline x                         // host JDK behavior

if policy explicitly selects host for f:
    decline x                         // host JDK behavior

if policy selects backend b for f and b is in candidates:
    select b

if candidates contains exactly one backend:
    select that backend               // overrides the JDK

select the deterministic default:
    portable/software before platform-native
    stable backend ID as the tie-breaker within a class
```

An explicit backend preference only resolves among capable candidates. It never
forces an unavailable backend to decode an unsupported input. Unknown backend
IDs in application configuration fail fast as configuration errors.

### Examples

The JPEG/FFmpeg combination is intentionally illustrative; it defines the
routing semantics independently of the formats implemented by today's modules.

Assume the host JDK, Windows/WIC, and FFmpeg can all decode JPEG:

| Installed modules | Application rule | JPEG owner |
|---|---|---|
| None | None | Host JDK |
| Windows | None | Windows/WIC |
| FFmpeg | None | FFmpeg |
| Windows + FFmpeg | None | FFmpeg (portable/software default) |
| Windows + FFmpeg | `jpeg -> windows` | Windows/WIC |
| Windows + FFmpeg | `jpeg -> host` | Host JDK |

If the Windows module is installed but WIC's probe rejects a particular JPEG,
WIC is not a candidate for that input. Another capable added backend owns it,
or the router declines it and leaves it to the host.

For non-intersecting formats:

```text
Windows supports HEIC, FFmpeg does not  -> Windows owns HEIC
FFmpeg supports MKV, Windows does not   -> FFmpeg owns MKV
Neither supports a PNG input            -> host ImageIO handles PNG
```

### Application-owned routing

Applications can keep all intersection choices in one minimal Java class:

```java
import io.github.ghosthack.imageio.common.ImageIoRouting;

public final class AppImageIoRouting {
    private AppImageIoRouting() {}

    public static void install() {
        ImageIoRouting.configure(routes -> routes
                .prefer("jpeg", "windows")
                .prefer("heic", "windows")
                .prefer("mkv", "ffmpeg")
                .preferHost("png"));
    }
}
```

Valid backend IDs are `apple`, `windows`, `vips`, `magick`, and `ffmpeg` when
the corresponding module is installed. The application calls the installation
method once during startup, before its first ImageIO operation:

```java
AppImageIoRouting.install();
```

Configuration is explicit Java code rather than registration-order tricks,
system-property priority lists, or magically discovered application classes.

### Registration model

Backend modules register backend services, not mutually competing
`ImageReaderSpi` implementations. One routing SPI per media category owns
integration with ImageIO:

```text
ImageIO
    |
    v
routing SPI
    |
    +-- detect the format once
    +-- discover installed backend services
    +-- run lightweight capability probes
    +-- resolve ownership
    `-- delegate to exactly one backend
```

The routing SPI is ordered before host readers, but returns `false` whenever no
added backend owns the input. Consequently it affects a host format only when
an installed backend has positively claimed that particular input.

Backend-to-backend `IIORegistry.setOrdering()`, shared format advertisements on
every backend SPI, global priority properties, and decode-time retry chains are
not part of this design.

</details>

<details>
<summary>Runtime detection for optional dependencies</summary>

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

</details>

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

## Advanced ImageIO behavior

### `ImageReadParam`

Native readers implement source regions, subsampling and subsampling offsets,
destination images and offsets, source and destination bands, destination
types, and source render sizes. Progressive-pass selection is not silently
ignored: requesting it fails with `IIOException`, because the native decoder
APIs do not expose compatible progressive passes.

Source render sizing is pushed into Apple, WIC, libvips, ImageMagick,
AVFoundation, and FFmpeg native processing. libvips and ImageMagick also crop
before materializing the Java image; the shared layer applies any remaining
operations with the same normalized ImageIO regions for every backend.

Readers reject a required native or Java intermediate larger than 512 MiB
instead of unexpectedly allocating it. This includes full-source ImageMagick
processing and the worst-case thumbnail surfaces used for exact Apple/WIC
render sizes. Override the limit in bytes with
`-Dimageio.native.maxIntermediateBytes=<bytes>`.

### EXIF orientation

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
