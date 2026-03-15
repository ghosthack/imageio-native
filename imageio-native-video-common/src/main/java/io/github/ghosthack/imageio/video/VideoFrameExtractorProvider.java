package io.github.ghosthack.imageio.video;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;

/**
 * SPI for platform-specific video frame extraction backends.
 * <p>
 * Implementations are discovered via {@link java.util.ServiceLoader} and should
 * be registered in
 * {@code META-INF/services/io.github.ghosthack.imageio.video.VideoFrameExtractorProvider}.
 */
public interface VideoFrameExtractorProvider {

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
     * Returns the backend name for
     * {@link io.github.ghosthack.imageio.common.BackendPriority} ordering.
     * <p>
     * Default is {@code "native"} for platform-native backends (AVFoundation,
     * Media Foundation).  Third-party backends override this (e.g.
     * {@code "ffmpeg"}).
     *
     * @return backend name
     */
    default String backendName() {
        return "native";
    }
}
