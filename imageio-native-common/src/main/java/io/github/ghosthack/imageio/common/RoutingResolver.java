package io.github.ghosthack.imageio.common;

import java.util.Comparator;
import java.util.List;

/** Deterministic ownership resolver shared by media-category routers. */
public final class RoutingResolver {

    private static final Comparator<RoutingBackend> DEFAULT_ORDER =
            Comparator.comparing(RoutingBackend::kind)
                    .thenComparing(RoutingBackend::id);

    private RoutingResolver() {}

    public static <T extends RoutingBackend> T resolve(String format, List<T> candidates) {
        return resolve(candidates, ImageIoRouting.preference(format));
    }

    static <T extends RoutingBackend> T resolve(List<T> candidates, String preference) {
        if (ImageIoRouting.isHost(preference)) {
            return null;
        }
        if (candidates.isEmpty()) {
            return null;
        }
        if (preference != null) {
            for (T candidate : candidates) {
                if (candidate.id().equals(preference)) {
                    return candidate;
                }
            }
        }
        if (candidates.size() == 1) {
            return candidates.getFirst();
        }
        return candidates.stream().min(DEFAULT_ORDER).orElseThrow();
    }
}
