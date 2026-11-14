package io.github.ghosthack.imageio.video.apple.internal;

import io.github.ghosthack.imageio.common.RoutingBackend;
import io.github.ghosthack.imageio.video.apple.AppleVideoFrameExtractor;
import io.github.ghosthack.imageio.video.VideoFrameExtractorProvider;
import io.github.ghosthack.imageio.video.VideoInfo;

import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;

/**
 * Lightweight Apple video service that avoids initializing the native FFM
 * bridge when service discovery runs on another operating system.
 */
public final class AppleVideoBackend implements VideoFrameExtractorProvider {

    private static final boolean IS_MACOS = System.getProperty("os.name", "")
            .toLowerCase(Locale.ROOT).contains("mac");

    private static final Set<String> FORMATS = Set.of("mp4", "mov", "m4v", "3gp");

    @Override
    public String id() {
        return "apple";
    }

    @Override
    public RoutingBackend.Kind kind() {
        return RoutingBackend.Kind.PLATFORM_NATIVE;
    }

    @Override
    public boolean isAvailable() {
        return IS_MACOS && delegate().isAvailable();
    }

    @Override
    public Set<String> formats() {
        return FORMATS;
    }

    @Override
    public boolean canDecode(Path videoFile) {
        return IS_MACOS && delegate().canDecode(videoFile);
    }

    @Override
    public BufferedImage extractFrame(Path videoFile, Duration time)
            throws IOException {
        return delegate().extractFrame(videoFile, time);
    }

    @Override
    public BufferedImage extractFrame(
            Path videoFile, Duration time, Dimension renderSize)
            throws IOException {
        return delegate().extractFrame(videoFile, time, renderSize);
    }

    @Override
    public boolean supportsRenderSizeReduction() {
        return IS_MACOS && delegate().supportsRenderSizeReduction();
    }

    @Override
    public VideoInfo getInfo(Path videoFile) throws IOException {
        return delegate().getInfo(videoFile);
    }

    private static AppleVideoFrameExtractor delegate() {
        return Holder.INSTANCE;
    }

    private static final class Holder {
        private static final AppleVideoFrameExtractor INSTANCE =
                new AppleVideoFrameExtractor();
    }
}
