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

    // CFDataRef CFDataCreate(CFAllocatorRef allocator, const UInt8 *bytes, CFIndex length)
    private static final MethodHandle CFDataCreate;

    // CFDataRef CFDataCreateWithBytesNoCopy(CFAllocatorRef allocator, const UInt8 *bytes,
    //     CFIndex length, CFAllocatorRef bytesDeallocator)
    private static final MethodHandle CFDataCreateWithBytesNoCopy;

    // kCFAllocatorNull — passed as bytesDeallocator to prevent CFData from freeing the buffer
    private static final MemorySegment kCFAllocatorNull;

    // void CFRelease(CFTypeRef cf)
    private static final MethodHandle CFRelease;

    // CGImageSourceRef CGImageSourceCreateWithData(CFDataRef data, CFDictionaryRef options)
    private static final MethodHandle CGImageSourceCreateWithData;

    // CGImageRef CGImageSourceCreateImageAtIndex(CGImageSourceRef src, size_t index, CFDictionaryRef options)
    private static final MethodHandle CGImageSourceCreateImageAtIndex;

    // size_t CGImageGetWidth(CGImageRef image)
    private static final MethodHandle CGImageGetWidth;

    // size_t CGImageGetHeight(CGImageRef image)
    private static final MethodHandle CGImageGetHeight;

    // CGColorSpaceRef CGColorSpaceCreateDeviceRGB(void)
    private static final MethodHandle CGColorSpaceCreateDeviceRGB;

    // CGContextRef CGBitmapContextCreate(void *data, size_t w, size_t h,
    //     size_t bitsPerComponent, size_t bytesPerRow, CGColorSpaceRef space, uint32_t bitmapInfo)
    private static final MethodHandle CGBitmapContextCreate;

    // void CGContextDrawImage(CGContextRef c, CGRect rect, CGImageRef image)
    private static final MethodHandle CGContextDrawImage;

    // CGImageSourceStatus CGImageSourceGetStatus(CGImageSourceRef src)
    // Returns: 0 = complete, -1 = incomplete (format identified), -3 = unknown type
    private static final MethodHandle CGImageSourceGetStatus;

    static {
        if (IS_MACOS) {
            System.load("/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation");
            System.load("/System/Library/Frameworks/CoreGraphics.framework/CoreGraphics");
            System.load("/System/Library/Frameworks/ImageIO.framework/ImageIO");
            LOOKUP = SymbolLookup.loaderLookup();

            CFDataCreate = downcall("CFDataCreate",
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
            CFDataCreateWithBytesNoCopy = downcall("CFDataCreateWithBytesNoCopy",
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));
            MemorySegment cfAllocatorNullSym = LOOKUP.find("kCFAllocatorNull")
                    .orElseThrow(() -> new UnsatisfiedLinkError("Symbol not found: kCFAllocatorNull"));
            kCFAllocatorNull = cfAllocatorNullSym
                    .reinterpret(ValueLayout.ADDRESS.byteSize())
                    .get(ValueLayout.ADDRESS, 0);
            CFRelease = downcall("CFRelease",
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
            CGImageSourceCreateWithData = downcall("CGImageSourceCreateWithData",
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            CGImageSourceCreateImageAtIndex = downcall("CGImageSourceCreateImageAtIndex",
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));
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
            CGImageSourceGetStatus = downcall("CGImageSourceGetStatus",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
        } else {
            LOOKUP = null;
            CFDataCreate = null;
            CFDataCreateWithBytesNoCopy = null;
            kCFAllocatorNull = null;
            CFRelease = null;
            CGImageSourceCreateWithData = null;
            CGImageSourceCreateImageAtIndex = null;
            CGImageGetWidth = null;
            CGImageGetHeight = null;
            CGColorSpaceCreateDeviceRGB = null;
            CGBitmapContextCreate = null;
            CGContextDrawImage = null;
            CGImageSourceGetStatus = null;
        }
    }

    static final int kCGImageStatusUnknownType = -3;
    static final int kCGImageStatusIncomplete  = -1;

    // ── Helper: create downcall handle ──────────────────────────────────

    private static MethodHandle downcall(String name, FunctionDescriptor fd) {
        MemorySegment addr = LOOKUP.find(name)
                .orElseThrow(() -> new UnsatisfiedLinkError("Symbol not found: " + name));
        return LINKER.downcallHandle(addr, fd);
    }

    // ── Helper: release a non-NULL native reference ─────────────────────

    static void release(MemorySegment ref) {
        if (ref != null && !MemorySegment.NULL.equals(ref)) {
            try {
                CFRelease.invokeExact(ref);
            } catch (Throwable ignored) { /* invokeExact signature; native release cannot throw */ }
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
     * Returns image dimensions without full pixel decode.
     * <p>
     * Creates a CGImage to read width/height metadata but does not allocate
     * a bitmap context or copy pixels — significantly lighter than {@link #decode}.
     *
     * @param imageData the raw image file bytes
     * @return dimensions as {@code [width, height]}
     * @throws javax.imageio.IIOException if the native size query fails or OS is not macOS
     */
    static int[] getSize(byte[] imageData) throws java.io.IOException {
        if (!IS_MACOS)
            throw new javax.imageio.IIOException("Apple ImageIO is only available on macOS");

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment cfData = MemorySegment.NULL;
            MemorySegment imgSrc = MemorySegment.NULL;
            MemorySegment cgImage = MemorySegment.NULL;
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

                cgImage = (MemorySegment) CGImageSourceCreateImageAtIndex.invokeExact(
                        imgSrc, 0L, MemorySegment.NULL);
                if (MemorySegment.NULL.equals(cgImage))
                    throw new javax.imageio.IIOException("Failed to read image metadata");

                int w = (int) (long) CGImageGetWidth.invokeExact(cgImage);
                int h = (int) (long) CGImageGetHeight.invokeExact(cgImage);
                if (w <= 0 || h <= 0)
                    throw new javax.imageio.IIOException("Invalid image dimensions: " + w + "x" + h);

                return new int[]{w, h};
            } catch (java.io.IOException e) {
                throw e;
            } catch (Throwable t) {
                throw new javax.imageio.IIOException("Native image size query failed", t);
            } finally {
                release(cgImage);
                release(imgSrc);
                release(cfData);
            }
        }
    }

    // ── Public decode entry point ───────────────────────────────────────

    /**
     * Decodes raw image bytes (HEIC, AVIF, WEBP, etc.) through Apple's CGImageSource
     * and returns a {@link BufferedImage} of type {@code TYPE_INT_ARGB_PRE}.
     *
     * @param imageData the raw image file bytes
     * @return decoded image
     * @throws javax.imageio.IIOException if the native decode fails or OS is not macOS
     */
    static BufferedImage decode(byte[] imageData) throws java.io.IOException {
        if (!IS_MACOS)
            throw new javax.imageio.IIOException("Apple ImageIO decoding is only available on macOS");

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment cfData = MemorySegment.NULL;
            MemorySegment imgSrc = MemorySegment.NULL;
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

                // 3. Decode image at index 0
                cgImage = (MemorySegment) CGImageSourceCreateImageAtIndex.invokeExact(
                        imgSrc, 0L, MemorySegment.NULL);
                if (MemorySegment.NULL.equals(cgImage))
                    throw new javax.imageio.IIOException("CGImageSourceCreateImageAtIndex returned NULL – decode failed");

                // 4. Dimensions
                long w = (long) CGImageGetWidth.invokeExact(cgImage);
                long h = (long) CGImageGetHeight.invokeExact(cgImage);
                if (w <= 0 || h <= 0)
                    throw new javax.imageio.IIOException("Invalid image dimensions: " + w + "x" + h);
                long totalPixels = w * h;
                if (totalPixels > MAX_PIXELS)
                    throw new javax.imageio.IIOException(
                            "Image too large: " + w + "x" + h + " (" + totalPixels
                                    + " pixels exceeds limit of " + MAX_PIXELS + ")");

                // 5. Device-RGB colour space
                colorSpace = (MemorySegment) CGColorSpaceCreateDeviceRGB.invokeExact();

                // 6. Allocate pixel buffer and create bitmap context
                long bytesPerRow = w * 4;
                MemorySegment pixelData = arena.allocate(bytesPerRow * h, 16);
                ctx = (MemorySegment) CGBitmapContextCreate.invokeExact(
                        pixelData, w, h, 8L, bytesPerRow, colorSpace, BITMAP_INFO);
                if (MemorySegment.NULL.equals(ctx))
                    throw new javax.imageio.IIOException("CGBitmapContextCreate returned NULL");

                // 7. Draw the decoded image into the bitmap context
                MemorySegment rect = arena.allocate(CGRECT);
                rect.set(ValueLayout.JAVA_DOUBLE, 0, 0.0);            // origin.x
                rect.set(ValueLayout.JAVA_DOUBLE, 8, 0.0);            // origin.y
                rect.set(ValueLayout.JAVA_DOUBLE, 16, (double) w);    // size.width
                rect.set(ValueLayout.JAVA_DOUBLE, 24, (double) h);    // size.height
                CGContextDrawImage.invokeExact(ctx, rect, cgImage);

                // 8. Copy pixels into a BufferedImage (TYPE_INT_ARGB_PRE)
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
                release(imgSrc);
                release(cfData);
            }
        }
    }
}
