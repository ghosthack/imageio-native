package io.github.ghosthack.imageio.apple;

import java.awt.image.BufferedImage;
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

    /** kCFNumberIntType = 9 (C {@code int}, 32-bit signed). */
    static final int kCFNumberIntType = 9;

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
    private static final MethodHandle CGImageSourceCreateWithURL;
    private static final MethodHandle CGImageSourceGetStatus;
    private static final MethodHandle CGImageSourceCopyPropertiesAtIndex;
    private static final MethodHandle CGImageSourceCreateThumbnailAtIndex;

    // ── CFURL handles ───────────────────────────────────────────────────

    private static final MethodHandle CFStringCreateWithCString;
    private static final MethodHandle CFURLCreateWithFileSystemPath;

    /** kCFStringEncodingUTF8 */
    private static final int kCFStringEncodingUTF8 = 0x08000100;

    /** kCFURLPOSIXPathStyle */
    private static final int kCFURLPOSIXPathStyle = 0;

    // ── ImageIO property key symbols (CFStringRef constants) ────────────

    private static final MemorySegment kCGImagePropertyPixelWidth;
    private static final MemorySegment kCGImagePropertyPixelHeight;
    private static final MemorySegment kCGImagePropertyOrientation;
    private static final MemorySegment kCGImageSourceCreateThumbnailFromImageAlways;
    private static final MemorySegment kCGImageSourceCreateThumbnailWithTransform;
    private static final MemorySegment kCGImageSourceThumbnailMaxPixelSize;



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

            // ── CFURL helpers ───────────────────────────────────────────
            CFStringCreateWithCString = downcall("CFStringCreateWithCString",
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
            CFURLCreateWithFileSystemPath = downcall("CFURLCreateWithFileSystemPath",
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_BOOLEAN));

            // ── ImageIO ─────────────────────────────────────────────────
            CGImageSourceCreateWithData = downcall("CGImageSourceCreateWithData",
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            CGImageSourceCreateWithURL = downcall("CGImageSourceCreateWithURL",
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
            CFStringCreateWithCString = null;
            CFURLCreateWithFileSystemPath = null;
            CGImageSourceCreateWithData = null;
            CGImageSourceCreateWithURL = null;
            CGImageSourceGetStatus = null;
            CGImageSourceCopyPropertiesAtIndex = null;
            CGImageSourceCreateThumbnailAtIndex = null;
            kCGImagePropertyPixelWidth = null;
            kCGImagePropertyPixelHeight = null;
            kCGImagePropertyOrientation = null;
            kCGImageSourceCreateThumbnailFromImageAlways = null;
            kCGImageSourceCreateThumbnailWithTransform = null;
            kCGImageSourceThumbnailMaxPixelSize = null;

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

    // ── Helper: create CGImageSource from a file path ────────────────

    /**
     * Creates a CFString from a Java string (UTF-8 C-string).
     * Caller must release the returned CFString.
     */
    private static MemorySegment createCFString(Arena arena, String s) throws Throwable {
        byte[] utf8 = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        MemorySegment cstr = arena.allocate(utf8.length + 1L);
        MemorySegment.copy(utf8, 0, cstr, ValueLayout.JAVA_BYTE, 0, utf8.length);
        cstr.set(ValueLayout.JAVA_BYTE, utf8.length, (byte) 0); // null terminator
        return (MemorySegment) CFStringCreateWithCString.invokeExact(
                MemorySegment.NULL, cstr, kCFStringEncodingUTF8);
    }

    /**
     * Creates a CFURL from a POSIX file path string.
     * Caller must release the returned CFURL.
     */
    private static MemorySegment createFileURL(MemorySegment cfString) throws Throwable {
        return (MemorySegment) CFURLCreateWithFileSystemPath.invokeExact(
                MemorySegment.NULL, cfString, kCFURLPOSIXPathStyle, false);
    }

    // ── Shared helpers: operate on a CGImageSource ──────────────────────

    /**
     * Reads display-oriented dimensions from a CGImageSource (properties only, no pixel decode).
     * The caller owns the imgSrc and is responsible for releasing it.
     */
    private static int[] getSizeFromSource(MemorySegment imgSrc) throws java.io.IOException {
        MemorySegment props = MemorySegment.NULL;
        try {
            props = (MemorySegment) CGImageSourceCopyPropertiesAtIndex.invokeExact(
                    imgSrc, 0L, MemorySegment.NULL);
            if (MemorySegment.NULL.equals(props))
                throw new javax.imageio.IIOException("Failed to read image properties");

            int rawW = dictGetInt(props, kCGImagePropertyPixelWidth, -1);
            int rawH = dictGetInt(props, kCGImagePropertyPixelHeight, -1);
            if (rawW <= 0 || rawH <= 0)
                throw new javax.imageio.IIOException("Invalid image dimensions: " + rawW + "x" + rawH);

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
        }
    }

    /**
     * Decodes a full image from a CGImageSource with EXIF orientation applied.
     * The caller owns the imgSrc and is responsible for releasing it.
     */
    private static BufferedImage decodeFromSource(Arena arena, MemorySegment imgSrc)
            throws java.io.IOException {
        MemorySegment props = MemorySegment.NULL;
        MemorySegment thumbOpts = MemorySegment.NULL;
        MemorySegment cgImage = MemorySegment.NULL;
        try {
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

            int maxDim = Math.max(rawW, rawH);
            thumbOpts = createThumbnailOptions(arena, maxDim);
            if (MemorySegment.NULL.equals(thumbOpts))
                throw new javax.imageio.IIOException("Failed to create thumbnail options");

            cgImage = (MemorySegment) CGImageSourceCreateThumbnailAtIndex.invokeExact(
                    imgSrc, 0L, thumbOpts);
            if (MemorySegment.NULL.equals(cgImage))
                throw new javax.imageio.IIOException(
                        "CGImageSourceCreateThumbnailAtIndex returned NULL – decode failed");

            return AppleCoreGraphicsHelper.cgImageToBufferedImage(cgImage, arena);
        } catch (java.io.IOException e) {
            throw e;
        } catch (Throwable t) {
            throw new javax.imageio.IIOException("Native image decode failed", t);
        } finally {
            release(cgImage);
            release(thumbOpts);
            release(props);
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

                return getSizeFromSource(imgSrc);
            } catch (java.io.IOException e) {
                throw e;
            } catch (Throwable t) {
                throw new javax.imageio.IIOException("Native image size query failed", t);
            } finally {
                release(imgSrc);
                release(cfData);
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
            throw new javax.imageio.IIOException("Apple ImageIO is only available on macOS");

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment cfString = MemorySegment.NULL;
            MemorySegment cfUrl = MemorySegment.NULL;
            MemorySegment imgSrc = MemorySegment.NULL;
            try {
                cfString = createCFString(arena, path);
                if (MemorySegment.NULL.equals(cfString))
                    throw new javax.imageio.IIOException("Failed to create CFString for path");
                cfUrl = createFileURL(cfString);
                if (MemorySegment.NULL.equals(cfUrl))
                    throw new javax.imageio.IIOException("Failed to create CFURL for path");

                imgSrc = (MemorySegment) CGImageSourceCreateWithURL.invokeExact(
                        cfUrl, MemorySegment.NULL);
                if (MemorySegment.NULL.equals(imgSrc))
                    throw new javax.imageio.IIOException("Unsupported image format: " + path);

                return getSizeFromSource(imgSrc);
            } catch (java.io.IOException e) {
                throw e;
            } catch (Throwable t) {
                throw new javax.imageio.IIOException("Native image size query failed for: " + path, t);
            } finally {
                release(imgSrc);
                release(cfUrl);
                release(cfString);
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
        if (!IS_MACOS)
            throw new javax.imageio.IIOException("Apple ImageIO decoding is only available on macOS");

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment cfData = MemorySegment.NULL;
            MemorySegment imgSrc = MemorySegment.NULL;
            try {
                MemorySegment nativeBuf = arena.allocateFrom(ValueLayout.JAVA_BYTE, imageData);
                cfData = (MemorySegment) CFDataCreateWithBytesNoCopy.invokeExact(
                        MemorySegment.NULL, nativeBuf,
                        (long) imageData.length, kCFAllocatorNull);
                if (MemorySegment.NULL.equals(cfData))
                    throw new javax.imageio.IIOException("CFDataCreateWithBytesNoCopy returned NULL");

                imgSrc = (MemorySegment) CGImageSourceCreateWithData.invokeExact(
                        cfData, MemorySegment.NULL);
                if (MemorySegment.NULL.equals(imgSrc))
                    throw new javax.imageio.IIOException("Unsupported format");

                return decodeFromSource(arena, imgSrc);
            } catch (java.io.IOException e) {
                throw e;
            } catch (Throwable t) {
                throw new javax.imageio.IIOException("Native image decode failed", t);
            } finally {
                release(imgSrc);
                release(cfData);
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
        if (!IS_MACOS)
            throw new javax.imageio.IIOException("Apple ImageIO decoding is only available on macOS");

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment cfString = MemorySegment.NULL;
            MemorySegment cfUrl = MemorySegment.NULL;
            MemorySegment imgSrc = MemorySegment.NULL;
            try {
                cfString = createCFString(arena, path);
                if (MemorySegment.NULL.equals(cfString))
                    throw new javax.imageio.IIOException("Failed to create CFString for path");
                cfUrl = createFileURL(cfString);
                if (MemorySegment.NULL.equals(cfUrl))
                    throw new javax.imageio.IIOException("Failed to create CFURL for path");

                imgSrc = (MemorySegment) CGImageSourceCreateWithURL.invokeExact(
                        cfUrl, MemorySegment.NULL);
                if (MemorySegment.NULL.equals(imgSrc))
                    throw new javax.imageio.IIOException("Unsupported format: " + path);

                return decodeFromSource(arena, imgSrc);
            } catch (java.io.IOException e) {
                throw e;
            } catch (Throwable t) {
                throw new javax.imageio.IIOException("Native image decode failed for: " + path, t);
            } finally {
                release(imgSrc);
                release(cfUrl);
                release(cfString);
            }
        }
    }
}
