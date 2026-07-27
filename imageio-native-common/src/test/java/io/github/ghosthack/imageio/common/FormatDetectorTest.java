package io.github.ghosthack.imageio.common;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    private static byte[] ftyp(String brand) {
        byte[] header = new byte[20];
        ByteBuffer.wrap(header).putInt(0, 20);
        System.arraycopy("ftyp".getBytes(StandardCharsets.US_ASCII), 0, header, 4, 4);
        System.arraycopy(brand.getBytes(StandardCharsets.US_ASCII), 0, header, 8, 4);
        return header;
    }
}
