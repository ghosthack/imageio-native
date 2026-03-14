package io.github.ghosthack.imageio.video;

import javax.imageio.stream.ImageInputStream;
import java.io.IOException;

/**
 * Detects video container formats from magic bytes in the stream header.
 * <p>
 * Supports:
 * <ul>
 *   <li>ISO BMFF (MP4, MOV, M4V, 3GP) — {@code ftyp} box at offset 4</li>
 *   <li>Matroska / WebM — EBML header {@code 0x1A45DFA3}</li>
 *   <li>AVI — RIFF header with AVI signature</li>
 * </ul>
 */
public final class VideoFormatDetector {

    private VideoFormatDetector() {}

    /**
     * Returns {@code true} if the stream header matches a known video container format.
     * The stream position is restored after probing.
     */
    public static boolean isVideoFormat(ImageInputStream stream) throws IOException {
        stream.mark();
        try {
            byte[] header = new byte[12];
            int n = stream.read(header);
            if (n < 12) return false;
            return isISOBMFF(header) || isEBML(header) || isAVI(header);
        } finally {
            stream.reset();
        }
    }

    /** ISO BMFF: bytes 4-7 == "ftyp" */
    private static boolean isISOBMFF(byte[] h) {
        return h[4] == 'f' && h[5] == 't' && h[6] == 'y' && h[7] == 'p';
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
