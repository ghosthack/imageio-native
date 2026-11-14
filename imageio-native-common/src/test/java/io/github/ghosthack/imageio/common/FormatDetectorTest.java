package io.github.ghosthack.imageio.common;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;

class FormatDetectorTest {

    @Test
    void detectsHostFormats() {
        assertEquals("jpeg", FormatDetector.detectFormat(
                new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}, 3));
        assertEquals("png", FormatDetector.detectFormat(
                new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10}, 8));
    }

    @Test
    void detectsIsoBmffImageBrands() {
        assertEquals("heic", FormatDetector.detectFormat(ftyp("heic"), 20));
        assertEquals("avif", FormatDetector.detectFormat(ftyp("avif"), 20));
        assertEquals("jpeg2000", FormatDetector.detectFormat(ftyp("jp2 "), 20));
    }

    @Test
    void videoIsoBmffDoesNotMasqueradeAsStillImage() {
        assertNull(FormatDetector.detectFormat(ftyp("isom"), 20));
    }

    @Test
    void wbmpDoesNotParticipateInPrefixBasedRouting() {
        assertNull(FormatDetector.detectFormat(
                new byte[]{0, 0, 1, 1, 0}, 5));
        assertNull(FormatDetector.detectFormat(
                new byte[]{0, 0, 3, 0}, 4));
    }

    @Test
    void codecBrandWinsOverGenericHeifMajorBrand() {
        assertEquals("avif", FormatDetector.detectFormat(ftyp("mif1", "miaf", "avif"), 28));
        assertEquals("heic", FormatDetector.detectFormat(ftyp("mif1", "heic"), 24));
        assertEquals("heif", FormatDetector.detectFormat(ftyp("mif1", "miaf"), 24));
    }

    @Test
    void detectsCanonCr3AndSharesItWithVideoDetection() {
        assertEquals("cr3", FormatDetector.detectFormat(ftyp("crx ", "isom"), 24));
        assertTrue(FormatDetector.isStillImageBrand("crx "));
        assertTrue(FormatDetector.isStillImageBrand("mif1"));
        assertFalse(FormatDetector.isStillImageBrand("isom"));
    }

    // ── TIFF family ─────────────────────────────────────────────────────

    @Test
    void plainTiffFromACameraIsNotMistakenForRaw() {
        // A TIFF exported from a camera JPEG keeps the make but its first
        // IFD is the full image, with no sub-IFDs.
        byte[] nikonTiff = tiff("NIKON CORPORATION", false, false);
        byte[] sonyTiff = tiff("SONY", false, false);

        assertEquals("tiff", FormatDetector.detectFormat(nikonTiff, nikonTiff.length));
        assertEquals("tiff", FormatDetector.detectFormat(sonyTiff, sonyTiff.length));
    }

    @Test
    void textThatHappensToContainDngIsStillTiff() {
        byte[] described = tiff("Landing page, ending credits (dng)", false, false);

        assertEquals("tiff", FormatDetector.detectFormat(described, described.length));
    }

    @Test
    void detectsRawWhenTheFirstIfdIsAPreviewOfSubIfdData() {
        byte[] nef = tiff("NIKON CORPORATION", true, false);
        byte[] arw = tiff("SONY", true, false);

        assertEquals("nef", FormatDetector.detectFormat(nef, nef.length));
        assertEquals("arw", FormatDetector.detectFormat(arw, arw.length));
    }

    @Test
    void detectsDngFromItsVersionTag() {
        byte[] dng = tiff("Leica Camera AG", false, true);

        assertEquals("dng", FormatDetector.detectFormat(dng, dng.length));
    }

    /**
     * Builds a little-endian TIFF prefix whose first IFD carries a Make tag
     * and, optionally, the preview-plus-sub-IFD shape of a RAW file or a
     * DNGVersion tag.
     */
    private static byte[] tiff(String make, boolean rawPreview, boolean dngVersion) {
        byte[] makeBytes = (make + "\0").getBytes(StandardCharsets.US_ASCII);
        int entries = 1 + (rawPreview ? 2 : 0) + (dngVersion ? 1 : 0);
        int ifdEnd = 8 + 2 + entries * 12 + 4;
        ByteBuffer buffer = ByteBuffer.allocate(ifdEnd + makeBytes.length + 16)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        buffer.put((byte) 'I').put((byte) 'I').putShort((short) 0x2A).putInt(8);
        buffer.putShort((short) entries);
        if (rawPreview) {
            // NewSubfileType = 1 (reduced-resolution image)
            buffer.putShort((short) 0x00FE).putShort((short) 4).putInt(1).putInt(1);
        }
        // Make (ASCII), stored after the IFD
        buffer.putShort((short) 0x010F).putShort((short) 2)
                .putInt(makeBytes.length).putInt(ifdEnd);
        if (rawPreview) {
            // SubIFDs: one offset
            buffer.putShort((short) 0x014A).putShort((short) 4).putInt(1).putInt(ifdEnd);
        }
        if (dngVersion) {
            buffer.putShort((short) 0xC612).putShort((short) 1).putInt(4).putInt(0x00000401);
        }
        buffer.putInt(0);
        buffer.put(makeBytes);
        return buffer.array();
    }

    private static byte[] ftyp(String major, String... compatible) {
        int size = 16 + compatible.length * 4;
        byte[] header = new byte[size];
        ByteBuffer.wrap(header).putInt(0, size);
        System.arraycopy("ftyp".getBytes(StandardCharsets.US_ASCII), 0, header, 4, 4);
        System.arraycopy(major.getBytes(StandardCharsets.US_ASCII), 0, header, 8, 4);
        for (int i = 0; i < compatible.length; i++) {
            System.arraycopy(compatible[i].getBytes(StandardCharsets.US_ASCII), 0,
                    header, 16 + i * 4, 4);
        }
        return header;
    }

    private static byte[] ftyp(String brand) {
        byte[] header = new byte[20];
        ByteBuffer.wrap(header).putInt(0, 20);
        System.arraycopy("ftyp".getBytes(StandardCharsets.US_ASCII), 0, header, 4, 4);
        System.arraycopy(brand.getBytes(StandardCharsets.US_ASCII), 0, header, 8, 4);
        return header;
    }
}
