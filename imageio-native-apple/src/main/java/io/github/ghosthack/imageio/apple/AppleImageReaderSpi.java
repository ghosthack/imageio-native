package io.github.ghosthack.imageio.apple;

import javax.imageio.ImageReader;
import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.stream.ImageInputStream;
import java.io.IOException;
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
 * {@code imageio.native.formats} — see {@link FormatRegistry} for details.  The
 * default ({@code supplemental}) claims only formats that Java's built-in
 * ImageIO does not already handle.
 */
public class AppleImageReaderSpi extends ImageReaderSpi {

    private static final String VENDOR  = "imageio-native";
    private static final String VERSION = "1.0";

    /**
     * Constructs the SPI with format metadata derived from {@link FormatRegistry}.
     * If {@code imageio.native.formats=none}, the SPI still loads but {@link #canDecodeInput}
     * always returns {@code false}.
     */
    public AppleImageReaderSpi() {
        super(VENDOR, VERSION,
                FormatRegistry.activeFormatNames(),
                FormatRegistry.activeSuffixes(),
                FormatRegistry.activeMimeTypes(),
                AppleImageReader.class.getName(),
                new Class<?>[]{ImageInputStream.class},
                null,   // writerSpiNames
                false, null, null, null, null,
                false, null, null, null, null);
    }

    /**
     * Probes whether Apple's CGImageSource can decode the input.
     * <p>
     * In {@code supplemental} mode (the default), the probe first rejects
     * formats Java can handle natively (JPEG, PNG, GIF, BMP, TIFF) via fast
     * magic-byte checks, then asks CGImageSource for everything else.
     * <p>
     * In {@code all} mode, CGImageSource is consulted for every input.
     */
    @Override
    public boolean canDecodeInput(Object source) throws IOException {
        if (!AppleNative.IS_MACOS) return false;
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

            // Ask CGImageSource if it recognises this data
            return AppleNative.canDecode(header, n);
        } finally {
            stream.reset();
        }
    }

    @Override
    public ImageReader createReaderInstance(Object extension) {
        return new AppleImageReader(this);
    }

    @Override
    public String getDescription(Locale locale) {
        return "Apple ImageIO universal image reader (via Panama) [" + System.getProperty(FormatRegistry.PROPERTY, "supplemental") + "]";
    }
}
