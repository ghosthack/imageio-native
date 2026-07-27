package io.github.ghosthack.imageio.video;

import io.github.ghosthack.imageio.common.RoutingResolver;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

final class VideoRouting {

    record Decision(String format, VideoFrameExtractorProvider backend) {}

    private static volatile List<VideoFrameExtractorProvider> frozenBackends;

    private VideoRouting() {}

    static Decision select(Path path) throws IOException {
        String format = VideoFormatDetector.detectFormat(path);
        if (format == null) return null;
        return select(path, format);
    }

    static Decision select(Path path, String format) {
        List<VideoFrameExtractorProvider> candidates = new ArrayList<>();
        for (VideoFrameExtractorProvider backend : backends()) {
            if (backend.formats().contains(format)
                    && backend.isAvailable()
                    && backend.canDecode(path)) {
                candidates.add(backend);
            }
        }
        VideoFrameExtractorProvider selected = RoutingResolver.resolve(format, candidates);
        return selected == null ? null : new Decision(format, selected);
    }

    static List<VideoFrameExtractorProvider> discover() {
        List<VideoFrameExtractorProvider> result = new ArrayList<>();
        ServiceLoader.load(VideoFrameExtractorProvider.class).forEach(result::add);
        return List.copyOf(result);
    }

    private static List<VideoFrameExtractorProvider> backends() {
        List<VideoFrameExtractorProvider> result = frozenBackends;
        if (result != null) return result;
        synchronized (VideoRouting.class) {
            if (frozenBackends == null) {
                frozenBackends = discover();
            }
            return frozenBackends;
        }
    }
}
