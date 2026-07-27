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
     * Detects the canonical routing format from a bounded input prefix.
     *
     * <p>This is deliberately a container/signature detector, not a statement
     * that any decoder can handle the input. Backends still perform their own
     * lightweight capability probes.</p>
     *
     * @return a lower-case canonical format, or {@code null} when the format
     *         cannot be determined safely from the prefix
     */
    public static String detectFormat(byte[] h, int len) {
        if (len < 2) return null;

        if (len >= 3 && u(h[0]) == 0xFF && u(h[1]) == 0xD8 && u(h[2]) == 0xFF)
            return "jpeg";
        if (len >= 8 && u(h[0]) == 0x89 && h[1] == 'P' && h[2] == 'N'
                && h[3] == 'G' && u(h[4]) == 0x0D && u(h[5]) == 0x0A)
            return "png";
        if (len >= 4 && h[0] == 'G' && h[1] == 'I' && h[2] == 'F' && h[3] == '8')
            return "gif";
        if (h[0] == 'B' && h[1] == 'M')
            return "bmp";
        if (len >= 4 && ((h[0] == 'I' && h[1] == 'I' && u(h[2]) == 0x2A && h[3] == 0)
                || (h[0] == 'M' && h[1] == 'M' && h[2] == 0 && u(h[3]) == 0x2A)))
            return detectTiffFamily(h, len);
        if (len >= 4 && h[0] == 'I' && h[1] == 'I'
                && ((h[2] == 'R' && h[3] == 'O') || (h[2] == 'R' && h[3] == 'S')))
            return "orf";
        if (len >= 4 && h[0] == 'I' && h[1] == 'I' && h[2] == 'U' && h[3] == 0)
            return "rw2";
        if (matches(h, len, new int[]{
                0x49, 0x49, 0x1A, 0x00, 0x00, 0x00,
                0x48, 0x45, 0x41, 0x50, 0x43, 0x43, 0x44, 0x52
        }))
            return "crw";
        if (startsWith(h, len, "FUJIFILMCCD-RAW"))
            return "raf";
        if (len >= 4 && h[0] == 0 && h[1] == 'M' && h[2] == 'R' && h[3] == 'M')
            return "mrw";
        if (len >= 4 && ((h[0] == 'I' && h[1] == 'I' && u(h[2]) == 0xBC && h[3] == 1)
                || (h[0] == 'M' && h[1] == 'M' && h[2] == 1 && u(h[3]) == 0xBC)))
            return "jpegxr";
        if (len >= 12 && h[0] == 'R' && h[1] == 'I' && h[2] == 'F'
                && h[3] == 'F' && h[8] == 'W' && h[9] == 'E'
                && h[10] == 'B' && h[11] == 'P')
            return "webp";
        if (len >= 12 && h[4] == 'f' && h[5] == 't' && h[6] == 'y' && h[7] == 'p')
            return detectIsoBmff(h, len);
        if (len >= 12 && u(h[0]) == 0 && u(h[1]) == 0 && u(h[2]) == 0
                && u(h[3]) == 12 && h[4] == 'j' && h[5] == 'P'
                && h[6] == ' ' && h[7] == ' ')
            return "jpeg2000";
        if (len >= 4 && u(h[0]) == 0xFF && u(h[1]) == 0x4F
                && u(h[2]) == 0xFF && u(h[3]) == 0x51)
            return "jpeg2000";
        if (len >= 2 && u(h[0]) == 0xFF && u(h[1]) == 0x0A)
            return "jxl";
        if (len >= 12 && u(h[0]) == 0 && u(h[1]) == 0 && u(h[2]) == 0
                && u(h[3]) == 12 && h[4] == 'J' && h[5] == 'X'
                && h[6] == 'L' && h[7] == ' ')
            return "jxl";
        if (len >= 4 && h[0] == '8' && h[1] == 'B' && h[2] == 'P' && h[3] == 'S')
            return "psd";
        if (len >= 4 && h[0] == 0 && h[1] == 0 && h[2] == 1 && h[3] == 0)
            return "ico";
        if (len >= 4 && h[0] == 0 && h[1] == 0 && h[2] == 2 && h[3] == 0)
            return "cur";
        if (len >= 4 && h[0] == 'i' && h[1] == 'c' && h[2] == 'n' && h[3] == 's')
            return "icns";
        if (len >= 4 && u(h[0]) == 0x76 && u(h[1]) == 0x2F
                && u(h[2]) == 0x31 && u(h[3]) == 0x01)
            return "exr";
        if (startsWith(h, len, "#?RADIANCE") || startsWith(h, len, "#?RGBE"))
            return "hdr";
        if (len >= 4 && h[0] == 'D' && h[1] == 'D' && h[2] == 'S' && h[3] == ' ')
            return "dds";
        if (len >= 2 && u(h[0]) == 0x01 && u(h[1]) == 0xDA)
            return "sgi";
        if (len >= 132 && h[128] == 'D' && h[129] == 'I'
                && h[130] == 'C' && h[131] == 'M')
            return "dicom";
        if (matches(h, len, new int[]{
                0xAB, 0x4B, 0x54, 0x58, 0x20, 0x31, 0x31, 0xBB,
                0x0D, 0x0A, 0x1A, 0x0A
        })) return "ktx";
        if (matches(h, len, new int[]{
                0xAB, 0x4B, 0x54, 0x58, 0x20, 0x32, 0x30, 0xBB,
                0x0D, 0x0A, 0x1A, 0x0A
        })) return "ktx2";
        if (matches(h, len, new int[]{0x13, 0xAB, 0xA1, 0x5C}))
            return "astc";
        if (len >= 4 && h[0] == 'P' && h[1] == 'V' && h[2] == 'R' && h[3] == 3)
            return "pvr";
        if (startsWith(h, len, "%PDF-"))
            return "pdf";
        if (startsWith(h, len, "%!PS"))
            return "eps";
        if (startsWith(h, len, "gimp xcf "))
            return "xcf";
        if (len >= 4 && ((h[0] == 'S' && h[1] == 'D' && h[2] == 'P' && h[3] == 'X')
                || (h[0] == 'X' && h[1] == 'P' && h[2] == 'D' && h[3] == 'S')))
            return "dpx";
        if (len >= 2 && h[0] == 'P' && h[1] >= '1' && h[1] <= '7')
            return switch (h[1]) {
                case '1', '4' -> "pbm";
                case '2', '5' -> "pgm";
                case '3', '6' -> "ppm";
                default -> "pfm";
            };
        if (len >= 3 && h[0] == 'P' && (h[1] == 'F' || h[1] == 'f')
                && (h[2] == '\n' || h[2] == '\r' || h[2] == ' '))
            return "pfm";
        if (len >= 4 && u(h[0]) == 0x0A && u(h[2]) == 1
                && Set.of(1, 2, 4, 8).contains(u(h[3])))
            return "pcx";
        if (startsWith(h, len, "/* XPM */"))
            return "xpm";
        if (containsAscii(h, Math.min(len, 512), "#define")
                && containsAscii(h, Math.min(len, 512), "_width"))
            return "xbm";
        if (looksLikeTga(h, len))
            return "tga";
        if (startsWith(h, len, "SIMPLE  ="))
            return "fits";
        if (containsAscii(h, len, "<svg"))
            return "svg";

        return null;
    }

    private static String detectIsoBmff(byte[] h, int len) {
        int boxSize = ((h[0] & 0xFF) << 24) | ((h[1] & 0xFF) << 16)
                | ((h[2] & 0xFF) << 8) | (h[3] & 0xFF);
        int limit = boxSize == 0 ? len : Math.min(boxSize, len);
        for (int off = 8; off + 4 <= limit; off += off == 8 ? 8 : 4) {
            String brand = new String(h, off, 4, StandardCharsets.US_ASCII);
            if (Set.of("heic", "heix", "heim", "heis", "hevc", "hevx",
                    "hevm", "hevs").contains(brand)) return "heic";
            if (Set.of("mif1", "mif2").contains(brand)) return "heif";
            if (Set.of("avif", "avis").contains(brand)) return "avif";
            if (Set.of("jp2 ", "jpx ", "jpm ").contains(brand)) return "jpeg2000";
            if ("jxl ".equals(brand)) return "jxl";
            if (Set.of("crx ", "cr3 ").contains(brand)) return "cr3";
        }
        return null;
    }

    private static String detectTiffFamily(byte[] h, int len) {
        if (len >= 12 && h[8] == 'C' && h[9] == 'R'
                && u(h[10]) == 2 && h[11] == 0) return "cr2";
        if (containsTiffTag(h, len, 0xC612)) return "dng";
        int scan = Math.min(len, 4096);
        if (containsAscii(h, scan, "DNG")) return "dng";
        if (containsAscii(h, scan, "Nikon")) return "nef";
        if (containsAscii(h, scan, "SONY")) return "arw";
        return "tiff";
    }

    private static boolean containsTiffTag(byte[] h, int len, int wantedTag) {
        if (len < 8) return false;
        boolean little = h[0] == 'I';
        long offset = unsignedInt(h, 4, little);
        if (offset < 0 || offset + 2 > len) return false;
        int entries = unsignedShort(h, (int) offset, little);
        int position = (int) offset + 2;
        for (int i = 0; i < entries && position + 12 <= len; i++, position += 12) {
            if (unsignedShort(h, position, little) == wantedTag) return true;
        }
        return false;
    }

    private static int unsignedShort(byte[] h, int offset, boolean little) {
        return little
                ? u(h[offset]) | (u(h[offset + 1]) << 8)
                : (u(h[offset]) << 8) | u(h[offset + 1]);
    }

    private static long unsignedInt(byte[] h, int offset, boolean little) {
        long a = unsignedShort(h, offset, little);
        long b = unsignedShort(h, offset + 2, little);
        return little ? a | (b << 16) : (a << 16) | b;
    }

    private static boolean looksLikeTga(byte[] h, int len) {
        if (len < 18) return false;
        int colourMapType = u(h[1]);
        int imageType = u(h[2]);
        int width = u(h[12]) | (u(h[13]) << 8);
        int height = u(h[14]) | (u(h[15]) << 8);
        int depth = u(h[16]);
        return (colourMapType == 0 || colourMapType == 1)
                && Set.of(1, 2, 3, 9, 10, 11).contains(imageType)
                && width > 0 && height > 0
                && Set.of(8, 15, 16, 24, 32).contains(depth);
    }

    private static boolean matches(byte[] h, int len, int[] signature) {
        if (len < signature.length) return false;
        for (int i = 0; i < signature.length; i++) {
            if (u(h[i]) != signature[i]) return false;
        }
        return true;
    }

    private static boolean startsWith(byte[] h, int len, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        if (len < bytes.length) return false;
        for (int i = 0; i < bytes.length; i++) {
            if (h[i] != bytes[i]) return false;
        }
        return true;
    }

    private static boolean containsAscii(byte[] h, int len, String value) {
        byte[] needle = value.getBytes(StandardCharsets.US_ASCII);
        int limit = Math.min(len, h.length) - needle.length;
        outer:
        for (int i = 0; i <= limit; i++) {
            for (int j = 0; j < needle.length; j++) {
                byte a = h[i + j];
                byte b = needle[j];
                if (a >= 'A' && a <= 'Z') a = (byte) (a + ('a' - 'A'));
                if (b >= 'A' && b <= 'Z') b = (byte) (b + ('a' - 'A'));
                if (a != b) continue outer;
            }
            return true;
        }
        return false;
    }

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
            // boxSize == 0 means "box extends to end of file" per ISO 14496-12
            int limit = (boxSize == 0) ? n : Math.min(boxSize, n);
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

    /** Unsigned byte → int. */
    private static int u(byte b) { return b & 0xFF; }
}
