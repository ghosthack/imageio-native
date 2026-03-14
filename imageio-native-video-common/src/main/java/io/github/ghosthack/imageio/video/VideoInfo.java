package io.github.ghosthack.imageio.video;

import java.time.Duration;

/**
 * Metadata about a video file, obtained without decoding any frames.
 *
 * @param width      frame width in pixels
 * @param height     frame height in pixels
 * @param duration   total duration of the video
 * @param codec      codec identifier (e.g. "h264", "hevc", "vp9"), or {@code null} if unknown
 * @param frameRate  frames per second, or {@code 0} if unknown
 */
public record VideoInfo(
        int width,
        int height,
        Duration duration,
        String codec,
        double frameRate
) {}
