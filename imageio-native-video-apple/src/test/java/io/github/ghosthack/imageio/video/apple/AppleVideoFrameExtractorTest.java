package io.github.ghosthack.imageio.video.apple;

import io.github.ghosthack.imageio.common.TestPixels;
import io.github.ghosthack.imageio.video.VideoFrameExtractor;
import io.github.ghosthack.imageio.video.VideoInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link AppleVideoFrameExtractor}.
 * <p>
 * Uses the 16x16 test video (3s, 1fps): Red at 0s, Green at 1s, Blue at 2s.
 * H.264 colour shift is expected — tolerance of 50 is used.
 * <p>
 * Test resources live inside the video-common test-jar, so they are extracted
 * to a temporary directory before use (AVFoundation needs real file paths).
 */
@EnabledOnOs(OS.MAC)
class AppleVideoFrameExtractorTest {

    private static final int TOLERANCE = 70;

    /** Red in ARGB */
    private static final int RED = 0xFFFF0000;
    /** Green in ARGB */
    private static final int GREEN = 0xFF00FF00;
    /** Blue in ARGB */
    private static final int BLUE = 0xFF0000FF;

    @TempDir
    Path tempDir;

    /**
     * Extracts a classpath resource to a real file in tempDir.
     * AVFoundation requires a real file path, not a jar: URI.
     */
    private Path extractResource(String name) throws IOException {
        Path target = tempDir.resolve(name);
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(name)) {
            assertNotNull(in, "Test resource not found: " + name);
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return target;
    }

    private Path testVideo() throws IOException {
        return extractResource("test-video-3s.mp4");
    }

    private Path testVideoMov() throws IOException {
        return extractResource("test-video-3s.mov");
    }



    @Test
    void extractFrameAtZero() throws Exception {
        AppleVideoFrameExtractor extractor = new AppleVideoFrameExtractor();
        BufferedImage frame = extractor.extractFrame(testVideo(), Duration.ZERO);
        assertNotNull(frame);
        assertEquals(16, frame.getWidth());
        assertEquals(16, frame.getHeight());
        // Centre pixel should be approximately red
        int pixel = frame.getRGB(8, 8);
        TestPixels.assertColourClose("Red frame at t=0", RED, pixel, TOLERANCE);
    }

    @Test
    void extractFrameAtOneSecond() throws Exception {
        AppleVideoFrameExtractor extractor = new AppleVideoFrameExtractor();
        BufferedImage frame = extractor.extractFrame(testVideo(), Duration.ofSeconds(1));
        assertNotNull(frame);
        assertEquals(16, frame.getWidth());
        assertEquals(16, frame.getHeight());
        int pixel = frame.getRGB(8, 8);
        TestPixels.assertColourClose("Green frame at t=1s", GREEN, pixel, TOLERANCE);
    }

    @Test
    void extractFrameAtTwoSeconds() throws Exception {
        AppleVideoFrameExtractor extractor = new AppleVideoFrameExtractor();
        BufferedImage frame = extractor.extractFrame(testVideo(), Duration.ofSeconds(2));
        assertNotNull(frame);
        assertEquals(16, frame.getWidth());
        assertEquals(16, frame.getHeight());
        int pixel = frame.getRGB(8, 8);
        TestPixels.assertColourClose("Blue frame at t=2s", BLUE, pixel, TOLERANCE);
    }

    @Test
    void getInfoReturnsCorrectMetadata() throws Exception {
        AppleVideoFrameExtractor extractor = new AppleVideoFrameExtractor();
        VideoInfo info = extractor.getInfo(testVideo());
        assertNotNull(info);
        assertEquals(16, info.width());
        assertEquals(16, info.height());
        // Duration should be approximately 3 seconds
        long durationMs = info.duration().toMillis();
        assertTrue(durationMs >= 2500 && durationMs <= 3500,
                "Expected duration ~3s but got " + durationMs + "ms");
        // Frame rate should be approximately 1 fps
        assertTrue(info.frameRate() > 0.5 && info.frameRate() < 1.5,
                "Expected frame rate ~1fps but got " + info.frameRate());
    }

    @Test
    void extractThumbnailViaFacade() throws Exception {
        BufferedImage thumb = VideoFrameExtractor.extractThumbnail(testVideo());
        assertNotNull(thumb);
        assertEquals(16, thumb.getWidth());
        assertEquals(16, thumb.getHeight());
    }

    // ── B-frame fixture: non-zero start_pts, reproduces t=0 NULL bug ──

    @Test
    void extractThumbnailFromBframeVideo() throws Exception {
        // This fixture has B-frames and start_pts=1014 (~66ms).
        // copyCGImageAtTime returns NULL at t=0 with zero tolerance.
        Path video = extractResource("test-video-3s-bframes.mp4");
        BufferedImage thumb = VideoFrameExtractor.extractThumbnail(video);
        assertNotNull(thumb, "Thumbnail should succeed for B-frame video with non-zero start_pts");
        assertEquals(16, thumb.getWidth());
        assertEquals(16, thumb.getHeight());
    }

    @Test
    void extractFrameAtZeroFromBframeVideo() throws Exception {
        AppleVideoFrameExtractor extractor = new AppleVideoFrameExtractor();
        Path video = extractResource("test-video-3s-bframes.mp4");
        BufferedImage frame = extractor.extractFrame(video, Duration.ZERO);
        assertNotNull(frame, "Frame at t=0 should succeed with tolerance for B-frame video");
        assertEquals(16, frame.getWidth());
        assertEquals(16, frame.getHeight());
    }

    @Test
    void extractFrameFromMovFile() throws Exception {
        AppleVideoFrameExtractor extractor = new AppleVideoFrameExtractor();
        BufferedImage frame = extractor.extractFrame(testVideoMov(), Duration.ZERO);
        assertNotNull(frame);
        assertEquals(16, frame.getWidth());
        assertEquals(16, frame.getHeight());
        int pixel = frame.getRGB(8, 8);
        TestPixels.assertColourClose("Red frame at t=0 (MOV)", RED, pixel, TOLERANCE);
    }

}
