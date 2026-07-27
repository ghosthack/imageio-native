package io.github.ghosthack.imageio.common;

import javax.imageio.spi.ImageInputStreamSpi;
import javax.imageio.spi.ServiceRegistry;
import javax.imageio.stream.ImageInputStream;
import java.io.File;
import java.io.IOException;
import java.util.Locale;

/**
 * Creates path-aware streams for {@link File} inputs.
 *
 * <p>The provider lives in the shared image module so
 * {@link javax.imageio.ImageIO#read(File)} can use native path decoding even
 * when no video module is installed.</p>
 */
public class PathAwareImageInputStreamSpi extends ImageInputStreamSpi {

    public PathAwareImageInputStreamSpi() {
        super("ghosthack", "2.0", File.class);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void onRegistration(ServiceRegistry registry, Class<?> category) {
        var others = registry.getServiceProviders(
                (Class<ImageInputStreamSpi>) category, true);
        while (others.hasNext()) {
            ImageInputStreamSpi other = others.next();
            if (other != this && File.class.equals(other.getInputClass())) {
                registry.setOrdering(
                        (Class<ImageInputStreamSpi>) category, this, other);
            }
        }
    }

    @Override
    public String getDescription(Locale locale) {
        return "Path-aware FileImageInputStream for native decoding";
    }

    @Override
    public ImageInputStream createInputStreamInstance(Object input, boolean useCache,
                                                      File cacheDir) throws IOException {
        if (input instanceof File file) {
            return new PathAwareImageInputStream(file);
        }
        throw new IllegalArgumentException("Expected File input, got: "
                + (input == null ? "null" : input.getClass().getName()));
    }
}
