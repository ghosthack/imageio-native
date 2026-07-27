package io.github.ghosthack.imageio.common;

import javax.imageio.stream.ImageInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

final class ImageRouting {

    record Decision(String format, ImageDecoderBackend backend) {}

    private static volatile List<ImageDecoderBackend> frozenBackends;

    private ImageRouting() {}

    static Decision select(Object source) throws IOException {
        if (!(source instanceof ImageInputStream stream)) {
            return null;
        }

        byte[] header = new byte[4096];
        int length;
        stream.mark();
        try {
            length = stream.read(header);
        } finally {
            stream.reset();
        }
        if (length <= 0) return null;

        String format = FormatDetector.detectFormat(header, length);
        if (format == null) return null;

        List<ImageDecoderBackend> candidates = new ArrayList<>();
        for (ImageDecoderBackend backend : backends()) {
            if (backend.formats().supports(format)
                    && backend.isAvailable()
                    && backend.canDecode(header, length)) {
                candidates.add(backend);
            }
        }
        ImageDecoderBackend selected = RoutingResolver.resolve(format, candidates);
        return selected == null ? null : new Decision(format, selected);
    }

    static List<ImageDecoderBackend> discover() {
        List<ImageDecoderBackend> result = new ArrayList<>();
        ServiceLoader.load(ImageDecoderBackend.class).forEach(result::add);
        return List.copyOf(result);
    }

    private static List<ImageDecoderBackend> backends() {
        List<ImageDecoderBackend> result = frozenBackends;
        if (result != null) return result;
        synchronized (ImageRouting.class) {
            if (frozenBackends == null) {
                frozenBackends = discover();
            }
            return frozenBackends;
        }
    }
}
