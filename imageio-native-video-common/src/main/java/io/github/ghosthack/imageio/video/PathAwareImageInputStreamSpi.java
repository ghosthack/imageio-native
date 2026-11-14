package io.github.ghosthack.imageio.video;

import javax.imageio.spi.ImageInputStreamSpi;
import javax.imageio.spi.ServiceRegistry;
import javax.imageio.stream.ImageInputStream;
import java.io.File;
import java.io.IOException;
import java.util.Locale;

/**
 * Compatibility alias for the provider now registered by
 * {@code imageio-native-common}.
 * <p>
 * This is transparent to all existing readers (since {@link PathAwareImageInputStream}
 * extends {@link javax.imageio.stream.FileImageInputStream}) but allows video-aware
 * readers to extract the file path via {@link PathAwareImageInputStream#getPath()}.
 * <p>This alias is no longer service-registered; the shared provider is
 * registered by {@code imageio-native-common}.</p>
 *
 * @deprecated use
 * {@link io.github.ghosthack.imageio.common.PathAwareImageInputStreamSpi}
 */
@Deprecated
public class PathAwareImageInputStreamSpi extends ImageInputStreamSpi {

    public PathAwareImageInputStreamSpi() {
        super("ghosthack", "1.0", File.class);
    }

    /**
     * Ensures this SPI is ordered before any other {@code File}-based
     * {@link ImageInputStreamSpi} (in particular the JDK's built-in
     * {@code FileImageInputStream} SPI).
     * <p>
     * Without explicit ordering, the JDK's SPI may win, which would
     * produce a plain {@code FileImageInputStream} instead of a
     * {@link PathAwareImageInputStream}, breaking
     * {@link NativeVideoReader}'s ability to recover the file path.
     */
    @Override
    @SuppressWarnings("unchecked")
    public void onRegistration(ServiceRegistry registry, Class<?> category) {
        // Iterate all registered ImageInputStreamSpi providers that handle File
        var others = registry.getServiceProviders(
                (Class<ImageInputStreamSpi>) category, true);
        while (others.hasNext()) {
            ImageInputStreamSpi other = others.next();
            if (other != this && File.class.equals(other.getInputClass())) {
                // "this before other" — our SPI takes priority
                registry.setOrdering(
                        (Class<ImageInputStreamSpi>) category, this, other);
            }
        }
    }

    @Override
    public String getDescription(Locale locale) {
        return "Path-aware FileImageInputStream for video frame extraction";
    }

    @Override
    public ImageInputStream createInputStreamInstance(Object input, boolean useCache,
                                                      File cacheDir) throws IOException {
        if (input instanceof File file) {
            return new PathAwareImageInputStream(file);
        }
        throw new IllegalArgumentException("Expected File input, got: " +
                (input == null ? "null" : input.getClass().getName()));
    }
}
