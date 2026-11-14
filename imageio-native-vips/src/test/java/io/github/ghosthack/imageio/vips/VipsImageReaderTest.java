package io.github.ghosthack.imageio.vips;

import io.github.ghosthack.imageio.common.ImageReadParamSupport;
import io.github.ghosthack.imageio.common.NativeDecodeRequest;
import io.github.ghosthack.imageio.common.NativeDecodeResult;
import io.github.ghosthack.imageio.common.TestPixels;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.imageio.ImageReadParam;
import java.awt.Dimension;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Tests for the libvips backend via the direct {@link VipsNative} API.
 * <p>
 * Uses the 8x8 test fixtures from imageio-native-common's test-jar.
 * All tests are skipped if libvips is not installed.
 */
class VipsImageReaderTest {

    /** Tolerance for lossy codec comparisons (HEIC/AVIF/WebP). */
    private static final int TOLERANCE = 70;

    /** Expected ARGB colours of the 4 quadrants in the 8x8 test images. */
    private static final int RED   = 0xFFFF0000;
    private static final int GREEN = 0xFF00FF00;
    private static final int BLUE  = 0xFF0000FF;
    private static final int WHITE = 0xFFFFFFFF;

    private void assumeVips() {
        assumeTrue(VipsNative.isAvailable(), "libvips not installed — skipping");
    }

    // ── isAvailable ─────────────────────────────────────────────────────

    @Test
    void isAvailableWhenLibInstalled() {
        // This test documents whether libvips is available in the test environment.
        // It does not fail either way.
        System.out.println("VipsNative.isAvailable() = " + VipsNative.isAvailable());
    }

    // ── canDecode ───────────────────────────────────────────────────────

    @ParameterizedTest(name = "canDecode({0})")
    @ValueSource(strings = {"test8x8.heic", "test8x8.avif", "test8x8.webp", "test8x8.png"})
    void canDecodeKnownFormats(String resource) throws IOException {
        assumeVips();
        byte[] data = loadResource(resource);
        assertTrue(VipsNative.canDecode(data, data.length),
                "libvips should be able to decode " + resource);
    }

    // ── getSize ─────────────────────────────────────────────────────────

    @ParameterizedTest(name = "getSize({0})")
    @ValueSource(strings = {"test8x8.heic", "test8x8.avif", "test8x8.webp", "test8x8.png"})
    void getSizeReturns8x8(String resource) throws IOException {
        assumeVips();
        byte[] data = loadResource(resource);
        int[] size = VipsNative.getSize(data);
        assertEquals(8, size[0], "width");
        assertEquals(8, size[1], "height");
    }

    // ── decode ──────────────────────────────────────────────────────────

    @ParameterizedTest(name = "decode({0})")
    @ValueSource(strings = {"test8x8.heic", "test8x8.avif", "test8x8.webp", "test8x8.png"})
    void decodeTo8x8BufferedImage(String resource) throws IOException {
        assumeVips();
        byte[] data = loadResource(resource);
        BufferedImage img = VipsNative.decode(data);

        assertNotNull(img, "decode should not return null for " + resource);
        assertEquals(8, img.getWidth(), "width");
        assertEquals(8, img.getHeight(), "height");
        assertEquals(BufferedImage.TYPE_INT_ARGB_PRE, img.getType(), "image type");
    }

    @ParameterizedTest(name = "decode quadrants({0})")
    @ValueSource(strings = {"test8x8.heic", "test8x8.avif", "test8x8.webp", "test8x8.png"})
    void decodeVerifyQuadrantColours(String resource) throws IOException {
        assumeVips();
        byte[] data = loadResource(resource);
        BufferedImage img = VipsNative.decode(data);

        // 8x8 test images have 4 quadrants: top-left=red, top-right=green,
        // bottom-left=blue, bottom-right=white
        int tol = resource.endsWith(".png") ? 5 : TOLERANCE;
        TestPixels.assertColourClose("top-left red",     RED,   img.getRGB(1, 1), tol);
        TestPixels.assertColourClose("top-right green",  GREEN, img.getRGB(6, 1), tol);
        TestPixels.assertColourClose("bottom-left blue", BLUE,  img.getRGB(1, 6), tol);
        TestPixels.assertColourClose("bottom-right white", WHITE, img.getRGB(6, 6), tol);
    }

