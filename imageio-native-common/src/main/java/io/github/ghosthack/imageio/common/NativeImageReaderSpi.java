package io.github.ghosthack.imageio.common;

import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.spi.ServiceRegistry;
import javax.imageio.stream.ImageInputStream;
import java.io.IOException;
import java.util.Iterator;

/**
 * Shared {@link ImageReaderSpi} base for platform-native image decoders.
 * <p>
 * Subclasses supply three hooks — an OS guard, a native format probe, and
 * a reader factory — and this class provides the complete {@code canDecodeInput}
 * logic including supplemental-mode filtering and
 * {@linkplain BackendPriority backend priority} checks.
 * <p>
 * Which formats are claimed is controlled by the system property
 * {@code imageio.native.formats} — see {@link FormatRegistry} for details.
 * <p>
 * Which backend wins when multiple are on the classpath is controlled by the
 * system property {@code imageio.native.backend.priority} — see
 * {@link BackendPriority} for details.
 */
public abstract class NativeImageReaderSpi extends ImageReaderSpi {

    private static final String VENDOR  = "imageio-native";
    private static final String VERSION = "1.0";

    /** Package prefix used to identify other imageio-native SPIs for ordering. */
    private static final String OUR_PACKAGE = "io.github.ghosthack.imageio";

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
     * is first registered.
     * <ul>
     *   <li>If the current OS does not match the platform this SPI targets,
     *       it deregisters itself immediately.</li>
     *   <li>Otherwise, it uses {@link BackendPriority} to set ordering against
     *       other imageio-native SPIs so the consumer's preferred backend
     *       is tried first.</li>
     * </ul>
     */
    @SuppressWarnings("unchecked")
    @Override
    public void onRegistration(ServiceRegistry registry, Class<?> category) {
        Class<ImageReaderSpi> spiClass = (Class<ImageReaderSpi>) category;

        if (!isOsSupported()) {
            registry.deregisterServiceProvider(this, spiClass);
            return;
        }

        // Set ordering against other imageio-native SPIs based on BackendPriority
        int myPriority = BackendPriority.priority(backendName());
        Iterator<ImageReaderSpi> others = registry.getServiceProviders(spiClass, true);
        while (others.hasNext()) {
            ImageReaderSpi other = others.next();
            if (other == this) continue;
            if (!other.getClass().getName().startsWith(OUR_PACKAGE)) continue;
            if (!(other instanceof NativeImageReaderSpi otherNative)) continue;

            int otherPriority = BackendPriority.priority(otherNative.backendName());
            if (myPriority < otherPriority) {
                registry.setOrdering(spiClass, this, other);
            } else if (myPriority > otherPriority) {
                registry.setOrdering(spiClass, other, this);
            }
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

    /**
     * Returns the backend name for {@link BackendPriority} ordering.
     * <p>
     * The default is {@code "native"}, which covers platform-native backends
     * (Apple CGImageSource, Windows WIC).  Subclasses for third-party backends
     * (e.g. libvips, ImageMagick) should override this.
     *
     * @return backend name, e.g. {@code "native"}, {@code "vips"}, {@code "magick"}
     */
    protected String backendName() {
        return "native";
    }

    /**
     * Returns the {@link FormatRegistry} associated with this SPI.
     * Subclasses may use this to access format metadata.
     */
    protected FormatRegistry formatRegistry() {
        return registry;
    }

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
