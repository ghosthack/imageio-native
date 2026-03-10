package io.github.ghosthack.imageio.windows;

import javax.imageio.ImageReader;
import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.stream.ImageInputStream;
import java.io.IOException;
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
 * {@code imageio.native.formats} — see {@link FormatRegistry} for details.  The
 * default ({@code supplemental}) claims only formats that Java's built-in
 * ImageIO does not already handle.
 * <p>
 * This SPI loads on all platforms (so the module compiles everywhere), but
 * {@link #canDecodeInput} always returns {@code false} on non-Windows systems.
 */
public class WicImageReaderSpi extends ImageReaderSpi {

    private static final String VENDOR  = "imageio-native";
    private static final String VERSION = "1.0";

    /**
     * Constructs the SPI with format metadata derived from {@link FormatRegistry}.
     * If {@code imageio.native.formats=none}, the SPI still loads but {@link #canDecodeInput}
     * always returns {@code false}.
     */
    public WicImageReaderSpi() {
        super(VENDOR, VERSION,
                FormatRegistry.activeFormatNames(),
                FormatRegistry.activeSuffixes(),
                FormatRegistry.activeMimeTypes(),
                WicImageReader.class.getName(),
                new Class<?>[]{ImageInputStream.class},
                null,   // writerSpiNames
                false, null, null, null, null,
                false, null, null, null, null);
    }

    /**
     * Probes whether WIC can decode the input.
     * <p>
     * On non-Windows platforms, always returns {@code false}.
     * <p>
     * In {@code supplemental} mode (the default), the probe first rejects
     * formats Java can handle natively (JPEG, PNG, GIF, BMP, TIFF) via fast
     * magic-byte checks, then asks WIC for everything else.
     * <p>
     * In {@code all} mode, WIC is consulted for every input.
     */
    @Override
    public boolean canDecodeInput(Object source) throws IOException {
        if (!WicNative.IS_WINDOWS) return false;
        if (!FormatRegistry.isEnabled()) return false;
        if (!(source instanceof ImageInputStream stream)) return false;

        stream.mark();
        try {
            byte[] header = new byte[4096];
            int n = stream.read(header, 0, header.length);
            if (n <= 0) return false;

            // In supplemental mode, skip formats Java already handles
            if (FormatRegistry.shouldExcludeJavaNative()
                    && FormatDetector.isJavaNativeFormat(header, n)) {
                return false;
            }

            // Ask WIC if it can create a decoder for this data
            return WicNative.canDecode(header, n);
        } finally {
            stream.reset();
        }
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
