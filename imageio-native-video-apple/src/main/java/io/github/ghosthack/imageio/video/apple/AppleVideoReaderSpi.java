package io.github.ghosthack.imageio.video.apple;

import io.github.ghosthack.imageio.video.NativeVideoReader;
import io.github.ghosthack.imageio.video.NativeVideoReaderSpi;

import javax.imageio.ImageReader;
import java.util.Locale;

/**
 * ImageIO Service Provider for video poster frames on macOS.
 * <p>
 * Delegates to {@link AppleVideoFrameExtractor} (AVFoundation via Panama).
 * Registered via {@code META-INF/services/javax.imageio.spi.ImageReaderSpi}.
 */
public class AppleVideoReaderSpi extends NativeVideoReaderSpi {

    private static final boolean IS_MACOS = System.getProperty("os.name", "")
            .toLowerCase(Locale.ROOT).contains("mac");

    public AppleVideoReaderSpi() {
        super(NativeVideoReader.class.getName());
    }

    @Override
    protected boolean isBackendAvailable() {
        return IS_MACOS;
    }

    @Override
    public ImageReader createReaderInstance(Object extension) {
        return new NativeVideoReader(this, new AppleVideoFrameExtractor());
    }

    @Override
    public String getDescription(Locale locale) {
        return "Apple AVFoundation video poster frame reader (via Panama)";
    }
}
