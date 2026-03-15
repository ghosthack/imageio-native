package io.github.ghosthack.imageio.video.ffmpeg;

import io.github.ghosthack.imageio.video.NativeVideoReader;
import io.github.ghosthack.imageio.video.NativeVideoReaderSpi;

import javax.imageio.ImageReader;
import java.util.Locale;

/**
 * ImageIO Service Provider for video poster frames via FFmpeg.
 * <p>
 * Delegates to {@link FFmpegVideoFrameExtractor} for frame extraction.
 * Registered via {@code META-INF/services/javax.imageio.spi.ImageReaderSpi}.
 */
public class FFmpegVideoReaderSpi extends NativeVideoReaderSpi {

    public FFmpegVideoReaderSpi() {
        super(NativeVideoReader.class.getName());
    }

    @Override
    protected boolean isBackendAvailable() {
        return FFmpegNative.isAvailable();
    }

    @Override
    protected String backendName() {
        return "ffmpeg";
    }

    @Override
    public ImageReader createReaderInstance(Object extension) {
        return new NativeVideoReader(this, new FFmpegVideoFrameExtractor());
    }

    @Override
    public String getDescription(Locale locale) {
        return "FFmpeg video poster frame reader (via Panama)";
    }
}