    @Test
    void decodePathAtRequestedRenderSize() throws IOException {
        assumeVips();
        Path file = Files.createTempFile("imageio-native-vips-", ".png");
        try {
            Files.write(file, loadResource("test8x8.png"));

            BufferedImage image =
                    VipsNative.decodeFromPath(file.toString(), 4, 2);

            assertEquals(4, image.getWidth());
            assertEquals(2, image.getHeight());
            assertEquals(BufferedImage.TYPE_INT_ARGB_PRE, image.getType());
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void spatialDecodeMatchesSharedReference() throws IOException {
        assumeVips();
        Path file = Files.createTempFile("imageio-native-vips-", ".png");
        try {
            Files.write(file, loadResource("test8x8.png"));
            ImageReadParam param = new ImageReadParam();
            param.setSourceRegion(new Rectangle(1, 1, 6, 6));
            param.setSourceSubsampling(2, 2, 1, 1);
            NativeDecodeRequest request =
                    NativeDecodeRequest.from(param, 8, 8);

            BufferedImage reference = ImageReadParamSupport.apply(
                    VipsNative.decodeFromPath(file.toString()), param);
            BufferedImage selected =
                    VipsNative.decodeFromPath(file.toString(), request);
            BufferedImage result = ImageReadParamSupport.apply(
                    NativeDecodeResult.spatiallySelected(
                            selected, request),
                    param);

            assertImagesEqual(reference, result);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void combinesNativeRenderAndSpatialSelection() throws IOException {
        assumeVips();
        Path file = Files.createTempFile("imageio-native-vips-", ".png");
        try {
            Files.write(file, loadResource("test8x8.png"));
            ImageReadParam param =
                    ImageReadParamSupport.createDefaultReadParam();
            param.setSourceRenderSize(new Dimension(6, 6));
            param.setSourceRegion(new Rectangle(1, 1, 4, 4));
            param.setSourceSubsampling(2, 2, 0, 0);
            NativeDecodeRequest request =
                    NativeDecodeRequest.from(param, 8, 8);

            BufferedImage selected =
                    VipsNative.decodeFromPath(file.toString(), request);
            BufferedImage result = ImageReadParamSupport.apply(
                    NativeDecodeResult.spatiallySelected(
                            selected, request),
                    param);

            assertEquals(2, result.getWidth());
            assertEquals(2, result.getHeight());
        } finally {
            Files.deleteIfExists(file);
        }
    }

    // ── EXIF orientation ────────────────────────────────────────────────
    // test-orient6.jpg is stored 4x8 with EXIF orientation 6 (rotate 90° CW).
    // Display-oriented it is 8x4: Blue(TL) Red(TR) / White(BL) Green(BR).

    @Test
    void sizeQueriesReportDisplayOrientation() throws IOException {
        assumeVips();
        byte[] data = loadResource("test-orient6.jpg");
        assertArrayEquals(new int[]{8, 4}, VipsNative.getSize(data));

        Path file = Files.createTempFile("imageio-native-orient-", ".jpg");
        try {
            Files.write(file, data);
            assertArrayEquals(new int[]{8, 4},
                    VipsNative.getSizeFromPath(file.toString()));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void fullDecodeAppliesExifOrientation() throws IOException {
        assumeVips();
        byte[] data = loadResource("test-orient6.jpg");
        assertDisplayOriented(VipsNative.decode(data), 8, 4);

        Path file = Files.createTempFile("imageio-native-orient-", ".jpg");
        try {
            Files.write(file, data);
            assertDisplayOriented(
                    VipsNative.decodeFromPath(file.toString()), 8, 4);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void renderSizedDecodeMatchesFullDecodeOrientation() throws IOException {
        assumeVips();
        Path file = Files.createTempFile("imageio-native-orient-", ".jpg");
        try {
            Files.write(file, loadResource("test-orient6.jpg"));
            assertDisplayOriented(VipsNative.decodeFromPath(file.toString(), 16, 8), 16, 8);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void sourceRegionIsSelectedInDisplayOrientation() throws IOException {
        assumeVips();
        Path file = Files.createTempFile("imageio-native-orient-", ".jpg");
        try {
            Files.write(file, loadResource("test-orient6.jpg"));
            ImageReadParam param =
                    ImageReadParamSupport.createDefaultReadParam();
            // Right half of the 8x4 display image: Red over Green.
            param.setSourceRegion(new Rectangle(4, 0, 4, 4));
            NativeDecodeRequest request =
                    NativeDecodeRequest.from(param, 8, 4);

            BufferedImage region = VipsNative.decodeFromPath(file.toString(), request);

            assertEquals(4, region.getWidth());
            assertEquals(4, region.getHeight());
            TestPixels.assertColourClose(
                    "region top (red)", 0xFFFF0000, region.getRGB(2, 0), 70);
            TestPixels.assertColourClose(
                    "region bottom (green)", 0xFF00FF00, region.getRGB(2, 3), 70);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private static void assertDisplayOriented(
            BufferedImage image, int width, int height) {
        assertEquals(width, image.getWidth(), "display width");
        assertEquals(height, image.getHeight(), "display height");
        int right = width - 1;
        int bottom = height - 1;
        TestPixels.assertColourClose(
                "top-left (blue)", 0xFF0000FF, image.getRGB(0, 0), 70);
        TestPixels.assertColourClose(
                "top-right (red)", 0xFFFF0000, image.getRGB(right, 0), 70);
        TestPixels.assertColourClose(
                "bottom-left (white)", 0xFFFFFFFF, image.getRGB(0, bottom), 70);
        TestPixels.assertColourClose(
                "bottom-right (green)", 0xFF00FF00, image.getRGB(right, bottom), 70);
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private byte[] loadResource(String name) throws IOException {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(name)) {
            assertNotNull(is, "Test fixture not found: " + name);
            return is.readAllBytes();
        }
    }

    private static void assertImagesEqual(
            BufferedImage expected, BufferedImage actual) {
        assertEquals(expected.getWidth(), actual.getWidth());
        assertEquals(expected.getHeight(), actual.getHeight());
        for (int y = 0; y < expected.getHeight(); y++) {
            for (int x = 0; x < expected.getWidth(); x++) {
                assertEquals(
                        expected.getRGB(x, y),
                        actual.getRGB(x, y),
                        "pixel at " + x + "," + y);
            }
        }
    }
}
