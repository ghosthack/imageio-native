package io.github.ghosthack.imageio.video;

import io.github.ghosthack.imageio.common.ImageReadParamSupport;
import io.github.ghosthack.imageio.common.RoutingBackend;

import java.awt.Dimension;
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
     * Extracts a frame rendered at an exact size.
     * <p>
     * Backends should override this method when they can reduce native output
     * allocation. The default preserves correctness by scaling after the
     * regular extraction path.
     */
    default BufferedImage extractFrame(
            Path videoFile, Duration time, Dimension renderSize)
            throws IOException {
        return ImageReadParamSupport.renderToSize(
                extractFrame(videoFile, time), renderSize);
    }

    /**
     * Returns whether {@link #extractFrame(Path, Duration, Dimension)}
     * reduces the native output allocation rather than scaling a full frame.
     */
    default boolean supportsRenderSizeReduction() {
        return false;
    }

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
     * Input-specific decoder capability probe.
     * <p>
     * Implementations that claim inputs must verify both that the input
     * contains a video stream and that this backend can open a decoder for
     * that stream on the current machine. Reading container metadata alone is
     * not sufficient. The conservative default claims no inputs.
     *
     * @return {@code true} only when this backend can decode the input
     */
    default boolean canDecode(Path videoFile) {
        return false;
    }
}
