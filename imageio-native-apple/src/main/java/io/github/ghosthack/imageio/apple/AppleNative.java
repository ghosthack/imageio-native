package io.github.ghosthack.imageio.apple;

import io.github.ghosthack.imageio.common.ImageReadParamSupport;
import io.github.ghosthack.panama.media.cgimage.CGImageSource;
import io.github.ghosthack.panama.media.corefoundation.CoreFoundation;

import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.lang.foreign.*;

/**
 * Decodes images through Apple's CGImageSource API, which supports HEIC, AVIF,
 * WEBP, and many other formats through the Apple Media Engine.
 * <p>
 * The native calls go through the {@code panama-media-corefoundation} and
 * {@code panama-media-cgimage} bindings; this class owns the decode policy
 * (pixel limit, render-size bound, orientation handling).
 * <p>
 * EXIF orientation is applied automatically: {@link #decode} uses
 * {@code CGImageSourceCreateThumbnailAtIndex} with
 * {@code kCGImageSourceCreateThumbnailWithTransform = true}, and {@link #getSize}
 * reads the orientation property to return display-oriented dimensions.
 * <p>
 * Requires native access for {@code io.github.ghosthack.panama.media.core},
 * {@code io.github.ghosthack.panama.media.corefoundation} and
 * {@code io.github.ghosthack.panama.media.cgimage}, or {@code ALL-UNNAMED}
 * when used on the class path.
 * Only functional on macOS; all entry points return failure gracefully on other OSes.
 */
final class AppleNative {

    private AppleNative() {}

    // ── OS guard ────────────────────────────────────────────────────────

    static final boolean IS_MACOS =
            io.github.ghosthack.panama.media.cgimage.ImageIO.isAvailable();

    private static final boolean ON_MACOS = System.getProperty("os.name", "")
            .toLowerCase(java.util.Locale.ROOT).contains("mac");

    /**
     * Explains why decoding is unavailable: not macOS, or macOS where the
     * panama-media bindings could not load (typically missing native access).
     */
    static String unavailableMessage(String offMacMessage) {
        return ON_MACOS
                ? "Apple ImageIO bindings failed to load; grant native access to "
                        + "io.github.ghosthack.panama.media.corefoundation and "
                        + "io.github.ghosthack.panama.media.cgimage"
                : offMacMessage;
    }

    /**
     * Maximum number of pixels (width * height) we are willing to decode.
     * Prevents OOM on malicious/corrupt images declaring huge dimensions.
     * Default: 256 megapixels (e.g. 16384 x 16384).
     */
    static final long MAX_PIXELS = Long.getLong("imageio.native.maxPixels", 256L * 1024 * 1024);

    // ── Helper: read an int property from a CFDictionary ────────────────

    /**
     * Reads an integer value from a CFDictionary by key.
     * Returns {@code defaultValue} if the key is absent or not a CFNumber.
     */
    private static int dictGetInt(MemorySegment dict, MemorySegment key, int defaultValue) {
        try {
            MemorySegment val = CoreFoundation.cfDictionaryGetValue(dict, key);
            if (MemorySegment.NULL.equals(val)) return defaultValue;
            try (Arena a = Arena.ofConfined()) {
                MemorySegment buf = a.allocate(ValueLayout.JAVA_INT);
                return CoreFoundation.cfNumberGetInt(val, buf)
                        ? buf.get(ValueLayout.JAVA_INT, 0) : defaultValue;
            }
        } catch (RuntimeException e) {
            return defaultValue;
        }
    }

    // ── Helper: create CFDictionary for thumbnail options ────────────────

    /**
     * Creates a CFDictionary with thumbnail options:
     * <ul>
     *   <li>{@code kCGImageSourceCreateThumbnailFromImageAlways = true}</li>
     *   <li>{@code kCGImageSourceCreateThumbnailWithTransform = true}</li>
     *   <li>{@code kCGImageSourceThumbnailMaxPixelSize = maxDim}</li>
     * </ul>
     * Caller must release the returned dictionary.
     */
    private static MemorySegment createThumbnailOptions(Arena arena, int maxDim) {
        MemorySegment cfMaxSize = CoreFoundation.cfNumberCreateInt(arena, maxDim);
        try {
            MemorySegment yes = CoreFoundation.kCFBooleanTrue();
            return CoreFoundation.cfDictionaryCreate(arena,
                    new MemorySegment[]{
                            CGImageSource.kCreateThumbnailFromImageAlways(),
                            CGImageSource.kCreateThumbnailWithTransform(),
                            CGImageSource.kThumbnailMaxPixelSize()},
                    new MemorySegment[]{yes, yes, cfMaxSize});
        } finally {
            CoreFoundation.cfRelease(cfMaxSize);
        }
    }

