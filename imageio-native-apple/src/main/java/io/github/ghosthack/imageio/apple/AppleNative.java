package io.github.ghosthack.imageio.apple;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;

/**
 * Panama (FFM) bindings to macOS CoreFoundation, CoreGraphics, and ImageIO frameworks.
 * <p>
 * Provides the native bridge to decode images via Apple's CGImageSource API,
 * which supports HEIC, AVIF, WEBP, and many other formats through the Apple Media Engine.
 * <p>
 * EXIF orientation is applied automatically: {@link #decode} uses
 * {@code CGImageSourceCreateThumbnailAtIndex} with
 * {@code kCGImageSourceCreateThumbnailWithTransform = true}, and {@link #getSize}
 * reads the orientation property to return display-oriented dimensions.
 * <p>
 * Requires {@code --enable-native-access=ALL-UNNAMED} at runtime.
 * Only functional on macOS; all entry points return failure gracefully on other OSes.
 */
final class AppleNative {

    private AppleNative() {}

    // ── OS guard ────────────────────────────────────────────────────────

    static final boolean IS_MACOS = System.getProperty("os.name", "")
            .toLowerCase(java.util.Locale.ROOT).contains("mac");

    /**
     * Maximum number of pixels (width * height) we are willing to decode.
     * Prevents OOM on malicious/corrupt images declaring huge dimensions.
     * Default: 256 megapixels (e.g. 16384 x 16384).
     */
    static final long MAX_PIXELS = Long.getLong("imageio.native.maxPixels", 256L * 1024 * 1024);

    // ── Constants ───────────────────────────────────────────────────────

    /** kCGImageAlphaPremultipliedFirst = 2 */
    static final int kCGImageAlphaPremultipliedFirst = 2;

    /** kCGBitmapByteOrder32Little = 2 &lt;&lt; 12 = 8192 */
    static final int kCGBitmapByteOrder32Little = 2 << 12;

    /** BGRA premultiplied, little-endian — matches TYPE_INT_ARGB_PRE when read as LE ints. */
    static final int BITMAP_INFO = kCGImageAlphaPremultipliedFirst | kCGBitmapByteOrder32Little;

    /** kCFNumberIntType = 9 (C {@code int}, 32-bit signed). */
    static final int kCFNumberIntType = 9;

    // ── CGRect struct layout (4 × CGFloat = 4 × double on 64-bit) ─────

    static final StructLayout CGRECT = MemoryLayout.structLayout(
            ValueLayout.JAVA_DOUBLE.withName("x"),
            ValueLayout.JAVA_DOUBLE.withName("y"),
            ValueLayout.JAVA_DOUBLE.withName("width"),
            ValueLayout.JAVA_DOUBLE.withName("height")
    );

    // ── Framework loading and method handles ────────────────────────────
    // Load via System.load so dlopen resolves from the dyld shared cache
    // (framework files may not exist on disk on macOS 11+).
    // Guarded by IS_MACOS so this class can be loaded safely on Windows/Linux.

    private static final Linker LINKER = Linker.nativeLinker();

    private static final SymbolLookup LOOKUP;

    // ── CoreFoundation handles ──────────────────────────────────────────

    private static final MethodHandle CFDataCreateWithBytesNoCopy;
    private static final MethodHandle CFRelease;
    private static final MethodHandle CFDictionaryCreate;
    private static final MethodHandle CFDictionaryGetValue;
    private static final MethodHandle CFNumberCreate;
    private static final MethodHandle CFNumberGetValue;

    // ── CoreFoundation constant symbols ─────────────────────────────────

    private static final MemorySegment kCFAllocatorNull;
    private static final MemorySegment kCFBooleanTrue;
    private static final MemorySegment kCFTypeDictionaryKeyCallBacks;   // struct, not pointer-to-pointer
    private static final MemorySegment kCFTypeDictionaryValueCallBacks; // struct, not pointer-to-pointer

    // ── ImageIO handles ─────────────────────────────────────────────────

