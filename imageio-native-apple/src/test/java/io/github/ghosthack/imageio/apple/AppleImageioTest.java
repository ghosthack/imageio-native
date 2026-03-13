package io.github.ghosthack.imageio.apple;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.imageio.IIOException;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.Set;

import static io.github.ghosthack.imageio.common.TestPixels.assertColourClose;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the {@link AppleImageio} direct public API.
 * <p>
 * Covers availability checks, format/suffix queries, canDecode probing,
 * getSize (dimension-only) and full decode — all through the public API
 * rather than through the SPI/ImageIO path.
 */
@EnabledOnOs(OS.MAC)
class AppleImageioTest {

    // ── Availability ────────────────────────────────────────────────────

    @Test
    void isAvailableReturnsTrue() {
        // On macOS (where these tests run), the backend should be available
        assertTrue(AppleImageio.isAvailable(), "Apple backend should be available on macOS");
    }

    // ── Format queries ──────────────────────────────────────────────────

    @Test
    void activeFormatsNotEmpty() {
        Set<String> formats = AppleImageio.activeFormats();
        assertNotNull(formats);
        assertFalse(formats.isEmpty(), "Active formats should not be empty in default mode");
    }

    @ParameterizedTest(name = "activeFormats contains {0}")
    @ValueSource(strings = {"HEIC", "heic", "AVIF", "avif", "WEBP", "webp"})
    void activeFormatsContainsSupplementalFormat(String name) {
        Set<String> formats = AppleImageio.activeFormats();
        assertTrue(formats.contains(name),
                "Active formats should contain '" + name + "' in supplemental mode");
    }

    @Test
    void activeSuffixesNotEmpty() {
        Set<String> suffixes = AppleImageio.activeSuffixes();
        assertNotNull(suffixes);
        assertFalse(suffixes.isEmpty(), "Active suffixes should not be empty in default mode");
    }

    @ParameterizedTest(name = "activeSuffixes contains {0}")
    @ValueSource(strings = {"heic", "avif", "webp", "jp2", "dng", "psd"})
    void activeSuffixesContainsExpected(String suffix) {
        Set<String> suffixes = AppleImageio.activeSuffixes();
        assertTrue(suffixes.contains(suffix),
                "Active suffixes should contain '" + suffix + "'");
    }

    // ── canDecode ───────────────────────────────────────────────────────

    @Test
    void canDecodeHeic() throws IOException {
        byte[] data = loadResource("test8x8.heic");
        assertTrue(AppleImageio.canDecode(data, data.length),
                "Should recognise HEIC data");
    }

    @Test
    void canDecodeAvif() throws IOException {
        byte[] data = loadResource("test8x8.avif");
        assertTrue(AppleImageio.canDecode(data, data.length),
                "Should recognise AVIF data");
    }

    @Test
    void canDecodeWebp() throws IOException {
        byte[] data = loadResource("test8x8.webp");
        assertTrue(AppleImageio.canDecode(data, data.length),
                "Should recognise WebP data");
    }

    @Test
    void canDecodeRejectsGarbage() {
        byte[] garbage = {0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08};
        assertFalse(AppleImageio.canDecode(garbage, garbage.length),
                "Should reject random bytes");
    }

    // ── getSize ─────────────────────────────────────────────────────────

    @ParameterizedTest(name = "getSize({0})")
    @ValueSource(strings = {"test8x8.heic", "test8x8.avif", "test8x8.webp", "test8x8.png"})
    void getSizeReturns8x8(String resource) throws IOException {
        byte[] data = loadResource(resource);
        Dimension size = AppleImageio.getSize(data);
        assertEquals(8, size.width, "width");
        assertEquals(8, size.height, "height");
    }

    @Test
    void getSizeThrowsOnGarbage() {
        byte[] garbage = {0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08};
        assertThrows(IIOException.class, () -> AppleImageio.getSize(garbage),
                "getSize should throw on unrecognised data");
    }

    // ── decode ──────────────────────────────────────────────────────────

    @ParameterizedTest(name = "decode({0})")
    @ValueSource(strings = {"test8x8.heic", "test8x8.avif", "test8x8.webp", "test8x8.png"})
    void decodeReturnsCorrectImage(String resource) throws IOException {
        byte[] data = loadResource(resource);
        BufferedImage img = AppleImageio.decode(data);

        assertNotNull(img, "decode returned null for " + resource);
        assertEquals(8, img.getWidth(), "width");
        assertEquals(8, img.getHeight(), "height");
        // Verify at least one pixel is non-transparent
        assertTrue(img.getRGB(0, 0) != 0, "top-left pixel should not be transparent black");
    }

    @ParameterizedTest(name = "decode quadrant colours ({0})")
    @ValueSource(strings = {"test8x8.heic", "test8x8.avif", "test8x8.webp"})
    void decodeQuadrantColours(String resource) throws IOException {
        byte[] data = loadResource(resource);
        BufferedImage img = AppleImageio.decode(data);

        assertNotNull(img);
        // 4 quadrants: red(0,0), green(7,0), blue(0,7), white(7,7)
        assertColourClose("top-left (red)",     0xFFFF0000, img.getRGB(0, 0));
        assertColourClose("top-right (green)",  0xFF00FF00, img.getRGB(7, 0));
        assertColourClose("bottom-left (blue)", 0xFF0000FF, img.getRGB(0, 7));
        assertColourClose("bottom-right (white)", 0xFFFFFFFF, img.getRGB(7, 7));
    }

    // ── EXIF orientation ────────────────────────────────────────────────

    @Test
    void getSizeRespectsExifOrientation() throws IOException {
        // test-orient6.jpg: stored 4×8 with EXIF orientation=6 (90° CW)
        // Display dimensions should be 8×4 (width/height swapped)
        byte[] data = loadResource("test-orient6.jpg");
        Dimension size = AppleImageio.getSize(data);
        assertEquals(8, size.width, "display width after orientation");
        assertEquals(4, size.height, "display height after orientation");
    }

    @Test
    void decodeAppliesExifOrientation() throws IOException {
        // test-orient6.jpg: stored 4×8 (Red/Green/Blue/White quadrants)
        // After 90° CW rotation, display is 8×4:
        //   Blue(TL) Red(TR) / White(BL) Green(BR)
        byte[] data = loadResource("test-orient6.jpg");
        BufferedImage img = AppleImageio.decode(data);

        assertNotNull(img);
        assertEquals(8, img.getWidth(), "display width");
        assertEquals(4, img.getHeight(), "display height");

        assertColourClose("top-left (blue)",      0xFF0000FF, img.getRGB(0, 0));
        assertColourClose("top-right (red)",      0xFFFF0000, img.getRGB(7, 0));
        assertColourClose("bottom-left (white)",  0xFFFFFFFF, img.getRGB(0, 3));
        assertColourClose("bottom-right (green)", 0xFF00FF00, img.getRGB(7, 3));
    }

    @Test
    void decodeThrowsOnGarbage() {
        byte[] garbage = {0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08};
        assertThrows(IIOException.class, () -> AppleImageio.decode(garbage),
                "decode should throw on unrecognised data");
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private byte[] loadResource(String name) throws IOException {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(name)) {
            assertNotNull(is, "test fixture missing: " + name);
            return is.readAllBytes();
        }
    }

}
