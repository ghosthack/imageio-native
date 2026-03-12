package io.github.ghosthack.imageio.common;

import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.spi.ServiceRegistry;
import javax.imageio.stream.ImageInputStream;
import java.io.IOException;

/**
 * Shared {@link ImageReaderSpi} base for platform-native image decoders.
 * <p>
 * Subclasses supply three hooks — an OS guard, a native format probe, and
 * a reader factory — and this class provides the complete {@code canDecodeInput}
 * logic including supplemental-mode filtering.
 * <p>
 * Which formats are claimed is controlled by the system property
 * {@code imageio.native.formats} — see {@link FormatRegistry} for details.
 */
public abstract class NativeImageReaderSpi extends ImageReaderSpi {

    private static final String VENDOR  = "imageio-native";
    private static final String VERSION = "1.0";

    private final FormatRegistry registry;

    /**
     * @param readerClassName fully-qualified class name of the concrete {@link NativeImageReader}
     * @param registry        platform-specific format registry instance
     */
    protected NativeImageReaderSpi(String readerClassName, FormatRegistry registry) {
        super(VENDOR, VERSION,
                registry.activeFormatNames(),
                registry.activeSuffixes(),
                registry.activeMimeTypes(),
                readerClassName,
                new Class<?>[]{ImageInputStream.class},
                null,   // writerSpiNames
                false, null, null, null, null,
                false, null, null, null, null);
        this.registry = registry;
    }

    // ── SPI lifecycle ─────────────────────────────────────────────────

    /**
     * Called by the {@link javax.imageio.spi.IIORegistry} when this provider
     * is first registered.  If the current OS does not match the platform
     * this SPI targets, it deregisters itself immediately so that
     * {@code ImageIO.getImageReaders()} never iterates over it — avoiding
     * a wasted 4 KB header read on every decode just to return {@code false}
     * from {@link #canDecodeInput}.
     */
    @SuppressWarnings("unchecked")
    @Override
    public void onRegistration(ServiceRegistry registry, Class<?> category) {
        if (!isOsSupported()) {
            registry.deregisterServiceProvider(this,
                    (Class<ImageReaderSpi>) category);
        }
    }

    // ── Platform hooks (implemented by subclasses) ──────────────────────

    /** Returns {@code true} if this SPI's platform is the current OS. */
    protected abstract boolean isOsSupported();

    /**
     * Probes whether the platform's native decoder can handle the given header bytes.
     *
     * @param header first bytes of the image
     * @param len    number of valid bytes in {@code header}
     * @return {@code true} if the native decoder recognises the format
     */
    protected abstract boolean nativeCanDecode(byte[] header, int len);

    // ── canDecodeInput ──────────────────────────────────────────────────

    @Override
    public boolean canDecodeInput(Object source) throws IOException {
        if (!isOsSupported()) return false;
        if (!registry.isEnabled()) return false;
        if (!(source instanceof ImageInputStream stream)) return false;

        stream.mark();
        try {
            byte[] header = new byte[4096];
            int n = stream.read(header, 0, header.length);
            if (n <= 0) return false;

            if (registry.shouldExcludeJavaNative()
                    && FormatDetector.isJavaNativeFormat(header, n)) {
                return false;
            }

            return nativeCanDecode(header, n);
        } finally {
            stream.reset();
        }
    }
}