    private static final MethodHandle CGImageSourceCreateWithData;
    private static final MethodHandle CGImageSourceGetStatus;
    private static final MethodHandle CGImageSourceCopyPropertiesAtIndex;
    private static final MethodHandle CGImageSourceCreateThumbnailAtIndex;

    // ── ImageIO property key symbols (CFStringRef constants) ────────────

    private static final MemorySegment kCGImagePropertyPixelWidth;
    private static final MemorySegment kCGImagePropertyPixelHeight;
    private static final MemorySegment kCGImagePropertyOrientation;
    private static final MemorySegment kCGImageSourceCreateThumbnailFromImageAlways;
    private static final MemorySegment kCGImageSourceCreateThumbnailWithTransform;
    private static final MemorySegment kCGImageSourceThumbnailMaxPixelSize;

    // ── CoreGraphics handles ────────────────────────────────────────────

    private static final MethodHandle CGImageGetWidth;
    private static final MethodHandle CGImageGetHeight;
    private static final MethodHandle CGColorSpaceCreateDeviceRGB;
    private static final MethodHandle CGBitmapContextCreate;
    private static final MethodHandle CGContextDrawImage;

    static {
        if (IS_MACOS) {
            System.load("/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation");
            System.load("/System/Library/Frameworks/CoreGraphics.framework/CoreGraphics");
            System.load("/System/Library/Frameworks/ImageIO.framework/ImageIO");
            LOOKUP = SymbolLookup.loaderLookup();

            // ── CoreFoundation ──────────────────────────────────────────
            CFDataCreateWithBytesNoCopy = downcall("CFDataCreateWithBytesNoCopy",
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));
            CFRelease = downcall("CFRelease",
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
            CFDictionaryCreate = downcall("CFDictionaryCreate",
                    FunctionDescriptor.of(ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            CFDictionaryGetValue = downcall("CFDictionaryGetValue",
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            CFNumberCreate = downcall("CFNumberCreate",
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
            CFNumberGetValue = downcall("CFNumberGetValue",
                    FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN, ValueLayout.ADDRESS,
                            ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

            kCFAllocatorNull          = loadConstPtr("kCFAllocatorNull");
            kCFBooleanTrue            = loadConstPtr("kCFBooleanTrue");
            // These are struct values (not pointer-to-pointer) — use the symbol address directly
            kCFTypeDictionaryKeyCallBacks   = loadSymbolAddr("kCFTypeDictionaryKeyCallBacks");
            kCFTypeDictionaryValueCallBacks = loadSymbolAddr("kCFTypeDictionaryValueCallBacks");

            // ── ImageIO ─────────────────────────────────────────────────
            CGImageSourceCreateWithData = downcall("CGImageSourceCreateWithData",
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            CGImageSourceGetStatus = downcall("CGImageSourceGetStatus",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
            CGImageSourceCopyPropertiesAtIndex = downcall("CGImageSourceCopyPropertiesAtIndex",
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));
            CGImageSourceCreateThumbnailAtIndex = downcall("CGImageSourceCreateThumbnailAtIndex",
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));

            kCGImagePropertyPixelWidth  = loadConstPtr("kCGImagePropertyPixelWidth");
            kCGImagePropertyPixelHeight = loadConstPtr("kCGImagePropertyPixelHeight");
            kCGImagePropertyOrientation = loadConstPtr("kCGImagePropertyOrientation");
            kCGImageSourceCreateThumbnailFromImageAlways = loadConstPtr("kCGImageSourceCreateThumbnailFromImageAlways");
            kCGImageSourceCreateThumbnailWithTransform   = loadConstPtr("kCGImageSourceCreateThumbnailWithTransform");
            kCGImageSourceThumbnailMaxPixelSize          = loadConstPtr("kCGImageSourceThumbnailMaxPixelSize");

            // ── CoreGraphics ────────────────────────────────────────────
            CGImageGetWidth = downcall("CGImageGetWidth",
                    FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));
            CGImageGetHeight = downcall("CGImageGetHeight",
                    FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));
            CGColorSpaceCreateDeviceRGB = downcall("CGColorSpaceCreateDeviceRGB",
                    FunctionDescriptor.of(ValueLayout.ADDRESS));
            CGBitmapContextCreate = downcall("CGBitmapContextCreate",
                    FunctionDescriptor.of(ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
            CGContextDrawImage = downcall("CGContextDrawImage",
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, CGRECT, ValueLayout.ADDRESS));
        } else {
            LOOKUP = null;
            CFDataCreateWithBytesNoCopy = null;
            CFRelease = null;
            CFDictionaryCreate = null;
            CFDictionaryGetValue = null;
            CFNumberCreate = null;
            CFNumberGetValue = null;
            kCFAllocatorNull = null;
            kCFBooleanTrue = null;
            kCFTypeDictionaryKeyCallBacks = null;
            kCFTypeDictionaryValueCallBacks = null;
            CGImageSourceCreateWithData = null;
            CGImageSourceGetStatus = null;
            CGImageSourceCopyPropertiesAtIndex = null;
            CGImageSourceCreateThumbnailAtIndex = null;
            kCGImagePropertyPixelWidth = null;
            kCGImagePropertyPixelHeight = null;
            kCGImagePropertyOrientation = null;
            kCGImageSourceCreateThumbnailFromImageAlways = null;
            kCGImageSourceCreateThumbnailWithTransform = null;
            kCGImageSourceThumbnailMaxPixelSize = null;
            CGImageGetWidth = null;
            CGImageGetHeight = null;
            CGColorSpaceCreateDeviceRGB = null;
            CGBitmapContextCreate = null;
            CGContextDrawImage = null;
        }
    }

    static final int kCGImageStatusUnknownType = -3;
    static final int kCGImageStatusIncomplete  = -1;

    // ── Helpers: symbol loading ─────────────────────────────────────────

    private static MethodHandle downcall(String name, FunctionDescriptor fd) {
        MemorySegment addr = LOOKUP.find(name)
                .orElseThrow(() -> new UnsatisfiedLinkError("Symbol not found: " + name));
        return LINKER.downcallHandle(addr, fd);
    }

    /** Dereferences a global constant pointer (e.g. {@code extern const CFStringRef kFoo}). */
    private static MemorySegment loadConstPtr(String name) {
        MemorySegment sym = LOOKUP.find(name)
                .orElseThrow(() -> new UnsatisfiedLinkError("Symbol not found: " + name));
        return sym.reinterpret(ValueLayout.ADDRESS.byteSize()).get(ValueLayout.ADDRESS, 0);
    }

    /** Returns the raw symbol address (for struct-valued globals like callback structs). */
    private static MemorySegment loadSymbolAddr(String name) {
        return LOOKUP.find(name)
                .orElseThrow(() -> new UnsatisfiedLinkError("Symbol not found: " + name));
    }

    // ── Helper: release a non-NULL native reference ─────────────────────

    static void release(MemorySegment ref) {
        if (ref != null && !MemorySegment.NULL.equals(ref)) {
            try {
                CFRelease.invokeExact(ref);
            } catch (Throwable ignored) { /* invokeExact signature; native release cannot throw */ }
        }
    }

    // ── Helper: read an int property from a CFDictionary ────────────────

    /**
     * Reads an integer value from a CFDictionary by key.
     * Returns {@code defaultValue} if the key is absent or not a CFNumber.
     */
    private static int dictGetInt(MemorySegment dict, MemorySegment key, int defaultValue) {
        try {
            MemorySegment val = (MemorySegment) CFDictionaryGetValue.invokeExact(dict, key);
            if (MemorySegment.NULL.equals(val)) return defaultValue;
            // val is a CFNumber — extract as kCFNumberIntType (9)
            try (Arena a = Arena.ofConfined()) {
                MemorySegment buf = a.allocate(ValueLayout.JAVA_INT);
                boolean ok = (boolean) CFNumberGetValue.invokeExact(val, kCFNumberIntType, buf);
                return ok ? buf.get(ValueLayout.JAVA_INT, 0) : defaultValue;
            }
        } catch (Throwable t) {
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
    private static MemorySegment createThumbnailOptions(Arena arena, int maxDim) throws Throwable {
        // Create CFNumber for max pixel size
        MemorySegment sizePtr = arena.allocate(ValueLayout.JAVA_INT);
        sizePtr.set(ValueLayout.JAVA_INT, 0, maxDim);
        MemorySegment cfMaxSize = (MemorySegment) CFNumberCreate.invokeExact(
                MemorySegment.NULL, kCFNumberIntType, sizePtr);

        try {
            // Build key and value arrays (3 entries)
            MemorySegment keys = arena.allocate(ValueLayout.ADDRESS, 3);
            MemorySegment values = arena.allocate(ValueLayout.ADDRESS, 3);

            keys.setAtIndex(ValueLayout.ADDRESS, 0, kCGImageSourceCreateThumbnailFromImageAlways);
            keys.setAtIndex(ValueLayout.ADDRESS, 1, kCGImageSourceCreateThumbnailWithTransform);
            keys.setAtIndex(ValueLayout.ADDRESS, 2, kCGImageSourceThumbnailMaxPixelSize);

            values.setAtIndex(ValueLayout.ADDRESS, 0, kCFBooleanTrue);
            values.setAtIndex(ValueLayout.ADDRESS, 1, kCFBooleanTrue);
            values.setAtIndex(ValueLayout.ADDRESS, 2, cfMaxSize);

            return (MemorySegment) CFDictionaryCreate.invokeExact(
                    MemorySegment.NULL, keys, values, 3L,
                    kCFTypeDictionaryKeyCallBacks, kCFTypeDictionaryValueCallBacks);
        } finally {
            release(cfMaxSize);
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
                cfData = (MemorySegment) CFDataCreateWithBytesNoCopy.invokeExact(
                        MemorySegment.NULL, buf, (long) len, kCFAllocatorNull);
                if (MemorySegment.NULL.equals(cfData)) return false;

                imgSrc = (MemorySegment) CGImageSourceCreateWithData.invokeExact(
                        cfData, MemorySegment.NULL);
                if (MemorySegment.NULL.equals(imgSrc)) return false;

                int status = (int) CGImageSourceGetStatus.invokeExact(imgSrc);
                // status >= kCGImageStatusIncomplete means the format was identified
                return status >= kCGImageStatusIncomplete;
            } catch (Throwable t) {
                return false;
            } finally {
                release(imgSrc);
                release(cfData);
            }
        }
    }

    // ── Lightweight size query ─────────────────────────────────────────

    /**
     * Returns display-oriented image dimensions without full pixel decode.
     * <p>
     * Reads pixel width, height, and EXIF orientation from
     * {@code CGImageSourceCopyPropertiesAtIndex} — no CGImage is created and
     * no pixels are decoded.  For orientations 5–8 (90°/270° rotations),
     * width and height are swapped to reflect the display orientation.
     *
     * @param imageData the raw image file bytes
     * @return display dimensions as {@code [width, height]}
     * @throws javax.imageio.IIOException if the native size query fails or OS is not macOS
     */
    static int[] getSize(byte[] imageData) throws java.io.IOException {
        if (!IS_MACOS)
            throw new javax.imageio.IIOException("Apple ImageIO is only available on macOS");

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment cfData = MemorySegment.NULL;
            MemorySegment imgSrc = MemorySegment.NULL;
            MemorySegment props = MemorySegment.NULL;
            try {
                MemorySegment nativeBuf = arena.allocateFrom(ValueLayout.JAVA_BYTE, imageData);
                cfData = (MemorySegment) CFDataCreateWithBytesNoCopy.invokeExact(
                        MemorySegment.NULL, nativeBuf, (long) imageData.length, kCFAllocatorNull);
                if (MemorySegment.NULL.equals(cfData))
                    throw new javax.imageio.IIOException("CFDataCreateWithBytesNoCopy returned NULL");

                imgSrc = (MemorySegment) CGImageSourceCreateWithData.invokeExact(
                        cfData, MemorySegment.NULL);
                if (MemorySegment.NULL.equals(imgSrc))
                    throw new javax.imageio.IIOException("Unsupported image format");

                // Read properties (metadata only — no pixel decode)
                props = (MemorySegment) CGImageSourceCopyPropertiesAtIndex.invokeExact(
                        imgSrc, 0L, MemorySegment.NULL);
                if (MemorySegment.NULL.equals(props))
                    throw new javax.imageio.IIOException("Failed to read image properties");

                int rawW = dictGetInt(props, kCGImagePropertyPixelWidth, -1);
                int rawH = dictGetInt(props, kCGImagePropertyPixelHeight, -1);
                if (rawW <= 0 || rawH <= 0)
                    throw new javax.imageio.IIOException("Invalid image dimensions: " + rawW + "x" + rawH);

                // EXIF orientations 5–8 involve a 90° or 270° rotation → swap width/height
                int orientation = dictGetInt(props, kCGImagePropertyOrientation, 1);
                if (orientation >= 5 && orientation <= 8) {
                    return new int[]{rawH, rawW};
                }
                return new int[]{rawW, rawH};
            } catch (java.io.IOException e) {
                throw e;
            } catch (Throwable t) {
                throw new javax.imageio.IIOException("Native image size query failed", t);
            } finally {
                release(props);
                release(imgSrc);
                release(cfData);
            }
        }
    }

    // ── Public decode entry point ───────────────────────────────────────

    /**
     * Decodes raw image bytes (HEIC, AVIF, WEBP, etc.) through Apple's CGImageSource
     * and returns a {@link BufferedImage} of type {@code TYPE_INT_ARGB_PRE}.
     * <p>
     * EXIF orientation is applied automatically via
     * {@code CGImageSourceCreateThumbnailAtIndex} with
     * {@code kCGImageSourceCreateThumbnailWithTransform = true}.  The returned
     * image always has display-oriented dimensions.
     *
     * @param imageData the raw image file bytes
     * @return decoded image with orientation applied
     * @throws javax.imageio.IIOException if the native decode fails or OS is not macOS
     */
    static BufferedImage decode(byte[] imageData) throws java.io.IOException {
        if (!IS_MACOS)
            throw new javax.imageio.IIOException("Apple ImageIO decoding is only available on macOS");

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment cfData = MemorySegment.NULL;
            MemorySegment imgSrc = MemorySegment.NULL;
            MemorySegment props = MemorySegment.NULL;
            MemorySegment thumbOpts = MemorySegment.NULL;
            MemorySegment cgImage = MemorySegment.NULL;
            MemorySegment colorSpace = MemorySegment.NULL;
            MemorySegment ctx = MemorySegment.NULL;
            try {
                // 1. Copy bytes into native memory and wrap as CFData (no-copy: arena owns the buffer)
                MemorySegment nativeBuf = arena.allocateFrom(ValueLayout.JAVA_BYTE, imageData);
                cfData = (MemorySegment) CFDataCreateWithBytesNoCopy.invokeExact(
                        MemorySegment.NULL,       // default allocator
                        nativeBuf,
                        (long) imageData.length,
                        kCFAllocatorNull);        // arena manages the buffer lifetime
                if (MemorySegment.NULL.equals(cfData))
                    throw new javax.imageio.IIOException("CFDataCreateWithBytesNoCopy returned NULL");

                // 2. Create CGImageSource
                imgSrc = (MemorySegment) CGImageSourceCreateWithData.invokeExact(
                        cfData, MemorySegment.NULL);
                if (MemorySegment.NULL.equals(imgSrc))
                    throw new javax.imageio.IIOException("CGImageSourceCreateWithData returned NULL – unsupported format");

                // 3. Read raw dimensions from properties for MAX_PIXELS check
                props = (MemorySegment) CGImageSourceCopyPropertiesAtIndex.invokeExact(
                        imgSrc, 0L, MemorySegment.NULL);
                if (MemorySegment.NULL.equals(props))
                    throw new javax.imageio.IIOException("Failed to read image properties");

                int rawW = dictGetInt(props, kCGImagePropertyPixelWidth, -1);
                int rawH = dictGetInt(props, kCGImagePropertyPixelHeight, -1);
                if (rawW <= 0 || rawH <= 0)
                    throw new javax.imageio.IIOException("Invalid image dimensions: " + rawW + "x" + rawH);

                long totalPixels = (long) rawW * rawH;
                if (totalPixels > MAX_PIXELS)
                    throw new javax.imageio.IIOException(
                            "Image too large: " + rawW + "x" + rawH + " (" + totalPixels
                                    + " pixels exceeds limit of " + MAX_PIXELS + ")");

                // 4. Create thumbnail with orientation transform applied
                //    kCGImageSourceCreateThumbnailFromImageAlways = true: always create from full image
                //    kCGImageSourceCreateThumbnailWithTransform = true: apply EXIF orientation
                //    kCGImageSourceThumbnailMaxPixelSize = max(rawW, rawH): full resolution
                int maxDim = Math.max(rawW, rawH);
                thumbOpts = createThumbnailOptions(arena, maxDim);
                if (MemorySegment.NULL.equals(thumbOpts))
                    throw new javax.imageio.IIOException("Failed to create thumbnail options");

                cgImage = (MemorySegment) CGImageSourceCreateThumbnailAtIndex.invokeExact(
                        imgSrc, 0L, thumbOpts);
                if (MemorySegment.NULL.equals(cgImage))
                    throw new javax.imageio.IIOException("CGImageSourceCreateThumbnailAtIndex returned NULL – decode failed");

                // 5. Display dimensions (orientation already applied by the thumbnail API)
                long w = (long) CGImageGetWidth.invokeExact(cgImage);
                long h = (long) CGImageGetHeight.invokeExact(cgImage);
                if (w <= 0 || h <= 0)
                    throw new javax.imageio.IIOException("Invalid decoded dimensions: " + w + "x" + h);

                // 6. Device-RGB colour space
                colorSpace = (MemorySegment) CGColorSpaceCreateDeviceRGB.invokeExact();

                // 7. Allocate pixel buffer and create bitmap context
                long bytesPerRow = w * 4;
                MemorySegment pixelData = arena.allocate(bytesPerRow * h, 16);
                ctx = (MemorySegment) CGBitmapContextCreate.invokeExact(
                        pixelData, w, h, 8L, bytesPerRow, colorSpace, BITMAP_INFO);
                if (MemorySegment.NULL.equals(ctx))
                    throw new javax.imageio.IIOException("CGBitmapContextCreate returned NULL");

                // 8. Draw the orientation-corrected image into the bitmap context
                MemorySegment rect = arena.allocate(CGRECT);
                rect.set(ValueLayout.JAVA_DOUBLE, 0, 0.0);            // origin.x
                rect.set(ValueLayout.JAVA_DOUBLE, 8, 0.0);            // origin.y
                rect.set(ValueLayout.JAVA_DOUBLE, 16, (double) w);    // size.width
                rect.set(ValueLayout.JAVA_DOUBLE, 24, (double) h);    // size.height
                CGContextDrawImage.invokeExact(ctx, rect, cgImage);

                // 9. Copy pixels into a BufferedImage (TYPE_INT_ARGB_PRE)
                //    Memory layout: BGRA (little-endian) → LE int reads as 0xAARRGGBB → matches ARGB_PRE.
                int iw = (int) w;
                int ih = (int) h;
                BufferedImage result = new BufferedImage(iw, ih, BufferedImage.TYPE_INT_ARGB_PRE);
                int[] dest = ((DataBufferInt) result.getRaster().getDataBuffer()).getData();
                MemorySegment.copy(pixelData, ValueLayout.JAVA_INT, 0, dest, 0, dest.length);

                return result;
            } catch (java.io.IOException e) {
                throw e;
            } catch (Throwable t) {
                throw new javax.imageio.IIOException("Native image decode failed", t);
            } finally {
                release(ctx);
                release(colorSpace);
                release(cgImage);
                release(thumbOpts);
                release(props);
                release(imgSrc);
                release(cfData);
            }
        }
    }
}
