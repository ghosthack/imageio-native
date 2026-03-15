package io.github.ghosthack.imageio.common;

import java.util.*;

/**
 * Controls the priority ordering of image decoding backends and per-format
 * backend routing.
 * <p>
 * When multiple backends are on the classpath (e.g. platform-native + libvips),
 * this class determines which backend gets first crack at each format.
 * <p>
 * Configured via system properties:
 * <ul>
 *   <li>{@code imageio.native.backend.priority} — global ordering, comma-separated,
 *       left = highest priority.  Default: {@code native,vips,magick}</li>
 *   <li>{@code imageio.native.backend.priority.<format>} — per-format override,
 *       e.g. {@code -Dimageio.native.backend.priority.jpeg=vips,native}</li>
 * </ul>
 * <p>
 * When no properties are set, all backends are allowed and the default ordering
 * is: {@code native} (platform-native) first, then {@code vips}, then {@code magick}.
 */
public final class BackendPriority {

    /** System property for global backend ordering. */
    public static final String PROPERTY = "imageio.native.backend.priority";

    /** Default ordering when no system property is set. */
    private static final String DEFAULT_ORDER = "native,vips,magick";

    /** Global ordering: backend name → priority index (lower = higher priority). */
    private static final Map<String, Integer> GLOBAL_PRIORITY;

    /** Per-format overrides: format (lower-case) → ordered list of backend names. */
    private static final Map<String, List<String>> FORMAT_OVERRIDES;

    static {
        // Parse global priority
        String order = System.getProperty(PROPERTY, DEFAULT_ORDER);
        GLOBAL_PRIORITY = parseOrder(order);

        // Parse per-format overrides
        Map<String, List<String>> overrides = new HashMap<>();
        Properties props = System.getProperties();
        String prefix = PROPERTY + ".";
        for (String key : props.stringPropertyNames()) {
            if (key.startsWith(prefix) && key.length() > prefix.length()) {
                String format = key.substring(prefix.length()).toLowerCase(Locale.ROOT);
                String value = props.getProperty(key, "");
                List<String> backends = new ArrayList<>();
                for (String s : value.split(",")) {
                    String trimmed = s.strip().toLowerCase(Locale.ROOT);
                    if (!trimmed.isEmpty()) backends.add(trimmed);
                }
                if (!backends.isEmpty()) {
                    overrides.put(format, List.copyOf(backends));
                }
            }
        }
        FORMAT_OVERRIDES = Map.copyOf(overrides);
    }

    private BackendPriority() {}

    /**
     * Returns the priority index for the given backend (lower = higher priority).
     * Backends not listed in the global ordering get {@code Integer.MAX_VALUE}.
     *
     * @param backend backend name (e.g. {@code "native"}, {@code "vips"}, {@code "magick"})
     * @return priority index, 0-based
     */
    public static int priority(String backend) {
        Integer p = GLOBAL_PRIORITY.get(backend.toLowerCase(Locale.ROOT));
        return p != null ? p : Integer.MAX_VALUE;
    }

    /**
     * Returns {@code true} if the given backend is allowed to handle the given
     * format.
     * <p>
     * If a per-format override exists (e.g.
     * {@code -Dimageio.native.backend.priority.jpeg=vips,native}), the backend
     * must appear in that list.  Otherwise, the backend must appear in the
     * global ordering.
     *
     * @param backend backend name (e.g. {@code "native"}, {@code "vips"})
     * @param format  format name (e.g. {@code "jpeg"}, {@code "heic"})
     * @return {@code true} if the backend is allowed for this format
     */
    public static boolean isAllowed(String backend, String format) {
        String backendLower = backend.toLowerCase(Locale.ROOT);
        if (format != null) {
            String formatLower = format.toLowerCase(Locale.ROOT);
            List<String> override = FORMAT_OVERRIDES.get(formatLower);
            if (override != null) {
                return override.contains(backendLower);
            }
        }
        // No per-format override — check global list
        return GLOBAL_PRIORITY.containsKey(backendLower);
    }

    private static Map<String, Integer> parseOrder(String order) {
        Map<String, Integer> map = new HashMap<>();
        int index = 0;
        for (String s : order.split(",")) {
            String trimmed = s.strip().toLowerCase(Locale.ROOT);
            if (!trimmed.isEmpty() && !map.containsKey(trimmed)) {
                map.put(trimmed, index++);
            }
        }
        return Map.copyOf(map);
    }
}
