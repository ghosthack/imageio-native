package io.github.ghosthack.imageio.video;

import io.github.ghosthack.imageio.common.RoutingBackend;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class VideoRoutingTest {

    private static final Path INPUT = Path.of("video.mp4");

    @Test
    void rejectsBackendThatCanReadMetadataButCannotOpenDecoder() {
        MetadataOnlyProvider metadataOnly = new MetadataOnlyProvider();

        VideoRouting.Decision decision =
                VideoRouting.select(INPUT, "mp4", List.of(metadataOnly));

        assertNull(decision);
    }

    @Test
    void selectsBackendWhoseInputSpecificProbeSucceeds() {
        TestProvider unsupported =
                new TestProvider("unsupported", true, true, false);
        TestProvider supported =
                new TestProvider("supported", true, true, true);

        VideoRouting.Decision decision = VideoRouting.select(
                INPUT, "mp4", List.of(unsupported, supported));

        assertEquals(supported, decision.backend());
        assertEquals(1, unsupported.probeCount);
        assertEquals(1, supported.probeCount);
    }

    @Test
    void doesNotProbeUnavailableOrWrongContainerBackend() {
        TestProvider unavailable =
                new TestProvider("unavailable", false, true, true);
        TestProvider wrongContainer =
                new TestProvider("wrong-container", true, false, true);

        VideoRouting.Decision decision = VideoRouting.select(
                INPUT, "mp4", List.of(unavailable, wrongContainer));

        assertNull(decision);
        assertEquals(0, unavailable.probeCount);
        assertEquals(0, wrongContainer.probeCount);
    }

    private static final class TestProvider
            implements VideoFrameExtractorProvider {
        private final String id;
        private final boolean available;
        private final boolean advertisesMp4;
        private final boolean decodable;
        private int probeCount;

        private TestProvider(
                String id,
                boolean available,
                boolean advertisesMp4,
                boolean decodable) {
            this.id = id;
            this.available = available;
            this.advertisesMp4 = advertisesMp4;
            this.decodable = decodable;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public RoutingBackend.Kind kind() {
            return RoutingBackend.Kind.PORTABLE;
        }

        @Override
        public boolean isAvailable() {
            return available;
        }

        @Override
        public Set<String> formats() {
            return advertisesMp4 ? Set.of("mp4") : Set.of("mov");
        }

        @Override
        public boolean canDecode(Path videoFile) {
            probeCount++;
            return decodable;
        }

        @Override
        public BufferedImage extractFrame(Path videoFile, Duration time) {
            throw new UnsupportedOperationException();
        }

        @Override
        public VideoInfo getInfo(Path videoFile) {
            return new VideoInfo(
                    16, 16, Duration.ofSeconds(1), "test", 1.0);
        }
    }

    private static final class MetadataOnlyProvider
            implements VideoFrameExtractorProvider {

        @Override
        public String id() {
            return "metadata-only";
        }

        @Override
        public RoutingBackend.Kind kind() {
            return RoutingBackend.Kind.PORTABLE;
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public BufferedImage extractFrame(Path videoFile, Duration time) {
            throw new UnsupportedOperationException();
        }

        @Override
        public VideoInfo getInfo(Path videoFile) {
            return new VideoInfo(
                    16, 16, Duration.ofSeconds(1), "test", 1.0);
        }
    }
}
