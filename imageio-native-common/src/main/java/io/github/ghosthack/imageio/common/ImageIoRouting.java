package io.github.ghosthack.imageio.common;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Application-owned routing policy for backend intersections.
 *
 * <p>Call {@link #configure} during application startup. The policy freezes on
 * the first routed ImageIO or video operation.</p>
 */
public final class ImageIoRouting {

    private static final String HOST = "host";
    private static Map<String, String> configuredRoutes = Map.of();
    private static Map<String, String> frozenRoutes;
    private static boolean configured;

    private ImageIoRouting() {}

    public static synchronized void configure(Consumer<Routes> configuration) {
        Objects.requireNonNull(configuration, "configuration");
        if (frozenRoutes != null) {
            throw new IllegalStateException(
                    "ImageIO routing is already frozen by a routed operation");
        }
        if (configured) {
            throw new IllegalStateException("ImageIO routing may only be configured once");
        }

        Routes routes = new Routes();
        configuration.accept(routes);
        validateBackendIds(routes.values());
        configuredRoutes = Map.copyOf(routes.values());
        configured = true;
    }

    static synchronized String preference(String format) {
        if (frozenRoutes == null) {
            frozenRoutes = configuredRoutes;
        }
        return frozenRoutes.get(normalizeFormat(format));
    }

    public static String normalizeFormat(String format) {
        Objects.requireNonNull(format, "format");
        String normalized = format.strip().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "jpg", "jpe", "jfif" -> "jpeg";
            case "tif" -> "tiff";
            case "webp" -> "webp";
            case "jp2", "j2k", "jpf", "jpx", "j2c" -> "jpeg2000";
            case "m4v" -> "m4v";
            default -> normalized;
        };
    }

    static boolean isHost(String preference) {
        return HOST.equals(preference);
    }

    private static void validateBackendIds(Map<String, String> routes) {
        Set<String> installed = new HashSet<>();
        for (RoutingBackend backend : ServiceLoader.load(RoutingBackend.class)) {
            installed.add(normalizeBackendId(backend.id()));
        }
        for (String backend : routes.values()) {
            if (!HOST.equals(backend) && !installed.contains(backend)) {
                throw new IllegalArgumentException(
                        "Unknown or uninstalled ImageIO backend ID: " + backend);
            }
        }
    }

    private static String normalizeBackendId(String backend) {
        String normalized = Objects.requireNonNull(backend, "backend")
                .strip().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Backend ID must not be empty");
        }
        return normalized;
    }

    public static final class Routes {
        private final Map<String, String> routes = new HashMap<>();

        private Routes() {}

        public Routes prefer(String format, String backend) {
            routes.put(normalizeFormat(format), normalizeBackendId(backend));
            return this;
        }

        public Routes preferHost(String format) {
            routes.put(normalizeFormat(format), HOST);
            return this;
        }

        private Map<String, String> values() {
            return routes;
        }
    }
}
