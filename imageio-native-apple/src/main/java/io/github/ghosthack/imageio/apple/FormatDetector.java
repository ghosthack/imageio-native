package io.github.ghosthack.imageio.apple;

import javax.imageio.stream.ImageInputStream;
import java.io.IOException;
import java.util.Set;

/**
 * Format detection for the Apple ImageIO SPI.
 * <p>
 * Delegates shared magic-byte logic to
 * {@link io.github.ghosthack.imageio.common.FormatDetector} and provides
 * Apple-specific convenience methods.
 */
final class FormatDetector {

    private FormatDetector() {}

    /**
     * Checks whether an {@link ImageInputStream} starts with an ISO BMFF
     * {@code ftyp} box whose major brand or any compatible brand is in the
     * supplied set.
     */
    static boolean matchesFtyp(ImageInputStream stream, Set<String> brands) throws IOException {
        return io.github.ghosthack.imageio.common.FormatDetector.matchesFtyp(stream, brands);
    }

    /**
     * Checks whether an {@link ImageInputStream} starts with the RIFF/WEBP
     * magic bytes.
     */
    static boolean matchesWebP(ImageInputStream stream) throws IOException {
        return io.github.ghosthack.imageio.common.FormatDetector.matchesWebP(stream);
    }

    /**
     * Returns {@code true} if the header bytes match a format that Java's
     * built-in ImageIO can already decode.
     */
    static boolean isJavaNativeFormat(byte[] h, int len) {
        return io.github.ghosthack.imageio.common.FormatDetector.isJavaNativeFormat(h, len);
    }
}
