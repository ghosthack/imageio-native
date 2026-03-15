package io.github.ghosthack.imageio.vips;

import io.github.ghosthack.imageio.common.NativeImageReaderSpi;

import javax.imageio.ImageReader;
import java.util.Locale;

/**
 * ImageIO Service Provider backed by libvips.
 * <p>
 * Claims all image formats in the {@link FormatRegistry} that libvips can
 * decode, subject to supplemental-mode filtering (see
 * {@link io.github.ghosthack.imageio.common.FormatRegistry}) and backend
 * priority (see {@link io.github.ghosthack.imageio.common.BackendPriority}).
 * <p>
 * This SPI loads on all platforms (no OS guard), but
 * {@link #canDecodeInput} returns {@code false} if libvips is not installed.
 * <p>
 * The hardcoded format list covers common libvips-supported formats: HEIC,
 * AVIF, WebP, JPEG 2000, PDF, SVG, EXR, FITS, Netpbm, HDR, and all
 * Java-native formats.  Formats not in the list but supported by the
 * installed libvips can still be decoded via the direct API.
 */
public class VipsImageReaderSpi extends NativeImageReaderSpi {

    public VipsImageReaderSpi() {
        super(VipsImageReader.class.getName(), FormatRegistry.INSTANCE);
    }

    @Override
    protected boolean isOsSupported() {
        // libvips is cross-platform — the OS check is replaced by a library check
        return VipsNative.isAvailable();
    }

    @Override
    protected boolean nativeCanDecode(byte[] header, int len) {
        return VipsNative.canDecode(header, len);
    }

    @Override
    protected String backendName() {
        return "vips";
    }

    @Override
    public ImageReader createReaderInstance(Object extension) {
        return new VipsImageReader(this);
    }

    @Override
    public String getDescription(Locale locale) {
        return "libvips image reader (via Panama) ["
                + System.getProperty(FormatRegistry.PROPERTY, "supplemental") + "]";
    }
}
