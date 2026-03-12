package io.github.ghosthack.imageio.windows;

import io.github.ghosthack.imageio.common.NativeImageReaderSpi;

import javax.imageio.ImageReader;
import java.util.Locale;

/**
 * Universal ImageIO Service Provider that delegates format detection to the
 * Windows Imaging Component (WIC) via Project Panama.
 * <p>
 * Instead of one SPI per format, this single provider covers <em>every</em>
 * still-image format WIC can decode (HEIC, AVIF, WEBP, JPEG-XR, DDS, camera
 * RAW, and all standard formats).
 * <p>
 * Which formats are actually claimed is controlled by the system property
 * {@code imageio.native.formats} — see
 * {@link io.github.ghosthack.imageio.common.FormatRegistry} for details.
 * The default ({@code supplemental}) claims only formats that Java's built-in
 * ImageIO does not already handle.
 * <p>
 * This SPI loads on all platforms (so the module compiles everywhere), but
 * {@link #canDecodeInput} always returns {@code false} on non-Windows systems.
 */
public class WicImageReaderSpi extends NativeImageReaderSpi {

    public WicImageReaderSpi() {
        super(WicImageReader.class.getName(), FormatRegistry.INSTANCE);
    }

    @Override
    protected boolean isOsSupported() {
        return WicNative.IS_WINDOWS;
    }

    @Override
    protected boolean nativeCanDecode(byte[] header, int len) {
        return WicNative.canDecode(header, len);
    }

    @Override
    public ImageReader createReaderInstance(Object extension) {
        return new WicImageReader(this);
    }

    @Override
    public String getDescription(Locale locale) {
        return "Windows Imaging Component (WIC) universal image reader (via Panama) ["
                + System.getProperty(FormatRegistry.PROPERTY, "supplemental") + "]";
    }
}
