package io.github.ghosthack.imageio.windows;

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
 * Tests for the {@link WindowsImageio} direct public API.
 * <p>
 * All tests are gated by {@code @EnabledOnOs(OS.WINDOWS)} so the module
 * compiles and "passes" on macOS/Linux CI (tests are simply skipped).
 * <p>
 * Covers availability checks, format/suffix queries, canDecode probing,
 * codec installation checks, getSize (dimension-only) and full decode.
 */
@EnabledOnOs(OS.WINDOWS)
class WindowsImageioTest {

    // ── Availability ────────────────────────────────────────────────────

    @Test
    void isAvailableReturnsTrue() {
        assertTrue(WindowsImageio.isAvailable(), "WIC backend should be available on Windows");
    }

    // ── Format queries ──────────────────────────────────────────────────

    @Test
    void activeFormatsNotEmpty() {
        Set<String> formats = WindowsImageio.activeFormats();
        assertNotNull(formats);
        assertFalse(formats.isEmpty(), "Active formats should not be empty in default mode");
    }

    @ParameterizedTest(name = "activeFormats contains {0}")
    @ValueSource(strings = {"HEIC", "heic", "AVIF", "avif", "WEBP", "webp"})
    void activeFormatsContainsSupplementalFormat(String name) {
        Set<String> formats = WindowsImageio.activeFormats();
        assertTrue(formats.contains(name),
                "Active formats should contain '" + name + "' in supplemental mode");
    }

    @Test
    void activeSuffixesNotEmpty() {
        Set<String> suffixes = WindowsImageio.activeSuffixes();
        assertNotNull(suffixes);
        assertFalse(suffixes.isEmpty(), "Active suffixes should not be empty in default mode");
    }

    @ParameterizedTest(name = "activeSuffixes contains {0}")
    @ValueSource(strings = {"heic", "avif", "webp", "jxr", "wdp", "dng"})
    void activeSuffixesContainsExpected(String suffix) {
        Set<String> suffixes = WindowsImageio.activeSuffixes();
        assertTrue(suffixes.contains(suffix),
                "Active suffixes should contain '" + suffix + "'");
    }

    // ── Codec availability checks ───────────────────────────────────────

    @Test
    void heicCodecCheckDoesNotThrow() {
        // Just verify the method runs without error; result depends on
        // whether HEVC Video Extensions are installed
        boolean available = WindowsImageio.isHeicCodecInstalled();
        System.out.println("HEIC codec installed: " + available);
    }

    @Test
    void avifCodecCheckDoesNotThrow() {
        boolean available = WindowsImageio.isAvifCodecInstalled();
        System.out.println("AVIF codec installed: " + available);
    }

    @Test
    void webpCodecAvailabilityDoesNotThrow() {
        // WebP is built-in on Windows 10 1809+; may not be present on
        // all CI runners, so just verify the check runs without error.
        boolean available = WindowsImageio.isWebpCodecInstalled();
        System.out.println("WebP codec available: " + available);
    }

    // ── canDecode ───────────────────────────────────────────────────────

    @Test
    void canDecodeRejectsGarbage() {
        byte[] garbage = {0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08};
        assertFalse(WindowsImageio.canDecode(garbage, garbage.length),
                "Should reject random bytes");
    }

    // ── getSize ─────────────────────────────────────────────────────────

    @Test
    void getSizeThrowsOnGarbage() {
        byte[] garbage = {0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08};
        assertThrows(IIOException.class, () -> WindowsImageio.getSize(garbage),
                "getSize should throw on unrecognised data");
    }

    // ── EXIF orientation ────────────────────────────────────────────────

    @Test
    void getSizeRespectsExifOrientation() throws IOException {
        // test-orient6.jpg: stored 4×8 with EXIF orientation=6 (90° CW)
        // Display dimensions should be 8×4 (width/height swapped)
        byte[] data = loadResource("test-orient6.jpg");
        Dimension size = WindowsImageio.getSize(data);
        assertEquals(8, size.width, "display width after orientation");
        assertEquals(4, size.height, "display height after orientation");
    }

    @Test
    void decodeAppliesExifOrientation() throws IOException {
        // test-orient6.jpg: stored 4×8 (Red/Green/Blue/White quadrants)
        // After 90° CW rotation, display is 8×4:
        //   Blue(TL) Red(TR) / White(BL) Green(BR)
        byte[] data = loadResource("test-orient6.jpg");
        BufferedImage img = WindowsImageio.decode(data);

        assertNotNull(img);
        assertEquals(8, img.getWidth(), "display width");
        assertEquals(4, img.getHeight(), "display height");

        assertColourClose("top-left (blue)",      0xFF0000FF, img.getRGB(0, 0));
        assertColourClose("top-right (red)",      0xFFFF0000, img.getRGB(7, 0));
        assertColourClose("bottom-left (white)",  0xFFFFFFFF, img.getRGB(0, 3));
        assertColourClose("bottom-right (green)", 0xFF00FF00, img.getRGB(7, 3));
    }

    // ── decode ──────────────────────────────────────────────────────────

    @Test
    void decodeThrowsOnGarbage() {
        byte[] garbage = {0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08};
        assertThrows(IIOException.class, () -> WindowsImageio.decode(garbage),
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
