package io.github.ghosthack.imageio.common;

import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Shared magic-byte detection that names the format of an input prefix.
 * <p>
 * The routing layer uses the result to find candidate backends and to apply
 * routing preferences; each backend still confirms with its own probe.
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

    // ── ISO BMFF brands ─────────────────────────────────────────────────

    private static final Set<String> HEIC_BRANDS = Set.of(
            "heic", "heix", "heim", "heis", "hevc", "hevx", "hevm", "hevs");
    private static final Set<String> HEIF_BRANDS = Set.of("mif1", "mif2");
    private static final Set<String> AVIF_BRANDS = Set.of("avif", "avis");
    private static final Set<String> JPEG2000_BRANDS = Set.of("jp2 ", "jpx ", "jpm ");
    private static final Set<String> JXL_BRANDS = Set.of("jxl ");
    private static final Set<String> CR3_BRANDS = Set.of("crx ", "cr3 ");

    /**
     * Returns whether an ISO BMFF {@code ftyp} brand identifies a still-image
     * container (HEIF family, AVIF, JPEG 2000, JPEG XL, Canon CR3).
     * <p>
     * Video detection uses this to leave image containers to the still-image
     * router, so both routers share one list.
     *
     * @param brand four-character brand code, including trailing spaces
     */
    public static boolean isStillImageBrand(String brand) {
        return specificIsoBmffFormat(brand) != null
                || HEIF_BRANDS.contains(brand);
    }

    private static String specificIsoBmffFormat(String brand) {
        if (HEIC_BRANDS.contains(brand)) return "heic";
        if (AVIF_BRANDS.contains(brand)) return "avif";
        if (JPEG2000_BRANDS.contains(brand)) return "jpeg2000";
        if (JXL_BRANDS.contains(brand)) return "jxl";
        if (CR3_BRANDS.contains(brand)) return "cr3";
        return null;
    }

    /**
     * Classifies an {@code ftyp} box from its major and compatible brands.
     * A codec-specific brand anywhere in the box wins over the generic HEIF
     * structural brands, so an AVIF or HEIC file whose major brand is
     * {@code mif1} is still reported by its codec.
     */
    private static String detectIsoBmff(byte[] h, int len) {
        int boxSize = ((h[0] & 0xFF) << 24) | ((h[1] & 0xFF) << 16)
                | ((h[2] & 0xFF) << 8) | (h[3] & 0xFF);
        int limit = boxSize == 0 ? len : Math.min(boxSize, len);
        boolean genericHeif = false;
        // Major brand at 8, then compatible brands after the minor version.
        for (int off = 8; off + 4 <= limit; off += off == 8 ? 8 : 4) {
            String brand = new String(h, off, 4, StandardCharsets.US_ASCII);
            String format = specificIsoBmffFormat(brand);
            if (format != null) return format;
            genericHeif |= HEIF_BRANDS.contains(brand);
        }
        return genericHeif ? "heif" : null;
    }

    // ── TIFF family ─────────────────────────────────────────────────────

    private static final int TIFF_TAG_NEW_SUBFILE_TYPE = 0x00FE;
    private static final int TIFF_TAG_MAKE = 0x010F;
    private static final int TIFF_TAG_SUB_IFDS = 0x014A;
    private static final int TIFF_TAG_DNG_VERSION = 0xC612;

    /**
     * Separates TIFF-based camera RAW from plain TIFF using the first IFD.
     * <p>
     * A camera make alone is not enough: a TIFF exported from a camera JPEG
     * keeps the make. NEF and ARW are recognised only when the first IFD is
     * also a reduced-resolution preview that points at sub-IFDs holding the
     * sensor data. Anything else is reported as plain TIFF; a backend that
     * can decode the RAW payload still claims it through its own probe.
     */
    private static String detectTiffFamily(byte[] h, int len) {
        if (len >= 12 && h[8] == 'C' && h[9] == 'R'
                && u(h[10]) == 2 && h[11] == 0) return "cr2";
        if (findTiffEntry(h, len, TIFF_TAG_DNG_VERSION) >= 0) return "dng";

        String make = tiffAscii(h, len, TIFF_TAG_MAKE);
        if (make != null && firstIfdIsRawPreview(h, len)) {
            String upper = make.toUpperCase(java.util.Locale.ROOT);
            if (upper.startsWith("NIKON")) return "nef";
            if (upper.startsWith("SONY")) return "arw";
        }
        return "tiff";
    }

    private static boolean firstIfdIsRawPreview(byte[] h, int len) {
        if (findTiffEntry(h, len, TIFF_TAG_SUB_IFDS) < 0) return false;
        int entry = findTiffEntry(h, len, TIFF_TAG_NEW_SUBFILE_TYPE);
        if (entry < 0) return false;
        boolean little = h[0] == 'I';
        int type = unsignedShort(h, entry + 2, little);
        long value = type == 3
                ? unsignedShort(h, entry + 8, little)
                : unsignedInt(h, entry + 8, little);
        return (value & 1) != 0;
    }

    /** Reads an ASCII tag of the first IFD when its value lies in the prefix. */
    private static String tiffAscii(byte[] h, int len, int tag) {
        int entry = findTiffEntry(h, len, tag);
        if (entry < 0) return null;
        boolean little = h[0] == 'I';
        if (unsignedShort(h, entry + 2, little) != 2) return null;
        long count = unsignedInt(h, entry + 4, little);
        if (count <= 0 || count > 256) return null;
        long start = count <= 4 ? entry + 8 : unsignedInt(h, entry + 8, little);
        if (start < 0 || start + count > len) return null;
        int length = (int) count;
        while (length > 0 && h[(int) start + length - 1] == 0) length--;
        return new String(h, (int) start, length, StandardCharsets.US_ASCII);
    }

    /**
     * Returns the offset of a tag's 12-byte entry in the first IFD, or -1 when
     * the tag is absent or the entry lies beyond the prefix.
     */
    private static int findTiffEntry(byte[] h, int len, int wantedTag) {
        if (len < 8) return -1;
        boolean little = h[0] == 'I';
        long offset = unsignedInt(h, 4, little);
        if (offset < 0 || offset + 2 > len) return -1;
        int entries = unsignedShort(h, (int) offset, little);
        int position = (int) offset + 2;
        for (int i = 0; i < entries && position + 12 <= len; i++, position += 12) {
            if (unsignedShort(h, position, little) == wantedTag) return position;
        }
        return -1;
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

    /** Unsigned byte → int. */
    private static int u(byte b) { return b & 0xFF; }
}
