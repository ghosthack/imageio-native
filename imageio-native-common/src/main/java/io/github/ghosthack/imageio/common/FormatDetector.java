package io.github.ghosthack.imageio.common;

import javax.imageio.stream.ImageInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Shared magic-byte detection for ISO BMFF (ftyp), RIFF/WEBP containers,
 * and Java-native image formats.
 * <p>
 * Used by the {@link javax.imageio.spi.ImageReaderSpi} implementations in
 * both the Apple and Windows platform modules to decide whether they can
 * decode a given input.
 */
public final class FormatDetector {

    private FormatDetector() {}

    /**
     * Checks whether an {@link ImageInputStream} starts with an ISO BMFF
     * {@code ftyp} box whose major brand or any compatible brand is in the
     * supplied set.
     * <p>
     * The stream position is saved and restored.
     *
     * @param stream the image input stream (must support mark/reset)
     * @param brands set of 4-character brand codes to match
     * @return {@code true} if the stream matches
     */
    public static boolean matchesFtyp(ImageInputStream stream, Set<String> brands) throws IOException {
        byte[] header = new byte[128];
        stream.mark();
        try {
            int n = stream.read(header, 0, header.length);
            if (n < 12) return false;

            // bytes 4..7 must be "ftyp"
            if (header[4] != 'f' || header[5] != 't' || header[6] != 'y' || header[7] != 'p')
                return false;

            // Box size (big-endian uint32 at offset 0)
            int boxSize = ((header[0] & 0xFF) << 24)
                        | ((header[1] & 0xFF) << 16)
                        | ((header[2] & 0xFF) <<  8)
                        | ((header[3] & 0xFF));

            // Major brand at offset 8..11
            String major = new String(header, 8, 4, StandardCharsets.US_ASCII);
            if (brands.contains(major)) return true;

            // Compatible brands start at offset 16 (after 4-byte minor_version)
            int limit = Math.min(boxSize, n);
            for (int off = 16; off + 4 <= limit; off += 4) {
                String compat = new String(header, off, 4, StandardCharsets.US_ASCII);
                if (brands.contains(compat)) return true;
            }

            return false;
        } finally {
            stream.reset();
        }
    }

    /**
     * Checks whether an {@link ImageInputStream} starts with the RIFF/WEBP
     * magic bytes: {@code RIFF....WEBP}.
     * <p>
     * The stream position is saved and restored.
     */
    public static boolean matchesWebP(ImageInputStream stream) throws IOException {
        byte[] header = new byte[12];
        stream.mark();
        try {
            int n = stream.read(header, 0, 12);
            if (n < 12) return false;
            // offset 0..3 = "RIFF", offset 8..11 = "WEBP"
            return header[0] == 'R' && header[1] == 'I' && header[2] == 'F' && header[3] == 'F'
                && header[8] == 'W' && header[9] == 'E' && header[10] == 'B' && header[11] == 'P';
        } finally {
            stream.reset();
        }
    }

    // ── Java-native format detection (raw byte[] variant) ───────────────

    /**
     * Returns {@code true} if the header bytes match a format that Java's
     * built-in ImageIO can already decode: JPEG, PNG, GIF, BMP, TIFF, WBMP.
     * <p>
     * Used in "supplemental" mode so the universal SPI yields to Java's
     * own readers for those formats.
     */
    public static boolean isJavaNativeFormat(byte[] h, int len) {
        if (len < 4) return false;

        // JPEG: FF D8 FF
        if (len >= 3 && u(h[0]) == 0xFF && u(h[1]) == 0xD8 && u(h[2]) == 0xFF)
            return true;

        // PNG: 89 50 4E 47
        if (u(h[0]) == 0x89 && h[1] == 'P' && h[2] == 'N' && h[3] == 'G')
            return true;

        // GIF: GIF8
        if (h[0] == 'G' && h[1] == 'I' && h[2] == 'F' && h[3] == '8')
            return true;

        // BMP: BM
        if (h[0] == 'B' && h[1] == 'M')
            return true;

        // TIFF little-endian: II 2A 00
        if (len >= 4 && h[0] == 'I' && h[1] == 'I' && u(h[2]) == 0x2A && h[3] == 0x00)
            return true;

        // TIFF big-endian: MM 00 2A
        if (len >= 4 && h[0] == 'M' && h[1] == 'M' && h[2] == 0x00 && u(h[3]) == 0x2A)
            return true;

        // WBMP: type=00, fix_header=00, then varint width, varint height.
        // Exclude ISOBMFF (ftyp at offset 4) which also starts with 00 00.
        if (h[0] == 0x00 && h[1] == 0x00 && len >= 8
                && !(h[4] == 'f' && h[5] == 't' && h[6] == 'y' && h[7] == 'p'))
            return true;

        return false;
    }

    /** Unsigned byte → int. */
    private static int u(byte b) { return b & 0xFF; }
}
