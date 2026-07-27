package io.github.ghosthack.imageio.video;

import io.github.ghosthack.imageio.common.RoutingBackend;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;

/**
 * SPI for platform-specific video frame extraction backends.
 * <p>
 * Implementations are discovered via {@link java.util.ServiceLoader} and should
 * be registered in
 * {@code META-INF/services/io.github.ghosthack.imageio.video.VideoFrameExtractorProvider}.
 */
public interface VideoFrameExtractorProvider extends RoutingBackend {

    /** Returns {@code true} if this backend is available on the current platform. */
    boolean isAvailable();

    /**
     * Extracts a single frame at the given time.
     *
     * @param videoFile path to the video file
     * @param time      target time from the start
     * @return decoded frame
     * @throws IOException on decode failure
     */
    BufferedImage extractFrame(Path videoFile, Duration time) throws IOException;

    /**
     * Returns video metadata without decoding frames.
     *
     * @param videoFile path to the video file
     * @return video metadata
     * @throws IOException on read failure
     */
    VideoInfo getInfo(Path videoFile) throws IOException;

    /**
     * Declares the video containers this backend may decode.
     */
    default Set<String> formats() {
        return Set.of("mp4", "mov", "m4v", "webm", "mkv", "avi", "wmv", "3gp");
    }

    /**
     * Lightweight, input-specific capability probe.
     */
    default boolean canDecode(Path videoFile) {
        try {
            VideoInfo info = getInfo(videoFile);
            return info.width() > 0 && info.height() > 0;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }
}
