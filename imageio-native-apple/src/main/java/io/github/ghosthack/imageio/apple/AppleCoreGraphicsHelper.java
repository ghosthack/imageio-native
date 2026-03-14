package io.github.ghosthack.imageio.apple;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;

/**
 * Public helper that converts a CGImageRef to a {@link BufferedImage}.
 * <p>
 * This class loads its own CoreGraphics downcall handles from the frameworks
 * that are already loaded by {@link AppleNative}'s static initialiser.
 * It is intended to be used by the video-apple module which lives in a
 * different package and therefore cannot access package-private members.
 * <p>
 * Requires macOS and {@code --enable-native-access=ALL-UNNAMED}.
 */
public final class AppleCoreGraphicsHelper {

    private AppleCoreGraphicsHelper() {}

    // ── Constants ───────────────────────────────────────────────────────

    /** kCGImageAlphaPremultipliedFirst = 2 */
    private static final int kCGImageAlphaPremultipliedFirst = 2;

    /** kCGBitmapByteOrder32Little = 2 << 12 = 8192 */
    private static final int kCGBitmapByteOrder32Little = 2 << 12;

    /** BGRA premultiplied, little-endian — matches TYPE_INT_ARGB_PRE when read as LE ints. */
    private static final int BITMAP_INFO = kCGImageAlphaPremultipliedFirst | kCGBitmapByteOrder32Little;

    /** CGRect struct layout (4 × double on 64-bit). */
    private static final StructLayout CGRECT = MemoryLayout.structLayout(
            ValueLayout.JAVA_DOUBLE.withName("x"),
            ValueLayout.JAVA_DOUBLE.withName("y"),
            ValueLayout.JAVA_DOUBLE.withName("width"),
            ValueLayout.JAVA_DOUBLE.withName("height")
    );

    // ── Downcall handles ────────────────────────────────────────────────

    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup LOOKUP;

    private static final MethodHandle CGImageGetWidth;
    private static final MethodHandle CGImageGetHeight;
    private static final MethodHandle CGColorSpaceCreateDeviceRGB;
    private static final MethodHandle CGBitmapContextCreate;
    private static final MethodHandle CGContextDrawImage;
    private static final MethodHandle CFRelease;

    static {
        // AppleNative's static init has already loaded CoreFoundation and CoreGraphics
        // via System.load, so the symbols are available in the loader lookup.
        LOOKUP = SymbolLookup.loaderLookup();

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
        CFRelease = downcall("CFRelease",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
    }

    private static MethodHandle downcall(String name, FunctionDescriptor fd) {
        MemorySegment addr = LOOKUP.find(name)
                .orElseThrow(() -> new UnsatisfiedLinkError("Symbol not found: " + name));
        return LINKER.downcallHandle(addr, fd);
    }

    private static void release(MemorySegment ref) {
        if (ref != null && !MemorySegment.NULL.equals(ref)) {
            try {
                CFRelease.invokeExact(ref);
            } catch (Throwable ignored) {}
        }
    }

    // ── Public API ──────────────────────────────────────────────────────

    /**
     * Converts a {@code CGImageRef} to a {@link BufferedImage} of type
     * {@code TYPE_INT_ARGB_PRE}.
     *
     * @param cgImage a valid, non-NULL CGImageRef
     * @param arena   arena for temporary native allocations (must outlive this call)
     * @return the decoded BufferedImage
     * @throws IOException if the conversion fails
     */
    public static BufferedImage cgImageToBufferedImage(MemorySegment cgImage, Arena arena)
            throws java.io.IOException {
        MemorySegment colorSpace = MemorySegment.NULL;
        MemorySegment ctx = MemorySegment.NULL;
        try {
            long w = (long) CGImageGetWidth.invokeExact(cgImage);
            long h = (long) CGImageGetHeight.invokeExact(cgImage);
            if (w <= 0 || h <= 0) {
                throw new javax.imageio.IIOException("Invalid CGImage dimensions: " + w + "x" + h);
            }

            colorSpace = (MemorySegment) CGColorSpaceCreateDeviceRGB.invokeExact();

            long bytesPerRow = w * 4;
            MemorySegment pixelData = arena.allocate(bytesPerRow * h, 16);
            ctx = (MemorySegment) CGBitmapContextCreate.invokeExact(
                    pixelData, w, h, 8L, bytesPerRow, colorSpace, BITMAP_INFO);
            if (MemorySegment.NULL.equals(ctx)) {
                throw new javax.imageio.IIOException("CGBitmapContextCreate returned NULL");
            }

            // Draw the CGImage into the bitmap context
            MemorySegment rect = arena.allocate(CGRECT);
            rect.set(ValueLayout.JAVA_DOUBLE, 0, 0.0);            // origin.x
            rect.set(ValueLayout.JAVA_DOUBLE, 8, 0.0);            // origin.y
            rect.set(ValueLayout.JAVA_DOUBLE, 16, (double) w);    // size.width
            rect.set(ValueLayout.JAVA_DOUBLE, 24, (double) h);    // size.height
            CGContextDrawImage.invokeExact(ctx, rect, cgImage);

            // Copy pixels into a BufferedImage
            int iw = (int) w;
            int ih = (int) h;
            BufferedImage result = new BufferedImage(iw, ih, BufferedImage.TYPE_INT_ARGB_PRE);
            int[] dest = ((DataBufferInt) result.getRaster().getDataBuffer()).getData();
            MemorySegment.copy(pixelData, ValueLayout.JAVA_INT, 0, dest, 0, dest.length);

            return result;
        } catch (java.io.IOException e) {
            throw e;
        } catch (Throwable t) {
            throw new javax.imageio.IIOException("CGImage to BufferedImage conversion failed", t);
        } finally {
            release(ctx);
            release(colorSpace);
        }
    }
}
