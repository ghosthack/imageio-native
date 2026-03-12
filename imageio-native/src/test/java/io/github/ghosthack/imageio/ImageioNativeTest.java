package io.github.ghosthack.imageio;

import org.junit.jupiter.api.Test;
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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Tests for the {@link ImageioNative} unified cross-platform facade.
 * <p>
 * On macOS these exercise the Apple backend; on Windows the WIC backend.
 * The tests verify the delegation logic and the public API contract.
 */
class ImageioNativeTest {

    /**
     * On macOS all codecs are built-in; on Windows HEIC/AVIF/WebP require
     * optional store-installed codecs that may not be present on CI runners.
     * Skip tests that need actual decoding when the codec is absent.
     */
    private void assumeCanDecode(String resource) throws IOException {
        if (!ImageioNative.isAvailable()) return;
        byte[] data = loadResource(resource);
        assumeTrue(ImageioNative.canDecode(data, data.length),
                resource + " codec not available — skipping");
    }

    // ── Availability ────────────────────────────────────────────────────

    @Test
    void isAvailableOnSupportedPlatform() {
        // On macOS or Windows, at least one backend should be available
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("mac") || os.contains("windows")) {
            assertTrue(ImageioNative.isAvailable(),
                    "Native backend should be available on " + os);
        }
    }

    // ── Format queries ──────────────────────────────────────────────────

    @Test
    void activeFormatsNotEmpty() {
        if (!ImageioNative.isAvailable()) return;
        Set<String> formats = ImageioNative.activeFormats();
        assertNotNull(formats);
        assertFalse(formats.isEmpty(), "Active formats should not be empty");
    }

    @ParameterizedTest(name = "activeFormats contains {0}")
    @ValueSource(strings = {"HEIC", "heic", "AVIF", "avif", "WEBP", "webp"})
    void activeFormatsContainsSupplementalFormat(String name) {
        if (!ImageioNative.isAvailable()) return;
        Set<String> formats = ImageioNative.activeFormats();
        assertTrue(formats.contains(name),
                "Active formats should contain '" + name + "'");
    }

    @Test
    void activeSuffixesNotEmpty() {
        if (!ImageioNative.isAvailable()) return;
        Set<String> suffixes = ImageioNative.activeSuffixes();
        assertNotNull(suffixes);
        assertFalse(suffixes.isEmpty(), "Active suffixes should not be empty");
    }

    @ParameterizedTest(name = "activeSuffixes contains {0}")
    @ValueSource(strings = {"heic", "avif", "webp"})
    void activeSuffixesContainsExpected(String suffix) {
        if (!ImageioNative.isAvailable()) return;
        Set<String> suffixes = ImageioNative.activeSuffixes();
        assertTrue(suffixes.contains(suffix),
                "Active suffixes should contain '" + suffix + "'");
    }

    // ── canDecode ───────────────────────────────────────────────────────

    @Test
    void canDecodeHeic() throws IOException {
        if (!ImageioNative.isAvailable()) return;
        byte[] data = loadResource("test8x8.heic");
        assumeTrue(ImageioNative.canDecode(data, data.length),
                "HEIC codec not available — skipping");
    }

    @Test
    void canDecodeAvif() throws IOException {
        if (!ImageioNative.isAvailable()) return;
        byte[] data = loadResource("test8x8.avif");
        assumeTrue(ImageioNative.canDecode(data, data.length),
                "AVIF codec not available — skipping");
    }

    @Test
    void canDecodeWebp() throws IOException {
        if (!ImageioNative.isAvailable()) return;
        byte[] data = loadResource("test8x8.webp");
        assumeTrue(ImageioNative.canDecode(data, data.length),
                "WebP codec not available — skipping");
    }

    @Test
    void canDecodeRejectsGarbage() {
        byte[] garbage = {0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08};
        assertFalse(ImageioNative.canDecode(garbage, garbage.length),
                "Should reject random bytes");
    }

    // ── getSize ─────────────────────────────────────────────────────────

    @ParameterizedTest(name = "getSize({0})")
    @ValueSource(strings = {"test8x8.heic", "test8x8.avif", "test8x8.webp", "test8x8.png"})
    void getSizeReturns8x8(String resource) throws IOException {
        if (!ImageioNative.isAvailable()) return;
        assumeCanDecode(resource);
        byte[] data = loadResource(resource);
        Dimension size = ImageioNative.getSize(data);
        assertEquals(8, size.width, "width");
        assertEquals(8, size.height, "height");
    }

    @Test
    void getSizeThrowsOnGarbage() {
        if (!ImageioNative.isAvailable()) return;
        byte[] garbage = {0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08};
        assertThrows(IIOException.class, () -> ImageioNative.getSize(garbage),
                "getSize should throw on unrecognised data");
    }

    // ── decode ──────────────────────────────────────────────────────────

    @ParameterizedTest(name = "decode({0})")
    @ValueSource(strings = {"test8x8.heic", "test8x8.avif", "test8x8.webp", "test8x8.png"})
    void decodeReturnsCorrectImage(String resource) throws IOException {
        if (!ImageioNative.isAvailable()) return;
        assumeCanDecode(resource);
        byte[] data = loadResource(resource);
        BufferedImage img = ImageioNative.decode(data);

        assertNotNull(img, "decode returned null for " + resource);
        assertEquals(8, img.getWidth(), "width");
        assertEquals(8, img.getHeight(), "height");
        assertTrue(img.getRGB(0, 0) != 0, "top-left pixel should not be transparent black");
    }

    @ParameterizedTest(name = "decode quadrant colours ({0})")
    @ValueSource(strings = {"test8x8.heic", "test8x8.avif", "test8x8.webp"})
    void decodeQuadrantColours(String resource) throws IOException {
        if (!ImageioNative.isAvailable()) return;
        assumeCanDecode(resource);
        byte[] data = loadResource(resource);
        BufferedImage img = ImageioNative.decode(data);

        assertNotNull(img);
        assertColourClose("top-left (red)",     0xFFFF0000, img.getRGB(0, 0));
        assertColourClose("top-right (green)",  0xFF00FF00, img.getRGB(7, 0));
        assertColourClose("bottom-left (blue)", 0xFF0000FF, img.getRGB(0, 7));
        assertColourClose("bottom-right (white)", 0xFFFFFFFF, img.getRGB(7, 7));
    }

    @Test
    void decodeThrowsOnGarbage() {
        if (!ImageioNative.isAvailable()) return;
        byte[] garbage = {0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08};
        assertThrows(IIOException.class, () -> ImageioNative.decode(garbage),
                "decode should throw on unrecognised data");
    }

    // ── No-backend behaviour ────────────────────────────────────────────

    @Test
    void canDecodeReturnsFalseWhenNoBackend() {
        // This test verifies the contract: on unsupported OS, canDecode → false
        // We can't truly test this on macOS/Windows, but we verify the method
        // doesn't throw
        byte[] data = {0x01, 0x02, 0x03, 0x04};
        assertFalse(ImageioNative.canDecode(data, data.length));
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private byte[] loadResource(String name) throws IOException {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(name)) {
            assertNotNull(is, "test fixture missing: " + name);
            return is.readAllBytes();
        }
    }

}
