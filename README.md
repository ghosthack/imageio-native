# imageio-native

[![CI](https://github.com/ghosthack/imageio-native/actions/workflows/ci.yml/badge.svg)](https://github.com/ghosthack/imageio-native/actions/workflows/ci.yml) [![Javadocs](https://javadoc.io/badge/io.github.ghosthack/imageio-native.svg)](https://javadoc.io/doc/io.github.ghosthack/imageio-native) [![Maven Central](https://img.shields.io/maven-central/v/io.github.ghosthack/imageio-native)](https://central.sonatype.com/artifact/io.github.ghosthack/imageio-native)

Java ImageIO readers that delegate to **platform-native image decoding APIs** via [Project Panama](https://openjdk.org/jeps/454) (Foreign Function & Memory API, Java 22+).

Drop the JAR on your classpath and `ImageIO.read()` gains support for **HEIC, AVIF, WEBP, JPEG 2000, JPEG XL, camera RAW, PSD, EXR**, and more. No JNI, no native builds, no manual SPI wiring.

Decode only. Still images only. Both modules are pure Java — they compile on any OS and auto-detect the platform at runtime.

## Quick start

Add the dependency and the JVM flag:

```xml
<dependency>
    <groupId>io.github.ghosthack</groupId>
    <artifactId>imageio-native</artifactId>
    <version>1.0.0</version>
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
implementation("io.github.ghosthack:imageio-native:1.0.0")
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

## Project structure

```
├── pom.xml                          parent POM (reactor)
├── imageio-native-common/           shared format registry & detection
├── imageio-native-apple/            macOS module (6 source files)
├── imageio-native-windows/          Windows module (7 source files)
├── imageio-native/                  cross-platform aggregator
└── example-consumer/                standalone demo (not in reactor)
```

## Building

Requires Java 22+ and Maven 3.9+.

```sh
mvn clean test                        # compile + test
mvn install -DskipTests               # install to local repo
mvn -f example-consumer/pom.xml test  # example-consumer

swift generate-heic-avif-cgimage.swift   # HEIC + AVIF (macOS CGImage)
./generate-png-webp-chrome.sh           # PNG + WebP  (Chrome headless)
python generate-all-pillow.py           # all formats (pip: pillow + plugins)
```

## Releasing

1. Set the release version in `pom.xml` (remove `-SNAPSHOT`)
2. Commit, push, and merge via PR
3. CI detects the version change, creates a GitHub release, and deploys to Maven Central
4. Publish the deployment at https://central.sonatype.com/publishing/deployments

## License

[MIT](LICENSE)
