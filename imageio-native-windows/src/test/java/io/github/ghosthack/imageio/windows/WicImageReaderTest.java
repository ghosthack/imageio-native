package io.github.ghosthack.imageio.windows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import java.util.Map;

import static io.github.ghosthack.imageio.common.TestPixels.assertColourClose;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * End-to-end tests for the WIC-backed image ImageIO readers.
 * <p>
 * All tests are gated by {@code @EnabledOnOs(OS.WINDOWS)} so the module
 * compiles and "passes" on macOS/Linux CI (tests are simply skipped).
 * <p>
 * Each image test loads an 8×8 test fixture with four solid-colour quadrants
 * (red / green / blue / white) and verifies that the decoded
 * {@link BufferedImage} has the expected dimensions and pixel colours.
 */
@EnabledOnOs(OS.WINDOWS)
class WicImageReaderTest {

    // Expected ARGB values (fully opaque).
    // Lossy codecs may shift colours, so we compare with a tolerance.
    private static final int RED   = 0xFFFF0000;
    private static final int GREEN = 0xFF00FF00;
    private static final int BLUE  = 0xFF0000FF;
    private static final int WHITE = 0xFFFFFFFF;

    private static final Map<String, Boolean> CODEC_AVAILABLE = Map.of(
            "heic", CodecChecker.isHeicAvailable(),
            "avif", CodecChecker.isAvifAvailable(),
            "webp", CodecChecker.isWebpAvailable()
    );

    private static void assumeCodec(String resource) {
        String fmt = resource.substring(resource.lastIndexOf('.') + 1);
        assumeTrue(CODEC_AVAILABLE.getOrDefault(fmt, false),
                fmt.toUpperCase() + " codec not installed — skipping");
    }

    // ── ImageIO.read round-trip ─────────────────────────────────────────

    @ParameterizedTest(name = "ImageIO.read({0})")
    @CsvSource({"test8x8.heic", "test8x8.avif", "test8x8.webp"})
    void readViaImageIO(String resource) throws IOException {
        assumeCodec(resource);
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(is, "test fixture missing: " + resource);
            BufferedImage img = ImageIO.read(is);
            assertNotNull(img, "ImageIO.read returned null for " + resource);
            assertDimensions(img);
            assertQuadrantColours(img);
        }
    }

    // ── SPI lookup by format name ───────────────────────────────────────

    @ParameterizedTest(name = "getReadersByFormatName({1})")
    @CsvSource({"test8x8.heic, heic", "test8x8.avif, avif", "test8x8.webp, webp"})
    void readerLookupByFormatName(String resource, String formatName) throws IOException {
        assumeCodec(resource);
        Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName(formatName);
        assertTrue(readers.hasNext(), "No reader registered for format: " + formatName);

        ImageReader reader = readers.next();
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resource);
             ImageInputStream iis = ImageIO.createImageInputStream(is)) {
            assertNotNull(iis);
            reader.setInput(iis);
            assertEquals(8, reader.getWidth(0));
            assertEquals(8, reader.getHeight(0));
            assertEquals(1, reader.getNumImages(true));

            BufferedImage img = reader.read(0);
            assertNotNull(img);
            assertQuadrantColours(img);
        } finally {
            reader.dispose();
        }
    }

    // ── SPI lookup by MIME type ─────────────────────────────────────────

    @ParameterizedTest(name = "getReadersByMIMEType({0})")
    @CsvSource({"image/heic", "image/avif", "image/webp"})
    void readerLookupByMimeType(String mimeType) {
        Iterator<ImageReader> readers = ImageIO.getImageReadersByMIMEType(mimeType);
        assertTrue(readers.hasNext(), "No reader registered for MIME type: " + mimeType);
        assertInstanceOf(WicImageReader.class, readers.next());
    }

    // ── SPI lookup by suffix ────────────────────────────────────────────

    @ParameterizedTest(name = "getReadersBySuffix({0})")
    @CsvSource({"heic", "heif", "avif", "webp"})
    void readerLookupBySuffix(String suffix) {
        Iterator<ImageReader> readers = ImageIO.getImageReadersBySuffix(suffix);
        assertTrue(readers.hasNext(), "No reader registered for suffix: " + suffix);
        assertInstanceOf(WicImageReader.class, readers.next());
    }

    // ── WIC-specific format suffixes (supplemental defaults) ────────────

    @ParameterizedTest(name = "supplemental suffix registered: {0}")
    @ValueSource(strings = {"jxr", "wdp", "dds", "dng", "cr2", "nef", "arw", "ico"})
    void supplementalSuffixRegistered(String suffix) {
        Iterator<ImageReader> readers = ImageIO.getImageReadersBySuffix(suffix);
        assertTrue(readers.hasNext(), "No reader registered for supplemental suffix: " + suffix);
        assertInstanceOf(WicImageReader.class, readers.next());
    }

    // ── Supplemental mode should NOT claim Java-native formats ──────────

    @Test
    void pngReadDoesNotUseWicReader() throws IOException {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("test8x8.png")) {
            assertNotNull(is);
            BufferedImage img = ImageIO.read(is);
            assertNotNull(img, "PNG should still be readable");
            assertEquals(8, img.getWidth());
        }
    }

    // ── Codec checker tests ─────────────────────────────────────────────

    @Test
    void webpCodecAvailabilityDoesNotThrow() {
        boolean available = CodecChecker.isWebpAvailable();
        System.out.println("WebP codec available: " + available);
    }

    @Test
    void heicCodecAvailabilityDoesNotThrow() {
        boolean available = CodecChecker.isHeicAvailable();
        System.out.println("HEIC codec available: " + available);
    }

    @Test
    void avifCodecAvailabilityDoesNotThrow() {
        boolean available = CodecChecker.isAvifAvailable();
        System.out.println("AVIF codec available: " + available);
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private static void assertDimensions(BufferedImage img) {
        assertEquals(8, img.getWidth(),  "width");
        assertEquals(8, img.getHeight(), "height");
    }

    private static void assertQuadrantColours(BufferedImage img) {
        assertColourClose("top-left (red)",     RED,   img.getRGB(0, 0));
        assertColourClose("top-right (green)",  GREEN, img.getRGB(7, 0));
        assertColourClose("bottom-left (blue)", BLUE,  img.getRGB(0, 7));
        assertColourClose("bottom-right (white)", WHITE, img.getRGB(7, 7));
    }

}
