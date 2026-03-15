# Critical Review: imageio-native

**Version**: 1.0.2 | **Language**: Java 22+ | **Lines**: ~5,934 across 38 files | **Modules**: 8

---

## CRITICAL

### 1. Windows video `extractFrame()` is incomplete and ships as a live artifact

`WindowsVideoFrameExtractor.java:99` throws `UnsupportedOperationException` with three TODO blocks (seek, ReadSample loop, pixel extraction). This is published to Maven Central as part of v1.0.2.

`VideoFrameExtractor.extractThumbnail()`, `extractFrame()`, and `extractFrames()` all throw at runtime on Windows. `getInfo()` works but returns `Duration.ZERO` and `null` codec (lines 179, 183) because duration/codec retrieval is also TODO.

**Recommendation**: Either remove `imageio-native-video-windows` from the reactor until complete, or gate the module with `isAvailable()` returning `false` until the implementation is done.

### 2. VideoFormatDetector claims ISO BMFF files that are images, not videos

`VideoFormatDetector.isISOBMFF()` at line 37-39 checks only for `ftyp` at offset 4. HEIC and AVIF files are also ISO BMFF containers with an `ftyp` box. If the `VideoFrameReaderSpi` runs `canDecodeInput` before the still-image SPIs, it will claim HEIC/AVIF files as video, routing them to the video pipeline.

ImageIO SPI ordering is not deterministic. There is no `onRegistration` deconfliction for this overlap.

**Recommendation**: `VideoFormatDetector.isISOBMFF()` must check the ftyp major brand / compatible brands and only match video brands (`isom`, `mp41`, `mp42`, `M4V`, `qt`, `3gp4`, etc.), explicitly excluding image brands (`heic`, `mif1`, `avif`, `avis`). A similar check already exists in `FormatDetector.matchesFtyp()` for the still-image path -- reuse that approach.

### 3. PathAwareImageInputStreamSpi is a global JDK override with no ordering guarantee

`PathAwareImageInputStreamSpi` replaces the JDK's default `File -> FileImageInputStream` SPI for all `ImageIO.read(File)` calls. There is no `onRegistration()` override to guarantee priority over the JDK's built-in SPI.

If the JDK SPI wins (which is implementation-dependent), `VideoFrameReader.getFilePath()` at line 84 will fail because the input won't be `PathAwareImageInputStream`. If the custom SPI wins, it affects every image decode in the JVM, not just video.

**Recommendation**: Add `onRegistration()` to explicitly deregister the JDK's `FileImageInputStream` SPI or to set ordering via `ServiceRegistry.setOrdering()`.

---

## HIGH

### 4. Entire image file loaded into Java heap before native decode

`NativeImageReader.readAllBytes()` (line 159-185) reads the complete file into a `byte[]`. The native bridges then copy this into native memory (`arena.allocateFrom()`). For camera RAW files (50-100MB) or large TIFF/PSD files, this creates at minimum two full copies in memory.

**Recommendation**: Consider a streaming path where native decoders read from a file path or file descriptor directly, avoiding the Java heap copy. This is already how the video module works (path-based).

### 5. `AppleCoreGraphicsHelper` has an implicit init-order dependency on `AppleNative`

`AppleCoreGraphicsHelper.java:56` assumes CoreFoundation and CoreGraphics frameworks are already loaded by `AppleNative`'s static initializer. If anyone uses `AppleCoreGraphicsHelper.cgImageToBufferedImage()` without `AppleNative` having been class-loaded first, `downcall()` will throw `UnsatisfiedLinkError`.

**Recommendation**: Add a defensive `System.load()` call or a static init guard that loads the frameworks if not already loaded.

### 6. Duplicated native rendering code across `AppleNative` and `AppleCoreGraphicsHelper`

`AppleCoreGraphicsHelper` reimplements the CGImage-to-BufferedImage pipeline (CGRECT layout, bitmap constants, CGBitmapContextCreate, CGContextDrawImage, pixel copy) that already exists in `AppleNative.decode()` lines 483-508. Any bug fix needs to be applied in two places.

**Recommendation**: Refactor `AppleNative.decode()` to call `AppleCoreGraphicsHelper` internally, or extract the shared logic into a single utility.

---

## MEDIUM

### 7. `NativeImageReader.checkParam()` is overly restrictive

`NativeImageReader.java:147-164` rejects any `ImageReadParam` with a non-null destination image, non-zero offset, region selection, or subsampling, throwing `IIOException`. Per the `ImageReader` contract, unknown or unsupported params should ideally be ignored rather than errored.

### 8. `VideoFrameReaderSpi` claims formats not supported on all platforms

`VideoFrameReaderSpi.java:21-28` declares WMV, MKV, AVI as format names/suffixes. On macOS, AVFoundation does not natively decode WMV or MKV. The SPI will return `true` from `canDecodeInput()` (since `VideoFormatDetector` matches the container), then fail at runtime during frame extraction.

**Recommendation**: `canDecodeInput()` should additionally check that the detected container format is actually decodable by the current platform backend.

### 9. `FormatDetector.isJavaNativeFormat()` WBMP false-positive risk

The WBMP heuristic (`FormatDetector.java:126-131`) excludes ICO, CUR, and ISO BMFF but any other format starting with `00 00 XX` (where XX != 0) will false-positive. Images in such formats would be skipped in supplemental mode even though Java can't decode them.

### 10. `VideoFrameReader.info` field is not volatile

`VideoFrameReader.java:92` -- the `info` field is lazily initialized without synchronization. The lack of `volatile` means a JIT compiler could legally reorder the write so that another thread sees a partially-constructed `VideoInfo` record.

### 11. CI deploys from Ubuntu but has no Linux build/test job

The `deploy` job in `ci.yml:104` runs on `ubuntu-latest`. There is no Linux build job at all. A Linux build step would verify compilation succeeds there, even though tests would skip.

---

## LOW

### 12. Eclipse IDE files committed to repo

`.classpath`, `.project`, and `.settings/` directories are tracked in git. These should be added to `.gitignore`.

### 13. No `module-info.java` descriptors

The project targets Java 22+ but runs as unnamed modules, requiring `--enable-native-access=ALL-UNNAMED`. Adding module descriptors would allow targeted native access declarations.

### 14. Javadoc lint completely disabled

`pom.xml:111` sets `<doclint>none</doclint>`. The Javadoc is well-written so most lint checks would pass. Re-enabling would catch drift.

### 15. `VideoFrameExtractor.isAvailable()` uses exception-based control flow

`VideoFrameExtractor.java:88-94` catches `UnsupportedOperationException` to determine availability. A dedicated `providerOrNull()` method would be cleaner.

### 16. Duration overflow potential in `AppleVideoFrameExtractor.getInfo()`

Line 376: `(durationValue * 1000L) / durationTimescale` could overflow `long` if `durationValue` is extremely large. Unlikely for real videos but not mathematically prevented.

### 17. Bundled Maven distribution

`apache-maven-3.9.9/` is committed to the repo. Standard practice is to use the Maven Wrapper (`mvnw`) which downloads Maven on first use and only commits a small wrapper script and properties file.