    // ── Shared helpers: operate on a CGImageSource ──────────────────────

    /**
     * Reads display-oriented dimensions from a CGImageSource (properties only, no pixel decode).
     * The caller owns the imgSrc and is responsible for releasing it.
     */
    private static int[] getSizeFromSource(MemorySegment imgSrc) throws java.io.IOException {
        MemorySegment props = MemorySegment.NULL;
        try {
            props = CGImageSource.copyPropertiesAtIndex(imgSrc, 0L, MemorySegment.NULL);
            if (MemorySegment.NULL.equals(props))
                throw new javax.imageio.IIOException("Failed to read image properties");

            int rawW = dictGetInt(props, CGImageSource.kPixelWidth(), -1);
            int rawH = dictGetInt(props, CGImageSource.kPixelHeight(), -1);
            if (rawW <= 0 || rawH <= 0)
                throw new javax.imageio.IIOException("Invalid image dimensions: " + rawW + "x" + rawH);

            int orientation = dictGetInt(props, CGImageSource.kOrientation(), 1);
            if (orientation >= 5 && orientation <= 8) {
                return new int[]{rawH, rawW};
            }
            return new int[]{rawW, rawH};
        } catch (java.io.IOException e) {
            throw e;
        } catch (Throwable t) {
            throw new javax.imageio.IIOException("Native image size query failed", t);
        } finally {
            CoreFoundation.cfRelease(props);
        }
    }

    /**
     * Decodes a full image from a CGImageSource with EXIF orientation applied.
     * The caller owns the imgSrc and is responsible for releasing it.
     */
    private static BufferedImage decodeFromSource(
            Arena arena, MemorySegment imgSrc, Dimension renderSize)
            throws java.io.IOException {
        MemorySegment props = MemorySegment.NULL;
        MemorySegment thumbOpts = MemorySegment.NULL;
        MemorySegment cgImage = MemorySegment.NULL;
        try {
            props = CGImageSource.copyPropertiesAtIndex(imgSrc, 0L, MemorySegment.NULL);
            if (MemorySegment.NULL.equals(props))
                throw new javax.imageio.IIOException("Failed to read image properties");

            int rawW = dictGetInt(props, CGImageSource.kPixelWidth(), -1);
            int rawH = dictGetInt(props, CGImageSource.kPixelHeight(), -1);
            if (rawW <= 0 || rawH <= 0)
                throw new javax.imageio.IIOException("Invalid image dimensions: " + rawW + "x" + rawH);

            long decodedPixels = renderSize != null
                    ? (long) renderSize.width * renderSize.height
                    : (long) rawW * rawH;
            if (decodedPixels > MAX_PIXELS)
                throw new javax.imageio.IIOException(
                        "Decoded image too large: " + decodedPixels
                                + " pixels exceeds limit of " + MAX_PIXELS);

            int maxDim = renderSize != null
                    ? Math.max(renderSize.width, renderSize.height)
                    : Math.max(rawW, rawH);
            if (renderSize != null) {
                // CGImageSource accepts only a maximum edge, not an exact
                // target shape. Bound its worst-case square thumbnail before
                // the exact render-size correction below.
                ImageReadParamSupport.validateIntermediateDimensions(
                        maxDim, maxDim);
            }
            thumbOpts = createThumbnailOptions(arena, maxDim);
            if (MemorySegment.NULL.equals(thumbOpts))
                throw new javax.imageio.IIOException("Failed to create thumbnail options");

            cgImage = CGImageSource.createThumbnailAtIndex(imgSrc, 0L, thumbOpts);
            if (MemorySegment.NULL.equals(cgImage))
                throw new javax.imageio.IIOException(
                        "CGImageSourceCreateThumbnailAtIndex returned NULL – decode failed");

            BufferedImage decoded =
                    AppleCoreGraphicsHelper.cgImageToBufferedImage(cgImage, arena);
            return ImageReadParamSupport.renderToSize(decoded, renderSize);
        } catch (java.io.IOException e) {
            throw e;
        } catch (Throwable t) {
            throw new javax.imageio.IIOException("Native image decode failed", t);
        } finally {
            CoreFoundation.cfRelease(cgImage);
            CoreFoundation.cfRelease(thumbOpts);
            CoreFoundation.cfRelease(props);
        }
    }

