package io.github.ghosthack.imageio.video;

import javax.imageio.ImageReader;
import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.spi.ServiceRegistry;
import javax.imageio.stream.ImageInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/** The single ImageIO provider for all video poster-frame backends. */
public final class VideoRoutingImageReaderSpi extends ImageReaderSpi {

    private static final ThreadLocal<VideoRouting.Decision> PENDING = new ThreadLocal<>();

    public VideoRoutingImageReaderSpi() {
        super("imageio-native", "2.0",
                VideoFormatRegistry.FORMAT_NAMES,
                VideoFormatRegistry.SUFFIXES,
                VideoFormatRegistry.MIME_TYPES,
                NativeVideoReader.class.getName(),
                new Class<?>[]{ImageInputStream.class},
                null,
                false, null, null, null, null,
                false, null, null, null, null);
    }

    @Override
    public boolean canDecodeInput(Object source) throws IOException {
        if (!(source instanceof io.github.ghosthack.imageio.common.PathAwareImageInputStream stream)) {
            PENDING.remove();
            return false;
        }
        String format = VideoFormatDetector.detectFormat(stream);
        VideoRouting.Decision decision = format == null
                ? null : VideoRouting.select(stream.getPath(), format);
        if (decision == null) {
            PENDING.remove();
            return false;
        }
        PENDING.set(decision);
        return true;
    }

    @Override
    public ImageReader createReaderInstance(Object extension) {
        VideoRouting.Decision decision = PENDING.get();
        PENDING.remove();
        return decision == null
                ? new NativeVideoReader(this)
                : new NativeVideoReader(this, decision.backend());
    }

    @Override
    public String getDescription(Locale locale) {
        return "imageio-native input-specific video routing reader";
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
}
