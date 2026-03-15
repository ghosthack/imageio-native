package io.github.ghosthack.imageio.magick;

import io.github.ghosthack.imageio.common.NativeImageReaderSpi;

import javax.imageio.ImageReader;
import java.util.Locale;

/**
 * ImageIO Service Provider backed by ImageMagick 7 (MagickWand API).
 * <p>
 * Claims image formats in the {@link FormatRegistry} that ImageMagick can
 * decode, subject to supplemental-mode filtering and backend priority.
 * <p>
 * This SPI loads on all platforms but {@link #canDecodeInput} returns
 * {@code false} if ImageMagick 7 is not installed.
 */
public class MagickImageReaderSpi extends NativeImageReaderSpi {

    public MagickImageReaderSpi() {
        super(MagickImageReader.class.getName(), FormatRegistry.INSTANCE);
    }

    @Override
    protected boolean isOsSupported() {
        return MagickNative.isAvailable();
    }

    @Override
    protected boolean nativeCanDecode(byte[] header, int len) {
        return MagickNative.canDecode(header, len);
    }

    @Override
    protected String backendName() {
        return "magick";
    }

    @Override
    public ImageReader createReaderInstance(Object extension) {
        return new MagickImageReader(this);
    }

    @Override
    public String getDescription(Locale locale) {
        return "ImageMagick 7 image reader (via Panama) ["
                + System.getProperty(FormatRegistry.PROPERTY, "supplemental") + "]";
    }
}
