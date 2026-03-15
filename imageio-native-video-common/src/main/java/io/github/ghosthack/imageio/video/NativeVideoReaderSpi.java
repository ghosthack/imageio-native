package io.github.ghosthack.imageio.video;

import io.github.ghosthack.imageio.common.BackendPriority;

import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.spi.ServiceRegistry;
import javax.imageio.stream.ImageInputStream;
import java.io.IOException;
import java.util.Iterator;

/**
 * Shared {@link ImageReaderSpi} base for video poster-frame readers.
 * <p>
 * Mirrors the architecture of
 * {@link io.github.ghosthack.imageio.common.NativeImageReaderSpi} for still
 * images.  Subclasses supply an availability check and a reader factory;
 * this class provides the complete {@code canDecodeInput} logic including
 * {@link VideoFormatDetector} probing and {@link BackendPriority} checks.
 * <p>
 * Each video backend registers its own SPI subclass, which creates a
 * {@link NativeVideoReader} wrapping the backend's
 * {@link VideoFrameExtractorProvider}.
 */
public abstract class NativeVideoReaderSpi extends ImageReaderSpi {

    private static final String VENDOR  = "imageio-native";
    private static final String VERSION = "1.0";

    /** Package prefix used to identify other imageio-native video SPIs for ordering. */
    private static final String OUR_PACKAGE = "io.github.ghosthack.imageio";

    protected NativeVideoReaderSpi(String readerClassName) {
        super(VENDOR, VERSION,
                VideoFormatRegistry.FORMAT_NAMES,
                VideoFormatRegistry.SUFFIXES,
                VideoFormatRegistry.MIME_TYPES,
                readerClassName,
                new Class<?>[]{ImageInputStream.class},
                null,   // writerSpiNames
                false, null, null, null, null,
                false, null, null, null, null);
    }

    // ── SPI lifecycle ─────────────────────────────────────────────────

    /**
     * Called by the {@link javax.imageio.spi.IIORegistry} when this provider
     * is first registered.
     * <ul>
     *   <li>If the backend is not available, deregisters immediately.</li>
     *   <li>Otherwise, uses {@link BackendPriority} to set ordering against
     *       other imageio-native video SPIs.</li>
     * </ul>
     */
    @SuppressWarnings("unchecked")
    @Override
    public void onRegistration(ServiceRegistry registry, Class<?> category) {
        Class<ImageReaderSpi> spiClass = (Class<ImageReaderSpi>) category;

        if (!isBackendAvailable()) {
            registry.deregisterServiceProvider(this, spiClass);
            return;
        }

        int myPriority = BackendPriority.priority(backendName());
        Iterator<ImageReaderSpi> others = registry.getServiceProviders(spiClass, true);
        while (others.hasNext()) {
            ImageReaderSpi other = others.next();
            if (other == this) continue;
            if (!other.getClass().getName().startsWith(OUR_PACKAGE)) continue;
            if (!(other instanceof NativeVideoReaderSpi otherVideo)) continue;

            int otherPriority = BackendPriority.priority(otherVideo.backendName());
            if (myPriority < otherPriority) {
                registry.setOrdering(spiClass, this, other);
            } else if (myPriority > otherPriority) {
                registry.setOrdering(spiClass, other, this);
            }
        }
    }

    // ── Hooks ───────────────────────────────────────────────────────────

    /**
     * Returns {@code true} if the video backend is available (platform check
     * or library check).
     */
    protected abstract boolean isBackendAvailable();

    /**
     * Returns the backend name for {@link BackendPriority} ordering.
     * Default is {@code "native"} for platform-native backends.
     */
    protected String backendName() {
        return "native";
    }

    // ── canDecodeInput ──────────────────────────────────────────────────

    @Override
    public boolean canDecodeInput(Object source) throws IOException {
        if (!(source instanceof PathAwareImageInputStream stream)) return false;
        if (!isBackendAvailable()) return false;
        return VideoFormatDetector.isVideoFormat(stream);
    }
}
