package io.github.ghosthack.imageio.video.windows;

import io.github.ghosthack.imageio.video.NativeVideoReader;
import io.github.ghosthack.imageio.video.NativeVideoReaderSpi;

import javax.imageio.ImageReader;
import java.util.Locale;

/**
 * ImageIO Service Provider for video poster frames on Windows.
 * <p>
 * Delegates to {@link WindowsVideoFrameExtractor} (Media Foundation via Panama).
 * Registered via {@code META-INF/services/javax.imageio.spi.ImageReaderSpi}.
 */
public class WindowsVideoReaderSpi extends NativeVideoReaderSpi {

    private static final boolean IS_WINDOWS = System.getProperty("os.name", "")
            .toLowerCase(Locale.ROOT).startsWith("win");

    public WindowsVideoReaderSpi() {
        super(NativeVideoReader.class.getName());
    }

    @Override
    protected boolean isBackendAvailable() {
        return IS_WINDOWS && new WindowsVideoFrameExtractor().isAvailable();
    }

    @Override
    public ImageReader createReaderInstance(Object extension) {
        return new NativeVideoReader(this, new WindowsVideoFrameExtractor());
    }

    @Override
    public String getDescription(Locale locale) {
        return "Windows Media Foundation video poster frame reader (via Panama)";
    }
}
