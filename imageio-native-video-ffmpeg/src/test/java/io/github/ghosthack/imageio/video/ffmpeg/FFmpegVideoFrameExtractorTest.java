package io.github.ghosthack.imageio.video.ffmpeg;

import io.github.ghosthack.imageio.video.VideoInfo;
import org.junit.jupiter.api.Test;

import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Tests for the FFmpeg video backend.
 * Uses the video test fixtures from imageio-native-video-common's test-jar.
 * The supported platforms use the FFmpeg native libraries bundled by ffmpeg-ffm.
 */
class FFmpegVideoFrameExtractorTest {

    private final FFmpegVideoFrameExtractor extractor = new FFmpegVideoFrameExtractor();

    private void assumeFFmpeg() {
        assumeTrue(extractor.isAvailable(), "FFmpeg not available — skipping");
    }

    private Path extractResource(String name) throws IOException {
        Path tmp = Files.createTempFile("ffmpeg-test-", "-" + name);
        tmp.toFile().deleteOnExit();
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(name)) {
            assertNotNull(is, "Test fixture not found: " + name);
            Files.write(tmp, is.readAllBytes());
        }
        return tmp;
    }

    // ── isAvailable ─────────────────────────────────────────────────────

    @Test
    void bundledFFmpegIsAvailable() {
        assumeTrue(isBundledPlatform(), "No bundled FFmpeg for this platform");
        assertTrue(extractor.isAvailable(), "Bundled FFmpeg should be available");
    }

    @Test
    void capabilityProbeRequiresOpenableVideoDecoder() throws Exception {
        assumeFFmpeg();
        Path invalid = Files.createTempFile("ffmpeg-test-invalid-", ".mp4");
        invalid.toFile().deleteOnExit();
        Files.writeString(invalid, "not a decodable video");

        assertTrue(
                extractor.canDecode(
                        extractResource("test-video-3s.mp4")));
        assertFalse(extractor.canDecode(invalid));
    }

    // ── getInfo ─────────────────────────────────────────────────────────

    @Test
    void getInfoFromMp4() throws Exception {
        assumeFFmpeg();
        VideoInfo info = extractor.getInfo(extractResource("test-video-3s.mp4"));
        assertNotNull(info);
        assertEquals(16, info.width());
        assertEquals(16, info.height());
        long durationMs = info.duration().toMillis();
        assertTrue(durationMs >= 2500 && durationMs <= 3500,
                "Expected duration ~3s but got " + durationMs + "ms");
        assertTrue(info.frameRate() > 0, "Frame rate should be > 0");
    }

    // ── extractFrame ────────────────────────────────────────────────────

    @Test
    void extractFrameAtZero() throws Exception {
        assumeFFmpeg();
        BufferedImage frame = extractor.extractFrame(
                extractResource("test-video-3s.mp4"), Duration.ZERO);
        assertNotNull(frame);
        assertEquals(16, frame.getWidth());
        assertEquals(16, frame.getHeight());
        assertEquals(BufferedImage.TYPE_INT_ARGB_PRE, frame.getType());
        assertDominantColor(frame, 0, "red");
    }

    @Test
    void extractFrameAtOneSecond() throws Exception {
        assumeFFmpeg();
        BufferedImage frame = extractor.extractFrame(
                extractResource("test-video-3s.mp4"), Duration.ofSeconds(1));
        assertNotNull(frame);
        assertEquals(16, frame.getWidth());
        assertEquals(16, frame.getHeight());
        assertDominantColor(frame, 1, "green");
    }

    @Test
    void extractFrameAtRequestedRenderSize() throws Exception {
        assumeFFmpeg();
        BufferedImage frame = extractor.extractFrame(
                extractResource("test-video-3s.mp4"),
                Duration.ZERO,
                new Dimension(8, 4));

        assertEquals(8, frame.getWidth());
        assertEquals(4, frame.getHeight());
        assertEquals(BufferedImage.TYPE_INT_ARGB_PRE, frame.getType());
        assertDominantColor(frame, 0, "red");
    }

    @Test
    void extractFrameFromMov() throws Exception {
        assumeFFmpeg();
        BufferedImage frame = extractor.extractFrame(
                extractResource("test-video-3s.mov"), Duration.ZERO);
        assertNotNull(frame);
        assertEquals(16, frame.getWidth());
        assertEquals(16, frame.getHeight());
    }

    // ── B-frame fixture ─────────────────────────────────────────────────

    @Test
    void extractFrameFromBframeVideo() throws Exception {
        assumeFFmpeg();
        BufferedImage frame = extractor.extractFrame(
                extractResource("test-video-3s-bframes.mp4"), Duration.ZERO);
        assertNotNull(frame, "Frame at t=0 should succeed for B-frame video");
        assertEquals(16, frame.getWidth());
        assertEquals(16, frame.getHeight());
    }

    @Test
    void getInfoFromBframeVideo() throws Exception {
        assumeFFmpeg();
        VideoInfo info = extractor.getInfo(extractResource("test-video-3s-bframes.mp4"));
        assertNotNull(info);
        assertEquals(16, info.width());
        assertEquals(16, info.height());
        assertEquals("h264", info.codec());
    }

    private static void assertDominantColor(BufferedImage frame, int channel, String name) {
        int argb = frame.getRGB(frame.getWidth() / 2, frame.getHeight() / 2);
        int[] channels = {(argb >>> 16) & 0xFF, (argb >>> 8) & 0xFF, argb & 0xFF};
        assertTrue(channels[channel] > 200,
                "Expected dominant " + name + " channel but got 0x" + Integer.toHexString(argb));
        assertTrue(channels[(channel + 1) % 3] < 80);
        assertTrue(channels[(channel + 2) % 3] < 80);
    }

    private static boolean isBundledPlatform() {
        String os = System.getProperty("os.name", "").toLowerCase();
        String arch = System.getProperty("os.arch", "").toLowerCase();
        boolean x64 = arch.equals("amd64") || arch.equals("x86_64");
        boolean arm64 = arch.equals("aarch64") || arch.equals("arm64");
        return (os.contains("mac") && arm64)
                || (os.contains("win") && x64)
                || (os.contains("linux") && x64);
    }
}
