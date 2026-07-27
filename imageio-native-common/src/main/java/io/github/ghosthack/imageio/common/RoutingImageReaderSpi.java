package io.github.ghosthack.imageio.common;

import javax.imageio.ImageReader;
import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.spi.ServiceRegistry;
import javax.imageio.stream.ImageInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The single still-image ImageIO provider for all imageio-native backends.
 */
public final class RoutingImageReaderSpi extends ImageReaderSpi {

    private static final ThreadLocal<ImageRouting.Decision> PENDING = new ThreadLocal<>();

    public RoutingImageReaderSpi() {
        this(Metadata.discover());
    }

    private RoutingImageReaderSpi(Metadata metadata) {
        super("imageio-native", "2.0",
                metadata.names, metadata.suffixes, metadata.mimeTypes,
                RoutingImageReader.class.getName(),
                new Class<?>[]{ImageInputStream.class},
                null,
                false, null, null, null, null,
                false, null, null, null, null);
    }

    @Override
    public boolean canDecodeInput(Object source) throws IOException {
        ImageRouting.Decision decision = ImageRouting.select(source);
        if (decision == null) {
            PENDING.remove();
            return false;
        }
        PENDING.set(decision);
        return true;
    }

    @Override
    public ImageReader createReaderInstance(Object extension) {
        ImageRouting.Decision decision = PENDING.get();
        PENDING.remove();
        if (decision != null) {
            return decision.backend().createReader(this);
        }
        return new RoutingImageReader(this);
    }

    @Override
    public String getDescription(Locale locale) {
        return "imageio-native input-specific routing reader";
    }

    @SuppressWarnings("unchecked")
    @Override
    public void onRegistration(ServiceRegistry registry, Class<?> category) {
        Class<ImageReaderSpi> type = (Class<ImageReaderSpi>) category;
        Iterator<ImageReaderSpi> providers = registry.getServiceProviders(type, false);
        List<ImageReaderSpi> hostReaders = new ArrayList<>();
        while (providers.hasNext()) {
            ImageReaderSpi provider = providers.next();
            if (provider != this
                    && provider.getClass().getName().startsWith("com.sun.imageio.plugins.")) {
                hostReaders.add(provider);
            }
        }
        for (ImageReaderSpi hostReader : hostReaders) {
            registry.setOrdering(type, this, hostReader);
        }
    }

    private record Metadata(String[] names, String[] suffixes, String[] mimeTypes) {
        private static Metadata discover() {
            Set<String> names = new LinkedHashSet<>();
            Set<String> suffixes = new LinkedHashSet<>();
            Set<String> mimeTypes = new LinkedHashSet<>();
            for (ImageDecoderBackend backend : ImageRouting.discover()) {
                names.addAll(Arrays.asList(backend.formats().formatNames()));
                suffixes.addAll(Arrays.asList(backend.formats().suffixes()));
                mimeTypes.addAll(Arrays.asList(backend.formats().mimeTypes()));
            }
            if (names.isEmpty()) names.add("imageio-native");
            return new Metadata(names.toArray(String[]::new),
                    suffixes.toArray(String[]::new),
                    mimeTypes.toArray(String[]::new));
        }
    }
}
