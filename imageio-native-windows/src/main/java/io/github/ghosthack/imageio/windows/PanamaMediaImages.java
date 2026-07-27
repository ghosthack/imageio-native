package io.github.ghosthack.imageio.windows;

import io.github.ghosthack.panama.media.core.DecodedImage;
import io.github.ghosthack.panama.media.core.PixelFormat;

import javax.imageio.IIOException;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Adapts panama-media's arena-backed BGRA images to Java2D images.
 */
final class PanamaMediaImages {

    private PanamaMediaImages() {}

    static BufferedImage toBufferedImage(DecodedImage<PixelFormat> image)
            throws IIOException {
        if (image.format() != PixelFormat.BGRA) {
            throw new IIOException("Expected BGRA pixels, got " + image.format());
        }
        if ((image.stride() & 3) != 0 || image.stride() < image.width() * 4) {
            throw new IIOException("Invalid BGRA stride: " + image.stride());
        }

        BufferedImage result = new BufferedImage(
                image.width(), image.height(), BufferedImage.TYPE_INT_ARGB_PRE);
        int[] dest = ((DataBufferInt) result.getRaster().getDataBuffer()).getData();
        MemorySegment pixels = image.pixels();

        if (image.stride() == image.width() * 4) {
            MemorySegment.copy(pixels, ValueLayout.JAVA_INT, 0, dest, 0, dest.length);
        } else {
            for (int y = 0; y < image.height(); y++) {
                MemorySegment row = pixels.asSlice((long) y * image.stride());
                MemorySegment.copy(
                        row, ValueLayout.JAVA_INT, 0,
                        dest, y * image.width(), image.width());
            }
        }
        return result;
    }

    static IIOException decodeFailure(String message, RuntimeException cause) {
        return new IIOException(message + ": " + cause.getMessage(), cause);
    }
}