    // ── Header probe: can Apple decode this data? ─────────────────────

    /**
     * Probes a header chunk through {@code CGImageSourceCreateWithData} to check
     * whether Apple's ImageIO can identify (and therefore decode) the format.
     *
     * @param header first bytes of the image (4 KB is plenty for identification)
     * @param len    number of valid bytes in {@code header}
     * @return {@code true} if CGImageSource recognised the format
     */
    static boolean canDecode(byte[] header, int len) {
        if (!IS_MACOS) return false;

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment cfData = MemorySegment.NULL;
            MemorySegment imgSrc = MemorySegment.NULL;
            try {
                MemorySegment buf = arena.allocate(len);
                MemorySegment.copy(header, 0, buf, ValueLayout.JAVA_BYTE, 0, len);
                cfData = CoreFoundation.cfDataCreateNoCopy(
                        MemorySegment.NULL, buf, len, CoreFoundation.kCFAllocatorNull());
                if (MemorySegment.NULL.equals(cfData)) return false;

                imgSrc = CGImageSource.createWithData(cfData, MemorySegment.NULL);
                if (MemorySegment.NULL.equals(imgSrc)) return false;

                // status >= kCGImageStatusIncomplete means the format was identified
                return CGImageSource.getStatus(imgSrc) >= CGImageSource.STATUS_INCOMPLETE;
            } catch (Throwable t) {
                return false;
            } finally {
                CoreFoundation.cfRelease(imgSrc);
                CoreFoundation.cfRelease(cfData);
            }
        }
    }

    // ── CGImageSource constructors ──────────────────────────────────────

    /** An image source and the CF object backing it; both released by the caller. */
    private record Source(MemorySegment imgSrc, MemorySegment backing) {}

    private static Source sourceFromBytes(Arena arena, byte[] imageData, String unsupported)
            throws java.io.IOException {
        MemorySegment nativeBuf = arena.allocateFrom(ValueLayout.JAVA_BYTE, imageData);
        MemorySegment cfData = CoreFoundation.cfDataCreateNoCopy(
                MemorySegment.NULL, nativeBuf, imageData.length, CoreFoundation.kCFAllocatorNull());
        if (MemorySegment.NULL.equals(cfData))
            throw new javax.imageio.IIOException("CFDataCreateWithBytesNoCopy returned NULL");

        MemorySegment imgSrc = CGImageSource.createWithData(cfData, MemorySegment.NULL);
        if (MemorySegment.NULL.equals(imgSrc)) {
            CoreFoundation.cfRelease(cfData);
            throw new javax.imageio.IIOException(unsupported);
        }
        return new Source(imgSrc, cfData);
    }

    private static Source sourceFromPath(Arena arena, String path, String unsupported)
            throws java.io.IOException {
        MemorySegment cfUrl = CoreFoundation.cfUrlCreate(arena, path);
        if (MemorySegment.NULL.equals(cfUrl))
            throw new javax.imageio.IIOException("Failed to create CFURL for path");

        MemorySegment imgSrc = CGImageSource.createWithURL(cfUrl, MemorySegment.NULL);
        if (MemorySegment.NULL.equals(imgSrc)) {
            CoreFoundation.cfRelease(cfUrl);
            throw new javax.imageio.IIOException(unsupported + path);
        }
        return new Source(imgSrc, cfUrl);
    }

    private static void release(Source source) {
        if (source != null) {
            CoreFoundation.cfRelease(source.imgSrc());
            CoreFoundation.cfRelease(source.backing());
        }
    }

    // ── Lightweight size query ─────────────────────────────────────────

    /**
     * Returns display-oriented image dimensions without full pixel decode.
     *
     * @param imageData the raw image file bytes
     * @return display dimensions as {@code [width, height]}
     * @throws javax.imageio.IIOException if the native size query fails or OS is not macOS
     */
    static int[] getSize(byte[] imageData) throws java.io.IOException {
        if (!IS_MACOS)
            throw new javax.imageio.IIOException(unavailableMessage("Apple ImageIO is only available on macOS"));

        try (Arena arena = Arena.ofConfined()) {
            Source source = null;
            try {
                source = sourceFromBytes(arena, imageData, "Unsupported image format");
                return getSizeFromSource(source.imgSrc());
            } catch (java.io.IOException e) {
                throw e;
            } catch (Throwable t) {
                throw new javax.imageio.IIOException("Native image size query failed", t);
            } finally {
                release(source);
            }
        }
    }

    /**
     * Returns display-oriented image dimensions by reading directly from a file path.
     * Avoids loading the entire file into the Java heap.
     *
     * @param path absolute file path
     * @return display dimensions as {@code [width, height]}
     * @throws javax.imageio.IIOException if the native size query fails or OS is not macOS
     */
    static int[] getSizeFromPath(String path) throws java.io.IOException {
        if (!IS_MACOS)
            throw new javax.imageio.IIOException(unavailableMessage("Apple ImageIO is only available on macOS"));

        try (Arena arena = Arena.ofConfined()) {
            Source source = null;
            try {
                source = sourceFromPath(arena, path, "Unsupported image format: ");
                return getSizeFromSource(source.imgSrc());
            } catch (java.io.IOException e) {
                throw e;
            } catch (Throwable t) {
                throw new javax.imageio.IIOException("Native image size query failed for: " + path, t);
            } finally {
                release(source);
            }
        }
    }

    // ── Public decode entry point ───────────────────────────────────────

    /**
     * Decodes raw image bytes through Apple's CGImageSource.
     *
     * @param imageData the raw image file bytes
     * @return decoded image with orientation applied
     * @throws javax.imageio.IIOException if the native decode fails or OS is not macOS
     */
    static BufferedImage decode(byte[] imageData) throws java.io.IOException {
        return decode(imageData, null);
    }

    /**
     * Decodes bytes with an optional native thumbnail bound before producing
     * an exact Java render size.
     */
    static BufferedImage decode(byte[] imageData, Dimension renderSize)
            throws java.io.IOException {
        if (!IS_MACOS)
            throw new javax.imageio.IIOException(unavailableMessage("Apple ImageIO decoding is only available on macOS"));

        try (Arena arena = Arena.ofConfined()) {
            Source source = null;
            try {
                source = sourceFromBytes(arena, imageData, "Unsupported format");
                return decodeFromSource(arena, source.imgSrc(), renderSize);
            } catch (java.io.IOException e) {
                throw e;
            } catch (Throwable t) {
                throw new javax.imageio.IIOException("Native image decode failed", t);
            } finally {
                release(source);
            }
        }
    }

    /**
     * Decodes an image directly from a file path through Apple's CGImageSource.
     * Avoids loading the entire file into the Java heap.
     *
     * @param path absolute file path
     * @return decoded image with orientation applied
     * @throws javax.imageio.IIOException if the native decode fails or OS is not macOS
     */
    static BufferedImage decodeFromPath(String path) throws java.io.IOException {
        return decodeFromPath(path, null);
    }

    /**
     * Decodes a path with an optional native thumbnail bound before producing
     * an exact Java render size.
     */
    static BufferedImage decodeFromPath(String path, Dimension renderSize)
            throws java.io.IOException {
        if (!IS_MACOS)
            throw new javax.imageio.IIOException(unavailableMessage("Apple ImageIO decoding is only available on macOS"));

        try (Arena arena = Arena.ofConfined()) {
            Source source = null;
            try {
                source = sourceFromPath(arena, path, "Unsupported format: ");
                return decodeFromSource(arena, source.imgSrc(), renderSize);
            } catch (java.io.IOException e) {
                throw e;
            } catch (Throwable t) {
                throw new javax.imageio.IIOException("Native image decode failed for: " + path, t);
            } finally {
                release(source);
            }
        }
    }
}
