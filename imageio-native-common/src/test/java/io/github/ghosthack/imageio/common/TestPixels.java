package io.github.ghosthack.imageio.common;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shared pixel-comparison utility for tests that verify decoded image colours.
 * <p>
 * Lossy codecs (HEIC, AVIF) may shift colours even at maximum quality on a
 * tiny 4x4 image, so an exact match is too strict. This helper compares each
 * ARGB channel independently with a configurable per-channel tolerance.
 */
public final class TestPixels {

    /** Default per-channel tolerance (covers HEIC/AVIF lossy shift on 4x4 images). */
    public static final int DEFAULT_TOLERANCE = 40;

    private TestPixels() {}

    /**
     * Asserts that two ARGB colours are within {@value #DEFAULT_TOLERANCE}
     * per channel.
     *
     * @param label    human-readable label shown on failure
     * @param expected expected ARGB colour (e.g. {@code 0xFFFF0000} for red)
     * @param actual   actual ARGB colour from {@code BufferedImage.getRGB()}
     */
    public static void assertColourClose(String label, int expected, int actual) {
        assertColourClose(label, expected, actual, DEFAULT_TOLERANCE);
    }

    /**
     * Asserts that two ARGB colours are within the given per-channel tolerance.
     *
     * @param label     human-readable label shown on failure
     * @param expected  expected ARGB colour
     * @param actual    actual ARGB colour
     * @param tolerance maximum allowed difference per channel (0-255)
     */
    public static void assertColourClose(String label, int expected, int actual, int tolerance) {
        int ea = (expected >> 24) & 0xFF, er = (expected >> 16) & 0xFF,
            eg = (expected >>  8) & 0xFF, eb =  expected        & 0xFF;
        int aa = (actual   >> 24) & 0xFF, ar = (actual   >> 16) & 0xFF,
            ag = (actual   >>  8) & 0xFF, ab =  actual          & 0xFF;
        boolean close = Math.abs(ea - aa) <= tolerance
                     && Math.abs(er - ar) <= tolerance
                     && Math.abs(eg - ag) <= tolerance
                     && Math.abs(eb - ab) <= tolerance;
        assertTrue(close,
                String.format("%s: expected #%08X but got #%08X (tolerance %d)",
                        label, expected, actual, tolerance));
    }
}
