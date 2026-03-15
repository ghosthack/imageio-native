package io.github.ghosthack.imageio.magick;

import io.github.ghosthack.imageio.common.TestPixels;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Tests for the ImageMagick backend via the direct {@link MagickNative} API.
 * <p>
 * Uses the 8x8 test fixtures from imageio-native-common's test-jar.
 * All tests are skipped if ImageMagick 7 is not installed.
 */
class MagickImageReaderTest {

    private static final int TOLERANCE = 70;

    private static final int RED   = 0xFFFF0000;
    private static final int GREEN = 0xFF00FF00;
    private static final int BLUE  = 0xFF0000FF;
    private static final int WHITE = 0xFFFFFFFF;

    private void assumeMagick() {
        assumeTrue(MagickNative.isAvailable(), "ImageMagick 7 not installed — skipping");
    }

    // ── isAvailable ─────────────────────────────────────────────────────

    @Test
    void isAvailableWhenLibInstalled() {
        System.out.println("MagickNative.isAvailable() = " + MagickNative.isAvailable());
    }

    // ── canDecode ───────────────────────────────────────────────────────

    @ParameterizedTest(name = "canDecode({0})")
    @ValueSource(strings = {"test8x8.heic", "test8x8.avif", "test8x8.webp", "test8x8.png"})
    void canDecodeKnownFormats(String resource) throws IOException {
        assumeMagick();
        byte[] data = loadResource(resource);
        assertTrue(MagickNative.canDecode(data, data.length),
                "ImageMagick should be able to decode " + resource);
    }

    // ── getSize ─────────────────────────────────────────────────────────

    @ParameterizedTest(name = "getSize({0})")
    @ValueSource(strings = {"test8x8.heic", "test8x8.avif", "test8x8.webp", "test8x8.png"})
    void getSizeReturns8x8(String resource) throws IOException {
        assumeMagick();
        byte[] data = loadResource(resource);
        int[] size = MagickNative.getSize(data);
        assertEquals(8, size[0], "width");
        assertEquals(8, size[1], "height");
    }

    // ── decode ──────────────────────────────────────────────────────────

    @ParameterizedTest(name = "decode({0})")
    @ValueSource(strings = {"test8x8.heic", "test8x8.avif", "test8x8.webp", "test8x8.png"})
    void decodeTo8x8BufferedImage(String resource) throws IOException {
        assumeMagick();
        byte[] data = loadResource(resource);
        BufferedImage img = MagickNative.decode(data);

        assertNotNull(img, "decode should not return null for " + resource);
        assertEquals(8, img.getWidth(), "width");
        assertEquals(8, img.getHeight(), "height");
        assertEquals(BufferedImage.TYPE_INT_ARGB_PRE, img.getType(), "image type");
    }

    @ParameterizedTest(name = "decode quadrants({0})")
    @ValueSource(strings = {"test8x8.heic", "test8x8.avif", "test8x8.webp", "test8x8.png"})
    void decodeVerifyQuadrantColours(String resource) throws IOException {
        assumeMagick();
        byte[] data = loadResource(resource);
        BufferedImage img = MagickNative.decode(data);

        int tol = resource.endsWith(".png") ? 5 : TOLERANCE;
        TestPixels.assertColourClose("top-left red",     RED,   img.getRGB(1, 1), tol);
        TestPixels.assertColourClose("top-right green",  GREEN, img.getRGB(6, 1), tol);
        TestPixels.assertColourClose("bottom-left blue", BLUE,  img.getRGB(1, 6), tol);
        TestPixels.assertColourClose("bottom-right white", WHITE, img.getRGB(6, 6), tol);
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private byte[] loadResource(String name) throws IOException {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(name)) {
            assertNotNull(is, "Test fixture not found: " + name);
            return is.readAllBytes();
        }
    }
}
