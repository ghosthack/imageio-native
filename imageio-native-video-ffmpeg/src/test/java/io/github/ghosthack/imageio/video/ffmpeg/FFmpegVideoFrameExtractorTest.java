package io.github.ghosthack.imageio.video.ffmpeg;

import io.github.ghosthack.imageio.video.VideoInfo;
import org.junit.jupiter.api.Test;

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
 * All tests are skipped if FFmpeg is not installed or the version is not supported.
 */
class FFmpegVideoFrameExtractorTest {

    private final FFmpegVideoFrameExtractor extractor = new FFmpegVideoFrameExtractor();

    private void assumeFFmpeg() {
        assumeTrue(FFmpegNative.isAvailable(), "FFmpeg not available — skipping");
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
    void isAvailableWhenInstalled() {
        System.out.println("FFmpegNative.isAvailable() = " + FFmpegNative.isAvailable());
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
    }

    @Test
    void extractFrameAtOneSecond() throws Exception {
        assumeFFmpeg();
        BufferedImage frame = extractor.extractFrame(
                extractResource("test-video-3s.mp4"), Duration.ofSeconds(1));
        assertNotNull(frame);
        assertEquals(16, frame.getWidth());
        assertEquals(16, frame.getHeight());
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
}
