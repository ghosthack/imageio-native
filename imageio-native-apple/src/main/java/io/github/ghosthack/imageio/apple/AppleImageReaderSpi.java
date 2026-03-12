package io.github.ghosthack.imageio.apple;

import io.github.ghosthack.imageio.common.NativeImageReaderSpi;

import javax.imageio.ImageReader;
import java.util.Locale;

/**
 * Universal ImageIO Service Provider that delegates format detection to
 * Apple's {@code CGImageSource} via Project Panama.
 * <p>
 * Instead of one SPI per format, this single provider covers <em>every</em>
 * still-image format Apple's ImageIO framework can decode (62+ formats
 * including HEIC, AVIF, WEBP, JPEG 2000, camera RAW, PSD, EXR, …).
 * <p>
 * Which formats are actually claimed is controlled by the system property
 * {@code imageio.native.formats} — see
 * {@link io.github.ghosthack.imageio.common.FormatRegistry} for details.
 * The default ({@code supplemental}) claims only formats that Java's built-in
 * ImageIO does not already handle.
 */
public class AppleImageReaderSpi extends NativeImageReaderSpi {

    public AppleImageReaderSpi() {
        super(AppleImageReader.class.getName(), FormatRegistry.INSTANCE);
    }

    @Override
    protected boolean isOsSupported() {
        return AppleNative.IS_MACOS;
    }

    @Override
    protected boolean nativeCanDecode(byte[] header, int len) {
        return AppleNative.canDecode(header, len);
    }

    @Override
    public ImageReader createReaderInstance(Object extension) {
        return new AppleImageReader(this);
    }

    @Override
    public String getDescription(Locale locale) {
        return "Apple ImageIO universal image reader (via Panama) ["
                + System.getProperty(FormatRegistry.PROPERTY, "supplemental") + "]";
    }
}
