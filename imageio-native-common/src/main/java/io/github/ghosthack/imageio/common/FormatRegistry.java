package io.github.ghosthack.imageio.common;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Immutable declaration of the formats a backend may decode.
 *
 * <p>Installing a backend authorizes all formats it declares. Actual ownership
 * is still input-specific: the routing layer also requires the backend's probe
 * to accept the input.</p>
 */
public final class FormatRegistry {

    /** A format's ImageIO names, suffixes, MIME types, and host-JDK overlap. */
    public record Format(String[] names, String[] suffixes, String[] mimeTypes,
                         boolean javaNative) {}

    private final List<Format> formats;
    private final Map<String, String> canonicalNames;
    private final String[] formatNames;
    private final String[] suffixes;
    private final String[] mimeTypes;

    public FormatRegistry(List<Format> formats) {
        this.formats = List.copyOf(formats);
        Map<String, String> names = new HashMap<>();
        for (Format format : this.formats) {
            if (format.names().length == 0) {
                throw new IllegalArgumentException("A format must declare at least one name");
            }
            String canonical = normalize(format.names()[0]);
            for (String name : format.names()) {
                names.put(normalize(name), canonical);
            }
        }
        canonicalNames = Map.copyOf(names);
        formatNames = this.formats.stream().flatMap(f -> Arrays.stream(f.names()))
                .distinct().toArray(String[]::new);
        suffixes = this.formats.stream().flatMap(f -> Arrays.stream(f.suffixes()))
                .distinct().toArray(String[]::new);
        mimeTypes = this.formats.stream().flatMap(f -> Arrays.stream(f.mimeTypes()))
                .distinct().toArray(String[]::new);
    }

    public List<Format> formats() {
        return formats;
    }

    public String[] formatNames() {
        return formatNames.clone();
    }

    public String[] suffixes() {
        return suffixes.clone();
    }

    public String[] mimeTypes() {
        return mimeTypes.clone();
    }

    public boolean supports(String format) {
        return canonicalNames.containsKey(normalize(format));
    }

    public String canonicalName(String format) {
        return canonicalNames.get(normalize(format));
    }

    private static String normalize(String value) {
        return value.strip().toLowerCase(Locale.ROOT);
    }
}
