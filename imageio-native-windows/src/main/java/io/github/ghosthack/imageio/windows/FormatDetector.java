package io.github.ghosthack.imageio.windows;

/**
 * Format detection for the WIC ImageIO SPI.
 * <p>
 * Delegates shared magic-byte logic to
 * {@link io.github.ghosthack.imageio.common.FormatDetector}.
 */
final class FormatDetector {

    private FormatDetector() {}

    /**
     * Returns {@code true} if the header bytes match a format that Java's
     * built-in ImageIO can already decode.
     */
    static boolean isJavaNativeFormat(byte[] h, int len) {
        return io.github.ghosthack.imageio.common.FormatDetector.isJavaNativeFormat(h, len);
    }
}
