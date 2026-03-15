package io.github.ghosthack.imageio.video;

import io.github.ghosthack.imageio.common.BackendPriority;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.ServiceLoader;

/**
 * Extracts still images from video files using platform-native media APIs.
 * <p>
 * macOS uses AVAssetImageGenerator (via AVFoundation Objective-C runtime bridge).
 * Windows uses IShellItemImageFactory for thumbnails and IMFSourceReader for
 * time-based frame extraction (via Media Foundation COM APIs).
 * <p>
 * All methods accept a {@link Path} to the video file — video data is never
 * loaded entirely into memory.
 *
 * <pre>{@code
 * // Quick thumbnail
 * BufferedImage thumb = VideoFrameExtractor.extractThumbnail(Path.of("video.mp4"));
 *
 * // Frame at a specific time
 * BufferedImage frame = VideoFrameExtractor.extractFrame(
 *         Path.of("video.mp4"), Duration.ofSeconds(30));
 *
 * // 5 evenly-spaced frames
 * List<BufferedImage> frames = VideoFrameExtractor.extractFrames(
 *         Path.of("video.mp4"), 5);
 *
 * // Metadata only
 * VideoInfo info = VideoFrameExtractor.getInfo(Path.of("video.mp4"));
 * }</pre>
 */
public final class VideoFrameExtractor {

    private VideoFrameExtractor() {}

    // ── Platform backend discovery ──────────────────────────────────────

    /** Cached provider; {@code null} until first lookup. */
    private static volatile VideoFrameExtractorProvider provider;

    /** Sentinel indicating that discovery ran but found no backend. */
    private static volatile boolean discoveryDone;

    /**
     * Discovers and returns the highest-priority available provider, or
     * {@code null} if none is available.
     * <p>
     * Collects all available providers via {@link ServiceLoader}, sorts them
     * by {@link BackendPriority}, and caches the winner.
     */
    private static VideoFrameExtractorProvider providerOrNull() {
        VideoFrameExtractorProvider p = provider;
        if (p != null) return p;
        if (discoveryDone) return null;
        synchronized (VideoFrameExtractor.class) {
            if (provider != null) return provider;
            if (discoveryDone) return null;

            // Collect all available providers
            List<VideoFrameExtractorProvider> available = new ArrayList<>();
            for (VideoFrameExtractorProvider spi :
                    ServiceLoader.load(VideoFrameExtractorProvider.class)) {
                if (spi.isAvailable()) {
                    available.add(spi);
                }
            }

            if (!available.isEmpty()) {
                // Sort by BackendPriority (lowest priority number = highest priority)
                available.sort(Comparator.comparingInt(
                        spi -> BackendPriority.priority(spi.backendName())));
                provider = available.getFirst();
                return provider;
            }

            discoveryDone = true;
            return null;
        }
    }

    /**
     * Returns the platform provider, throwing if none is available.
     */
    private static VideoFrameExtractorProvider provider() {
        VideoFrameExtractorProvider p = providerOrNull();
        if (p == null)
            throw new UnsupportedOperationException(
                    "No video frame extraction backend for OS: " + System.getProperty("os.name"));
        return p;
    }

    // ── Public API ──────────────────────────────────────────────────────

    /**
     * Returns {@code true} if a video frame extraction backend is available
     * for the current platform.
     */
    public static boolean isAvailable() {
        return providerOrNull() != null;
    }

    /**
     * Extracts the poster frame (representative frame at or near t=0).
     *
     * @param videoFile path to the video file
     * @return the poster frame as a BufferedImage
     * @throws IOException if the file cannot be read or the format is unsupported
     */
    public static BufferedImage extractThumbnail(Path videoFile) throws IOException {
        return provider().extractFrame(videoFile, Duration.ZERO);
    }

    /**
     * Extracts a single frame at the specified time position.
     *
     * @param videoFile path to the video file
     * @param time      target position from the start of the video
     * @return the decoded frame as a BufferedImage
     * @throws IOException if the file cannot be read or the format is unsupported
     */
    public static BufferedImage extractFrame(Path videoFile, Duration time) throws IOException {
        return provider().extractFrame(videoFile, time);
    }

    /**
     * Extracts {@code count} evenly-spaced frames across the video duration.
     *
     * @param videoFile path to the video file
     * @param count     number of frames to extract
     * @return list of decoded frames, ordered by time
     * @throws IOException if the file cannot be read or the format is unsupported
     */
    public static List<BufferedImage> extractFrames(Path videoFile, int count) throws IOException {
        if (count <= 0) throw new IllegalArgumentException("count must be > 0");
        VideoInfo info = provider().getInfo(videoFile);
        long totalMs = info.duration().toMillis();
        List<BufferedImage> frames = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            long ms = (count == 1) ? 0 : (totalMs * i) / (count - 1);
            frames.add(provider().extractFrame(videoFile, Duration.ofMillis(ms)));
        }
        return frames;
    }

    /**
     * Returns video metadata (dimensions, duration, codec, frame rate)
     * without decoding any frames.
     *
     * @param videoFile path to the video file
     * @return video metadata
     * @throws IOException if the file cannot be read or the format is unsupported
     */
    public static VideoInfo getInfo(Path videoFile) throws IOException {
        return provider().getInfo(videoFile);
    }
}
