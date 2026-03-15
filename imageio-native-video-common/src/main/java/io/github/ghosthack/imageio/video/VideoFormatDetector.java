package io.github.ghosthack.imageio.video;

import javax.imageio.stream.ImageInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Detects video container formats from magic bytes in the stream header.
 * <p>
 * Supports:
 * <ul>
 *   <li>ISO BMFF (MP4, MOV, M4V, 3GP) — {@code ftyp} box with a video brand</li>
 *   <li>Classic QuickTime MOV — starts with {@code wide}, {@code mdat},
 *       {@code moov}, or {@code free} atoms (no {@code ftyp})</li>
 *   <li>Matroska / WebM — EBML header {@code 0x1A45DFA3}</li>
 *   <li>AVI — RIFF header with AVI signature</li>
 * </ul>
 * <p>
 * ISO BMFF containers are also used for still images (HEIC, AVIF). To avoid
 * claiming image files as video, {@link #isVideoISOBMFF} checks the ftyp major
 * brand and compatible brands against a set of known image brands and rejects
 * the match if any image brand is found.
 */
public final class VideoFormatDetector {

    private VideoFormatDetector() {}

    /**
     * ISO BMFF brands that identify still-image containers.
     * If the ftyp box declares any of these (as major or compatible brand),
     * the file is an image — not a video — and must not be claimed.
     */
    private static final Set<String> IMAGE_BRANDS = Set.of(
            // HEIF / HEIC
            "heic", "heix", "heim", "heis",
            "hevc", "hevx", "hevm", "hevs",
            "mif1", "mif2",
            // AVIF
            "avif", "avis",
            // JPEG 2000
            "jp2 ", "jpx ", "jpm "
    );

    /**
     * Known QuickTime/MPEG-4 atom types that appear at the start of MOV/MP4 files.
     * Classic QuickTime files may not have {@code ftyp} and instead start with
     * {@code wide}, {@code mdat}, {@code moov}, {@code free}, or {@code skip}.
     */
    private static final Set<String> QT_ATOM_TYPES = Set.of(
            "ftyp", "moov", "mdat", "wide", "free", "skip", "pnot"
    );

    /**
     * Returns {@code true} if the stream header matches a known video container format.
     * The stream position is restored after probing.
     */
    public static boolean isVideoFormat(ImageInputStream stream) throws IOException {
        stream.mark();
        try {
            byte[] header = new byte[128];
            int n = stream.read(header);
            if (n < 12) return false;
            return isVideoISOBMFF(header, n)
                    || isClassicQuickTime(header, n)
                    || isEBML(header)
                    || isAVI(header);
        } finally {
            stream.reset();
        }
    }

    /**
     * Returns {@code true} if the header is an ISO BMFF container (has {@code ftyp}
     * at offset 4) and none of its brands identify it as a still-image format.
     * <p>
     * This prevents HEIC, AVIF, and JPEG 2000 files from being claimed as video.
     */
    private static boolean isVideoISOBMFF(byte[] h, int n) {
        // Must have ftyp signature at offset 4..7
        if (h[4] != 'f' || h[5] != 't' || h[6] != 'y' || h[7] != 'p')
            return false;

        // Box size (big-endian uint32 at offset 0)
        int boxSize = ((h[0] & 0xFF) << 24)
                    | ((h[1] & 0xFF) << 16)
                    | ((h[2] & 0xFF) <<  8)
                    | ((h[3] & 0xFF));

        // Major brand at offset 8..11
        String major = new String(h, 8, 4, StandardCharsets.US_ASCII);
        if (IMAGE_BRANDS.contains(major)) return false;

        // Compatible brands start at offset 16 (after 4-byte minor_version)
        // boxSize == 0 means "box extends to end of file" per ISO 14496-12
        int limit = (boxSize == 0) ? n : Math.min(boxSize, n);
        for (int off = 16; off + 4 <= limit; off += 4) {
            String compat = new String(h, off, 4, StandardCharsets.US_ASCII);
            if (IMAGE_BRANDS.contains(compat)) return false;
        }

        return true; // ftyp present, no image brands → treat as video
    }

    /**
     * Detects classic QuickTime MOV files that lack an {@code ftyp} atom.
     * <p>
     * These files start with atoms like {@code wide}, {@code mdat}, {@code moov},
     * {@code free}, or {@code skip}.  We check that the first atom has a valid
     * size (big-endian uint32 at offset 0) and a recognized atom type at offset 4-7.
     * <p>
     * This does NOT match ISO BMFF with {@code ftyp} — that's handled by
     * {@link #isVideoISOBMFF} which also filters out image formats.
     */
    private static boolean isClassicQuickTime(byte[] h, int n) {
        if (n < 8) return false;

        // Atom type at offset 4..7
        String atomType = new String(h, 4, 4, StandardCharsets.US_ASCII);
        if ("ftyp".equals(atomType)) return false; // handled by isVideoISOBMFF
        if (!QT_ATOM_TYPES.contains(atomType)) return false;

        // Atom size at offset 0..3 (big-endian uint32)
        int atomSize = ((h[0] & 0xFF) << 24)
                     | ((h[1] & 0xFF) << 16)
                     | ((h[2] & 0xFF) <<  8)
                     | ((h[3] & 0xFF));

        // Size 0 means "extends to end of file" (valid for mdat).
        // Size 1 means "64-bit extended size" follows (valid).
        // Otherwise, size must be >= 8 (minimum atom: 4-byte size + 4-byte type).
        return atomSize == 0 || atomSize == 1 || atomSize >= 8;
    }

    /** EBML (Matroska/WebM): bytes 0-3 == 0x1A 0x45 0xDF 0xA3 */
    private static boolean isEBML(byte[] h) {
        return (h[0] & 0xFF) == 0x1A && (h[1] & 0xFF) == 0x45
                && (h[2] & 0xFF) == 0xDF && (h[3] & 0xFF) == 0xA3;
    }

    /** AVI: "RIFF" at 0-3 and "AVI " at 8-11 */
    private static boolean isAVI(byte[] h) {
        return h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F'
                && h[8] == 'A' && h[9] == 'V' && h[10] == 'I' && h[11] == ' ';
    }
}
