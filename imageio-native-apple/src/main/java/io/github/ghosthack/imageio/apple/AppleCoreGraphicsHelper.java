package io.github.ghosthack.imageio.apple;

import io.github.ghosthack.panama.media.cgimage.CGBitmapContext;
import io.github.ghosthack.panama.media.cgimage.CGColorSpace;
import io.github.ghosthack.panama.media.cgimage.CGContext;
import io.github.ghosthack.panama.media.cgimage.CGGeometry;
import io.github.ghosthack.panama.media.cgimage.CGImage;
import io.github.ghosthack.panama.media.corefoundation.CoreFoundation;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.IOException;
import java.lang.foreign.*;

/**
 * Public helper that converts a CGImageRef to a {@link BufferedImage}.
 * <p>
 * The native calls go through the {@code panama-media-cgimage} and
 * {@code panama-media-corefoundation} bindings.
 * <p>
 * Intended to be used by both {@link AppleNative} (for still-image decode)
 * and the video-apple module (which lives in a different package and cannot
 * access package-private members).
 * <p>
 * Requires macOS and native access for
 * {@code io.github.ghosthack.panama.media.core},
 * {@code io.github.ghosthack.panama.media.corefoundation} and
 * {@code io.github.ghosthack.panama.media.cgimage}, or {@code ALL-UNNAMED}
 * when used on the class path.
 */
public final class AppleCoreGraphicsHelper {

    private AppleCoreGraphicsHelper() {}

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
            throws IOException {
        MemorySegment colorSpace = MemorySegment.NULL;
        MemorySegment ctx = MemorySegment.NULL;
        try {
            long w = CGImage.getWidth(cgImage);
            long h = CGImage.getHeight(cgImage);
            if (w <= 0 || h <= 0) {
                throw new javax.imageio.IIOException("Invalid CGImage dimensions: " + w + "x" + h);
            }

            colorSpace = CGColorSpace.createDeviceRGB();

            // BGRA premultiplied, little-endian — matches TYPE_INT_ARGB_PRE when read as LE ints.
            long bytesPerRow = w * 4;
            MemorySegment pixelData = arena.allocate(bytesPerRow * h, 16);
            ctx = CGBitmapContext.create(pixelData, w, h, 8L, bytesPerRow, colorSpace,
                    CGBitmapContext.BitmapInfo.BGRA_PREMULTIPLIED_LITTLE_ENDIAN);
            if (MemorySegment.NULL.equals(ctx)) {
                throw new javax.imageio.IIOException("CGBitmapContextCreate returned NULL");
            }

            // Draw the CGImage into the bitmap context
            CGContext.drawImage(ctx, CGGeometry.CGRect.allocate(arena, 0, 0, w, h), cgImage);

            // Copy pixels into a BufferedImage
            int iw = (int) w;
            int ih = (int) h;
            BufferedImage result = new BufferedImage(iw, ih, BufferedImage.TYPE_INT_ARGB_PRE);
            int[] dest = ((DataBufferInt) result.getRaster().getDataBuffer()).getData();
            MemorySegment.copy(pixelData, ValueLayout.JAVA_INT, 0, dest, 0, dest.length);

            return result;
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw new javax.imageio.IIOException("CGImage to BufferedImage conversion failed", t);
        } finally {
            CoreFoundation.cfRelease(ctx);
            CoreFoundation.cfRelease(colorSpace);
        }
    }
}
