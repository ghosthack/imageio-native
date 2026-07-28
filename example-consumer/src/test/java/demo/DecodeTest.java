package demo;

import io.github.ghosthack.imageio.ImageioNative;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Consumer-side integration test.
 * <p>
 * Verifies that adding imageio-native as a dependency is sufficient for
 * {@code ImageIO.read()} to decode HEIC, AVIF, and WEBP -- no manual
 * SPI registration, no extra flags beyond {@code --enable-native-access}.
 * <p>
 * Also tests the direct {@link ImageioNative} API for decode, getSize,
 * canDecode, and format/suffix queries.
 * <p>
 * Codec-dependent tests are skipped when the required codec is not
 * installed (e.g. on CI runners without HEVC/AV1/WebP extensions).
 */
class DecodeTest {

    private void assumeCanDecode(String resource) throws IOException {
        byte[] data = loadResource(resource);
        assumeTrue(ImageioNative.canDecode(data, data.length),
                resource + " codec not available — skipping");
    }

    // ── Backend formats (SPI path) ──────────────────────────────────────

    @ParameterizedTest(name = "decode {0}")
    @ValueSource(strings = {"test8x8.heic", "test8x8.avif", "test8x8.webp"})
    void backendFormatsDecoded(String resource) throws IOException {
        assumeCanDecode(resource);
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(in, "fixture missing: " + resource);

            BufferedImage img = ImageIO.read(in);

            assertNotNull(img, "ImageIO.read() returned null for " + resource);
            assertEquals(8, img.getWidth());
            assertEquals(8, img.getHeight());
            assertTrue(img.getRGB(0, 0) != 0, "top-left pixel should not be transparent black");
        }
    }

    // ── PNG is owned by the installed capable backend ───────────────────

    @Test
    void pngAlwaysReadable() throws IOException {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("test8x8.png")) {
            assertNotNull(in);
            BufferedImage img = ImageIO.read(in);
            assertNotNull(img, "PNG should always be decodable");
            assertEquals(8, img.getWidth());
        }
    }

    @Test
    void installedBackendClaimsPng() throws IOException {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("test8x8.png");
             ImageInputStream imageInput = ImageIO.createImageInputStream(in)) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(imageInput);
            assertTrue(readers.hasNext());
            ImageReader reader = readers.next();
            if (ImageioNative.isAvailable()) {
                assertTrue(
                        reader.getClass().getName().startsWith(
                                "io.github.ghosthack"),
                        "The installed capable backend should own PNG");
            } else {
                assertFalse(
                        reader.getClass().getName().startsWith(
                                "io.github.ghosthack"),
                        "Without a native still-image backend, host ImageIO "
                                + "should own PNG");
            }
            reader.dispose();
        }
    }

    // ── Direct API: availability ────────────────────────────────────────

    @Test
    void directApiAvailable() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("mac") || os.contains("windows")) {
            assertTrue(
                    ImageioNative.isAvailable(),
                    "ImageioNative should be available on " + os);
        } else {
            assertFalse(
                    ImageioNative.isAvailable(),
                    "The Apple/Windows aggregator should remain unavailable "
                            + "on " + os);
        }
    }

    // ── Direct API: format and suffix queries ───────────────────────────

    @Test
    void directApiActiveFormats() {
        Set<String> formats = ImageioNative.activeFormats();
        assertNotNull(formats);
        if (!ImageioNative.isAvailable()) {
            assertTrue(formats.isEmpty());
            return;
        }
        assertFalse(formats.isEmpty());
        // The platform backend declares HEIC/AVIF/WebP candidates.
        assertTrue(formats.contains("HEIC") || formats.contains("heic"),
                "Active formats should include HEIC");
    }

    @Test
    void directApiActiveSuffixes() {
        Set<String> suffixes = ImageioNative.activeSuffixes();
        assertNotNull(suffixes);
        if (!ImageioNative.isAvailable()) {
            assertTrue(suffixes.isEmpty());
            return;
        }
        assertTrue(suffixes.contains("heic"), "Should contain heic suffix");
        assertTrue(suffixes.contains("avif"), "Should contain avif suffix");
        assertTrue(suffixes.contains("webp"), "Should contain webp suffix");
    }

    // ── Direct API: canDecode ───────────────────────────────────────────

    @ParameterizedTest(name = "canDecode({0})")
    @ValueSource(strings = {"test8x8.heic", "test8x8.avif", "test8x8.webp"})
    void directApiCanDecode(String resource) throws IOException {
        assumeCanDecode(resource);
        byte[] data = loadResource(resource);
        assertTrue(ImageioNative.canDecode(data, data.length),
                "canDecode should return true for " + resource);
    }

    // ── Direct API: getSize ─────────────────────────────────────────────

    @ParameterizedTest(name = "getSize({0})")
    @ValueSource(strings = {"test8x8.heic", "test8x8.avif", "test8x8.webp"})
    void directApiGetSize(String resource) throws IOException {
        assumeCanDecode(resource);
        byte[] data = loadResource(resource);
        Dimension size = ImageioNative.getSize(data);
        assertEquals(8, size.width, "width");
        assertEquals(8, size.height, "height");
    }

    // ── Direct API: decode ──────────────────────────────────────────────

    @ParameterizedTest(name = "decode({0})")
    @ValueSource(strings = {"test8x8.heic", "test8x8.avif", "test8x8.webp"})
    void directApiDecode(String resource) throws IOException {
        assumeCanDecode(resource);
        byte[] data = loadResource(resource);
        BufferedImage img = ImageioNative.decode(data);

        assertNotNull(img, "decode returned null for " + resource);
        assertEquals(8, img.getWidth());
        assertEquals(8, img.getHeight());
        assertTrue(img.getRGB(0, 0) != 0, "top-left pixel should not be transparent black");
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private byte[] loadResource(String name) throws IOException {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(name)) {
            assertNotNull(is, "test fixture missing: " + name);
            return is.readAllBytes();
        }
    }
}
