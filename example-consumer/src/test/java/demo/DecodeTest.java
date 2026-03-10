package demo;

import io.github.ghosthack.imageio.ImageioNative;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
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
 * <p>
 * Run with default (supplemental) mode:
 * <pre>mvn test</pre>
 * <p>
 * Run with all-formats mode (native backend decodes JPEG/PNG too):
 * <pre>mvn test -Dimageio.native.formats=all</pre>
 */
class DecodeTest {

    private void assumeCanDecode(String resource) throws IOException {
        byte[] data = loadResource(resource);
        assumeTrue(ImageioNative.canDecode(data, data.length),
                resource + " codec not available — skipping");
    }

    // ── Supplemental formats (SPI path) ─────────────────────────────────

    @ParameterizedTest(name = "decode {0}")
    @ValueSource(strings = {"test4x4.heic", "test4x4.avif", "test4x4.webp"})
    void supplementalFormatsDecoded(String resource) throws IOException {
        assumeCanDecode(resource);
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(in, "fixture missing: " + resource);

            BufferedImage img = ImageIO.read(in);

            assertNotNull(img, "ImageIO.read() returned null for " + resource);
            assertEquals(4, img.getWidth());
            assertEquals(4, img.getHeight());
            assertTrue(img.getRGB(0, 0) != 0, "top-left pixel should not be transparent black");
        }
    }

    // ── PNG always readable (Java builtin or native in "all" mode) ──────

    @Test
    void pngAlwaysReadable() throws IOException {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("test4x4.png")) {
            assertNotNull(in);
            BufferedImage img = ImageIO.read(in);
            assertNotNull(img, "PNG should always be decodable");
            assertEquals(4, img.getWidth());
        }
    }

    // ── In "all" mode, native SPI should claim PNG too ──────────────────

    @Test
    void allModeClaimsPng() {
        String mode = System.getProperty("imageio.native.formats", "supplemental");
        if (!"all".equals(mode)) return; // only relevant in "all" mode

        Iterator<ImageReader> readers = ImageIO.getImageReadersBySuffix("png");
        boolean foundNative = false;
        while (readers.hasNext()) {
            ImageReader r = readers.next();
            if (r.getClass().getName().contains("ghosthack")) {
                foundNative = true;
                break;
            }
        }
        assertTrue(foundNative, "In 'all' mode, native SPI should claim PNG");
    }

    // ── Direct API: availability ────────────────────────────────────────

    @Test
    void directApiAvailable() {
        assertTrue(ImageioNative.isAvailable(),
                "ImageioNative should be available on this platform");
    }

    // ── Direct API: format and suffix queries ───────────────────────────

    @Test
    void directApiActiveFormats() {
        Set<String> formats = ImageioNative.activeFormats();
        assertNotNull(formats);
        assertFalse(formats.isEmpty());
        // In supplemental mode, should contain HEIC/AVIF/WEBP
        assertTrue(formats.contains("HEIC") || formats.contains("heic"),
                "Active formats should include HEIC");
    }

    @Test
    void directApiActiveSuffixes() {
        Set<String> suffixes = ImageioNative.activeSuffixes();
        assertNotNull(suffixes);
        assertTrue(suffixes.contains("heic"), "Should contain heic suffix");
        assertTrue(suffixes.contains("avif"), "Should contain avif suffix");
        assertTrue(suffixes.contains("webp"), "Should contain webp suffix");
    }

    // ── Direct API: canDecode ───────────────────────────────────────────

    @ParameterizedTest(name = "canDecode({0})")
    @ValueSource(strings = {"test4x4.heic", "test4x4.avif", "test4x4.webp"})
    void directApiCanDecode(String resource) throws IOException {
        assumeCanDecode(resource);
        byte[] data = loadResource(resource);
        assertTrue(ImageioNative.canDecode(data, data.length),
                "canDecode should return true for " + resource);
    }

    // ── Direct API: getSize ─────────────────────────────────────────────

    @ParameterizedTest(name = "getSize({0})")
    @ValueSource(strings = {"test4x4.heic", "test4x4.avif", "test4x4.webp"})
    void directApiGetSize(String resource) throws IOException {
        assumeCanDecode(resource);
        byte[] data = loadResource(resource);
        Dimension size = ImageioNative.getSize(data);
        assertEquals(4, size.width, "width");
        assertEquals(4, size.height, "height");
    }

    // ── Direct API: decode ──────────────────────────────────────────────

    @ParameterizedTest(name = "decode({0})")
    @ValueSource(strings = {"test4x4.heic", "test4x4.avif", "test4x4.webp"})
    void directApiDecode(String resource) throws IOException {
        assumeCanDecode(resource);
        byte[] data = loadResource(resource);
        BufferedImage img = ImageioNative.decode(data);

        assertNotNull(img, "decode returned null for " + resource);
        assertEquals(4, img.getWidth());
        assertEquals(4, img.getHeight());
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
