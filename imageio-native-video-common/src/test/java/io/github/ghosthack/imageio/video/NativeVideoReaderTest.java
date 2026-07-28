package io.github.ghosthack.imageio.video;

import io.github.ghosthack.imageio.common.PathAwareImageInputStream;
import io.github.ghosthack.imageio.common.RoutingBackend;
import org.junit.jupiter.api.Test;

import javax.imageio.IIOException;
import javax.imageio.ImageReadParam;
import java.awt.Dimension;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NativeVideoReaderTest {

    @Test
    void readAppliesImageReadParamToExtractedFrame() throws IOException {
        Path file = Files.createTempFile("imageio-native-video-reader-", ".mp4");
        try (PathAwareImageInputStream input =
                     new PathAwareImageInputStream(file.toFile())) {
            NativeVideoReader reader =
                    new NativeVideoReader(null, new TestProvider());
            try {
                reader.setInput(input);
                ImageReadParam param = reader.getDefaultReadParam();
                param.setSourceRegion(new Rectangle(1, 1, 3, 2));
                param.setSourceSubsampling(2, 1, 0, 0);

                BufferedImage image = reader.read(0, param);

                assertEquals(2, image.getWidth());
                assertEquals(2, image.getHeight());
            } finally {
                reader.dispose();
            }
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void readPassesRenderSizeToProvider() throws IOException {
        Path file = Files.createTempFile(
                "imageio-native-video-reader-", ".mp4");
        try (PathAwareImageInputStream input =
                     new PathAwareImageInputStream(file.toFile())) {
            TestProvider provider = new TestProvider();
            NativeVideoReader reader =
                    new NativeVideoReader(null, provider);
            try {
                reader.setInput(input);
                ImageReadParam param = reader.getDefaultReadParam();
                param.setSourceRenderSize(new Dimension(3, 2));

                BufferedImage image = reader.read(0, param);

                assertEquals(new Dimension(3, 2), provider.requestedSize);
                assertEquals(3, image.getWidth());
                assertEquals(2, image.getHeight());
            } finally {
                reader.dispose();
            }
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void rejectsHugeSpatialFallbackBeforeFrameExtraction()
            throws IOException {
        Path file = Files.createTempFile(
                "imageio-native-video-reader-", ".mp4");
        HugeProvider provider = new HugeProvider();
        try (PathAwareImageInputStream input =
                     new PathAwareImageInputStream(file.toFile())) {
            NativeVideoReader reader =
                    new NativeVideoReader(null, provider);
            try {
                reader.setInput(input);
                ImageReadParam param = reader.getDefaultReadParam();
                param.setSourceRegion(new Rectangle(0, 0, 1, 1));

                assertThrows(
                        IIOException.class,
                        () -> reader.read(0, param));
                assertFalse(provider.extracted);
            } finally {
                reader.dispose();
            }
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private static final class TestProvider
            implements VideoFrameExtractorProvider {
        private Dimension requestedSize;

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public boolean canDecode(Path videoFile) {
            return true;
        }

        @Override
        public BufferedImage extractFrame(Path videoFile, Duration time) {
            return new BufferedImage(
                    5, 4, BufferedImage.TYPE_INT_ARGB_PRE);
        }

        @Override
        public BufferedImage extractFrame(
                Path videoFile, Duration time, Dimension renderSize) {
            requestedSize = new Dimension(renderSize);
            return new BufferedImage(
                    renderSize.width,
                    renderSize.height,
                    BufferedImage.TYPE_INT_ARGB_PRE);
        }

        @Override
        public boolean supportsRenderSizeReduction() {
            return true;
        }

        @Override
        public VideoInfo getInfo(Path videoFile) {
            return new VideoInfo(
                    5, 4, Duration.ofSeconds(1), "test", 1.0);
        }

        @Override
        public String id() {
            return "test";
        }

        @Override
        public RoutingBackend.Kind kind() {
            return RoutingBackend.Kind.PORTABLE;
        }
    }

    private static final class HugeProvider
            implements VideoFrameExtractorProvider {
        private boolean extracted;

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public boolean canDecode(Path videoFile) {
            return true;
        }

        @Override
        public BufferedImage extractFrame(Path videoFile, Duration time) {
            extracted = true;
            throw new AssertionError("full frame must not be extracted");
        }

        @Override
        public VideoInfo getInfo(Path videoFile) {
            return new VideoInfo(
                    20_000, 20_000, Duration.ofSeconds(1), "test", 1.0);
        }

        @Override
        public String id() {
            return "huge-test";
        }

        @Override
        public RoutingBackend.Kind kind() {
            return RoutingBackend.Kind.PORTABLE;
        }
    }
}
