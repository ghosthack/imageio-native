package io.github.ghosthack.imageio.common;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.Dimension;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeImageReaderTest {

    private static final byte[] ENCODED = {1, 2, 3, 4, 5, 6};

    @Test
    void nonPathInputUsesTemporaryPathWithoutHeapDecode() throws IOException {
        PathReader reader = new PathReader();
        try (ImageInputStream input =
                     new MemoryCacheImageInputStream(new ByteArrayInputStream(ENCODED))) {
            reader.setInput(input, true, false);

            assertEquals(3, reader.getWidth(0));
            assertEquals(2, reader.getHeight(0));
            assertNotNull(reader.sizePath);
            assertTrue(Files.exists(reader.sizePath));
            assertEquals(ENCODED.length, input.getFlushedPosition());

            BufferedImage image = reader.read(0);

            assertEquals(3, image.getWidth());
            assertEquals(reader.sizePath, reader.decodePath);
            assertFalse(Files.exists(reader.decodePath));
        } finally {
            reader.dispose();
        }
    }

    @Test
    void disposeDeletesCachedTemporaryInput() throws IOException {
        PathReader reader = new PathReader();
        try (ImageInputStream input =
                     new MemoryCacheImageInputStream(new ByteArrayInputStream(ENCODED))) {
            reader.setInput(input);
            assertEquals(3, reader.getWidth(0));
            Path temporaryPath = reader.sizePath;
            assertTrue(Files.exists(temporaryPath));

            reader.dispose();

            assertFalse(Files.exists(temporaryPath));
        }
    }

    @Test
    void sharedFileProviderPreservesPathWithoutVideoModule() throws IOException {
        Path file = Files.createTempFile("imageio-native-test-", ".image");
        try {
            Files.write(file, ENCODED);
            ImageIO.scanForPlugins();

            try (ImageInputStream input = ImageIO.createImageInputStream(file.toFile())) {
                PathAwareImageInputStream pathInput =
                        assertInstanceOf(PathAwareImageInputStream.class, input);
                assertEquals(file.toAbsolutePath(), pathInput.getPath());

                PathReader reader = new PathReader();
                try {
                    reader.setInput(pathInput, true, false);
                    reader.read(0);
                    assertEquals(file.toAbsolutePath(), reader.decodePath);
                    assertTrue(Files.exists(file));
                } finally {
                    reader.dispose();
                }
            }
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void readAppliesImageReadParamToNativeResult() throws IOException {
        PathReader reader = new PathReader();
        try (ImageInputStream input =
                     new MemoryCacheImageInputStream(new ByteArrayInputStream(ENCODED))) {
            reader.setInput(input);
            ImageReadParam param = reader.getDefaultReadParam();
            param.setSourceRegion(new Rectangle(1, 0, 2, 2));

            BufferedImage image = reader.read(0, param);

            assertEquals(2, image.getWidth());
            assertEquals(2, image.getHeight());
        } finally {
            reader.dispose();
        }
    }

    @Test
    void readPassesRenderSizeToReducedDecodeHook() throws IOException {
        ReducedPathReader reader = new ReducedPathReader();
        try (ImageInputStream input =
                     new MemoryCacheImageInputStream(new ByteArrayInputStream(ENCODED))) {
            reader.setInput(input);
            ImageReadParam param = reader.getDefaultReadParam();
            param.setSourceRenderSize(new Dimension(4, 3));
            param.setSourceRegion(new Rectangle(1, 1, 2, 1));

            BufferedImage image = reader.read(0, param);

            assertEquals(new Dimension(4, 3), reader.requestedRenderSize);
            assertEquals(2, image.getWidth());
            assertEquals(1, image.getHeight());
        } finally {
            reader.dispose();
        }
    }

    @Test
    void rejectsOversizedFullIntermediateForFallbackParameters()
            throws IOException {
        HugeFallbackReader reader = new HugeFallbackReader();
        try (ImageInputStream input =
                     new MemoryCacheImageInputStream(
                             new ByteArrayInputStream(ENCODED))) {
            reader.setInput(input);
            ImageReadParam param = reader.getDefaultReadParam();
            param.setSourceRegion(new Rectangle(0, 0, 1, 1));

            assertThrows(
                    javax.imageio.IIOException.class,
                    () -> reader.read(0, param));
        } finally {
            reader.dispose();
        }
    }

    @Test
    void readPassesNormalizedSpatialPlanToCapableBackend()
            throws IOException {
        SpatialPathReader reader = new SpatialPathReader();
        try (ImageInputStream input =
                     new MemoryCacheImageInputStream(
                             new ByteArrayInputStream(ENCODED))) {
            reader.setInput(input);
            ImageReadParam param = reader.getDefaultReadParam();
            param.setSourceRegion(new Rectangle(1, 1, 4, 3));
            param.setSourceSubsampling(2, 2, 1, 0);

            BufferedImage image = reader.read(0, param);

            assertEquals(new Rectangle(2, 1, 3, 3), reader.sourceRegion);
            assertEquals(2, image.getWidth());
            assertEquals(2, image.getHeight());
        } finally {
            reader.dispose();
        }
    }

    private static final class PathReader extends NativeImageReader {
        private Path sizePath;
        private Path decodePath;

        private PathReader() {
            super(null);
        }

        @Override
        protected int[] nativeGetSize(byte[] data) {
            throw new AssertionError("heap byte[] size path must not be used");
        }

        @Override
        protected BufferedImage nativeDecode(byte[] data) {
            throw new AssertionError("heap byte[] decode path must not be used");
        }

        @Override
        protected int[] nativeGetSizeFromPath(String path) throws IOException {
            sizePath = Path.of(path);
            assertTrue(Files.exists(sizePath));
            assertEquals(ENCODED.length, Files.size(sizePath));
            return new int[]{3, 2};
        }

        @Override
        protected BufferedImage nativeDecodeFromPath(String path) throws IOException {
            decodePath = Path.of(path);
            assertTrue(Files.exists(decodePath));
            assertEquals(ENCODED.length, Files.size(decodePath));
            return new BufferedImage(3, 2, BufferedImage.TYPE_INT_ARGB_PRE);
        }
    }

    private static final class ReducedPathReader extends NativeImageReader {
        private Dimension requestedRenderSize;

        private ReducedPathReader() {
            super(null);
        }

        @Override
        protected boolean supportsNativeSourceRenderSize() {
            return true;
        }

        @Override
        protected int[] nativeGetSize(byte[] data) {
            throw new AssertionError("size query not expected");
        }

        @Override
        protected BufferedImage nativeDecode(byte[] data) {
            throw new AssertionError("legacy heap decode not expected");
        }

        @Override
        protected NativeDecodeResult nativeDecodeFromPath(
                String path, NativeDecodeRequest request) {
            requestedRenderSize = request.sourceRenderSize();
            return NativeDecodeResult.sourceRendered(
                    new BufferedImage(
                            requestedRenderSize.width,
                            requestedRenderSize.height,
                            BufferedImage.TYPE_INT_ARGB_PRE));
        }
    }

    private static final class HugeFallbackReader extends NativeImageReader {

        private HugeFallbackReader() {
            super(null);
        }

        @Override
        protected int[] nativeGetSize(byte[] data) {
            throw new AssertionError("heap size query not expected");
        }

        @Override
        protected BufferedImage nativeDecode(byte[] data) {
            throw new AssertionError("decode must be rejected first");
        }

        @Override
        protected int[] nativeGetSizeFromPath(String path) {
            return new int[]{20_000, 20_000};
        }
    }

    private static final class SpatialPathReader extends NativeImageReader {
        private Rectangle sourceRegion;

        private SpatialPathReader() {
            super(null);
        }

        @Override
        protected boolean supportsNativeSpatialSelection() {
            return true;
        }

        @Override
        protected int[] nativeGetSize(byte[] data) {
            throw new AssertionError("heap size query not expected");
        }

        @Override
        protected int[] nativeGetSizeFromPath(String path) {
            return new int[]{6, 5};
        }

        @Override
        protected BufferedImage nativeDecode(byte[] data) {
            throw new AssertionError("legacy decode not expected");
        }

        @Override
        protected NativeDecodeResult nativeDecodeFromPath(
                String path, NativeDecodeRequest request) {
            sourceRegion = request.sourceRegion();
            Rectangle destinationRegion = request.destinationRegion();
            BufferedImage selected = new BufferedImage(
                    destinationRegion.width,
                    destinationRegion.height,
                    BufferedImage.TYPE_INT_ARGB_PRE);
            return NativeDecodeResult.spatiallySelected(
                    selected, request);
        }
    }
}
