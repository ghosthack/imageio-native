package io.github.ghosthack.imageio.common;

import java.util.*;

/**
 * Base format registry with configuration engine for the {@code imageio.native.formats}
 * system property.
 * <p>
 * Platform modules extend this class and supply their platform-specific format
 * list via the constructor. The configuration logic (supplemental/all/none/explicit
 * list) is shared.
 * <p>
 * Controlled by the system property {@code imageio.native.formats}:
 * <ul>
 *   <li>{@code supplemental} (default) – only formats that Java's built-in
 *       ImageIO does <em>not</em> handle</li>
 *   <li>{@code all} – every format the platform can decode, including
 *       JPEG/PNG/GIF/BMP/TIFF</li>
 *   <li>{@code none} – disabled entirely</li>
 *   <li>comma-separated list of format names – explicit whitelist,
 *       e.g. {@code heic,avif,webp}</li>
 * </ul>
 */
public class FormatRegistry {

    /** System property name. */
    public static final String PROPERTY = "imageio.native.formats";

    /**
     * A single image format with names, file suffixes, MIME types, and
     * whether Java's built-in ImageIO can already handle it.
     */
    public record Format(String[] names, String[] suffixes, String[] mimeTypes, boolean javaNative) {}

    private static final String config = System.getProperty(PROPERTY, "supplemental");

    private final List<Format> allFormats;
    private final List<Format> active;
    private final Map<String, Format> byName;

    // Cached query results — computed once in constructor
    private final String[] cachedFormatNames;
    private final String[] cachedSuffixes;
    private final String[] cachedMimeTypes;

    /**
     * Creates a registry from the given platform-specific format list.
     *
     * @param allFormats all formats the platform can decode
     */
    public FormatRegistry(List<Format> allFormats) {
        this.allFormats = List.copyOf(allFormats);

        // Build name lookup
        Map<String, Format> m = new HashMap<>();
        for (Format f : this.allFormats)
            for (String n : f.names) m.put(n.toLowerCase(Locale.ROOT), f);
        this.byName = Map.copyOf(m);

        // Resolve active formats
        this.active = switch (config) {
            case "none" -> List.of();
            case "all"  -> this.allFormats;
            case "supplemental" -> this.allFormats.stream()
                    .filter(f -> !f.javaNative).toList();
            default -> {
                Set<String> requested = new HashSet<>();
                for (String s : config.split(","))
                    requested.add(s.strip().toLowerCase(Locale.ROOT));
                yield this.allFormats.stream()
                        .filter(f -> {
                            for (String n : f.names)
                                if (requested.contains(n.toLowerCase(Locale.ROOT))) return true;
                            return false;
                        }).toList();
            }
        };

        // Pre-compute query results once
        this.cachedFormatNames = active.stream().flatMap(f -> Arrays.stream(f.names))
                .distinct().toArray(String[]::new);
        this.cachedSuffixes = active.stream().flatMap(f -> Arrays.stream(f.suffixes))
                .distinct().toArray(String[]::new);
        this.cachedMimeTypes = active.stream().flatMap(f -> Arrays.stream(f.mimeTypes))
                .distinct().toArray(String[]::new);
    }

    // ── Queries ─────────────────────────────────────────────────────────

    /** Returns all active format names (mixed case as declared, distinct). */
    public String[] activeFormatNames() { return cachedFormatNames; }

    /** Returns all active file suffixes (lower-case, distinct). */
    public String[] activeSuffixes() { return cachedSuffixes; }

    /** Returns all active MIME types (distinct). */
    public String[] activeMimeTypes() { return cachedMimeTypes; }

    /** Returns {@code true} if at least one format is active. */
    public boolean isEnabled() {
        return !active.isEmpty();
    }

    /**
     * Returns {@code true} if the named format is one that Java's built-in
     * ImageIO already handles (JPEG, PNG, GIF, BMP, TIFF, WBMP).
     */
    public boolean isJavaNative(String formatNameLower) {
        Format f = byName.get(formatNameLower);
        return f != null && f.javaNative;
    }

    /**
     * Returns {@code true} in "supplemental" mode, meaning the SPI should
     * skip formats Java can already handle.
     */
    public boolean shouldExcludeJavaNative() {
        return "supplemental".equals(config);
    }

    // ── Convenience ─────────────────────────────────────────────────────

    /** Helper for compact format array declarations. */
    protected static String[] a(String... s) { return s; }
}
