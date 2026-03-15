package io.github.ghosthack.imageio.video.windows;

import io.github.ghosthack.imageio.video.VideoInfo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * End-to-end tests for the Windows Media Foundation video frame extractor.
 * <p>
 * All tests are gated by {@code @EnabledOnOs(OS.WINDOWS)} so the module
 * compiles and "passes" on macOS/Linux CI (tests are simply skipped).
 * <p>
 * Tests use a 3-second MP4 test fixture from the video-common test-jar.
 */
@EnabledOnOs(OS.WINDOWS)
class WindowsVideoFrameExtractorTest {

    private static final Path TEST_VIDEO_MP4 = testResource("test-video-3s.mp4");

    private final WindowsVideoFrameExtractor extractor = new WindowsVideoFrameExtractor();

    /**
     * Resolves a test resource from the classpath to a filesystem Path.
     * Returns {@code null} if the resource is not found (test will be skipped).
     */
    private static Path testResource(String name) {
        var url = WindowsVideoFrameExtractorTest.class.getClassLoader().getResource(name);
        if (url == null) return null;
        try {
            return Path.of(url.toURI());
        } catch (Exception e) {
            return null;
        }
    }

    private void assumeCanDecode() {
        assumeTrue(extractor.isAvailable(), "Windows video backend not available");
        assumeTrue(TEST_VIDEO_MP4 != null, "Test fixture test-video-3s.mp4 not found");
    }

    // ── isAvailable ─────────────────────────────────────────────────────

    @Test
    void isAvailableOnWindows() {
        // TODO: change to assertTrue once extractFrame is fully implemented
        assertFalse(extractor.isAvailable());
    }

    // ── extractFrame ────────────────────────────────────────────────────

    @Test
    void extractFrameAtZero() throws IOException {
        assumeCanDecode();
        BufferedImage frame = extractor.extractFrame(TEST_VIDEO_MP4, Duration.ZERO);
        assertNotNull(frame, "Frame at t=0 should not be null");
        assertTrue(frame.getWidth() > 0, "Width should be > 0");
        assertTrue(frame.getHeight() > 0, "Height should be > 0");
    }

    @Test
    void extractFrameAtOneSecond() throws IOException {
        assumeCanDecode();
        BufferedImage frame = extractor.extractFrame(TEST_VIDEO_MP4, Duration.ofSeconds(1));
        assertNotNull(frame, "Frame at t=1s should not be null");
        assertTrue(frame.getWidth() > 0, "Width should be > 0");
        assertTrue(frame.getHeight() > 0, "Height should be > 0");
    }

    @Test
    void extractFrameAtEnd() throws IOException {
        assumeCanDecode();
        // Request frame near the end of the 3-second video
        BufferedImage frame = extractor.extractFrame(TEST_VIDEO_MP4, Duration.ofMillis(2900));
        assertNotNull(frame, "Frame near end should not be null");
        assertTrue(frame.getWidth() > 0, "Width should be > 0");
        assertTrue(frame.getHeight() > 0, "Height should be > 0");
    }

    @Test
    void extractFrameNullPathThrows() {
        assertThrows(Exception.class,
                () -> extractor.extractFrame(null, Duration.ZERO));
    }

    // ── getInfo ─────────────────────────────────────────────────────────

    @Test
    void getInfoReturnsDimensions() throws IOException {
        assumeCanDecode();
        VideoInfo info = extractor.getInfo(TEST_VIDEO_MP4);
        assertNotNull(info, "VideoInfo should not be null");
        assertTrue(info.width() > 0, "Width should be > 0");
        assertTrue(info.height() > 0, "Height should be > 0");
    }

    @Test
    void getInfoReturnsFrameRate() throws IOException {
        assumeCanDecode();
        VideoInfo info = extractor.getInfo(TEST_VIDEO_MP4);
        assertTrue(info.frameRate() > 0, "Frame rate should be > 0");
    }

    @Test
    void getInfoNullPathThrows() {
        assertThrows(Exception.class,
                () -> extractor.getInfo(null));
    }
}
