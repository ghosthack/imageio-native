package io.github.ghosthack.imageio.common;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
}
