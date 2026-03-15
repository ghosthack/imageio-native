package io.github.ghosthack.imageio.video;

import org.junit.jupiter.api.Test;

import javax.imageio.stream.MemoryCacheImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link VideoFormatDetector} header detection.
 * <p>
 * Uses synthetic byte headers — no real video files needed.
 */
class VideoFormatDetectorTest {

    private static boolean probe(byte[] header) throws IOException {
        var bais = new ByteArrayInputStream(header);
        var iis = new MemoryCacheImageInputStream(bais);
        return VideoFormatDetector.isVideoFormat(iis);
    }

    // ── ISO BMFF (ftyp) ─────────────────────────────────────────────────

    @Test
    void detectsMp4Ftyp() throws IOException {
        // ftyp box: size=20, type=ftyp, major_brand=isom
        byte[] h = makeFtyp("isom", 20);
        assertTrue(probe(h), "MP4 with isom brand should be detected as video");
    }

    @Test
    void detectsQuickTimeFtyp() throws IOException {
        byte[] h = makeFtyp("qt  ", 20);
        assertTrue(probe(h), "MOV with qt brand should be detected as video");
    }

    @Test
    void rejectsHeicFtyp() throws IOException {
        byte[] h = makeFtyp("heic", 20);
        assertFalse(probe(h), "HEIC should NOT be detected as video");
    }

    @Test
    void rejectsAvifFtyp() throws IOException {
        byte[] h = makeFtyp("avif", 20);
        assertFalse(probe(h), "AVIF should NOT be detected as video");
    }

    @Test
    void rejectsMif1Ftyp() throws IOException {
        byte[] h = makeFtyp("mif1", 20);
        assertFalse(probe(h), "HEIF (mif1) should NOT be detected as video");
    }

    // ── Classic QuickTime MOV (no ftyp) ─────────────────────────────────

    @Test
    void detectsWideAtom() throws IOException {
        // wide atom: size=8, type=wide, followed by mdat
        byte[] h = makeAtom("wide", 8, "mdat");
        assertTrue(probe(h), "MOV starting with 'wide' atom should be detected as video");
    }

    @Test
    void detectsMdatAtom() throws IOException {
        // mdat atom: size=0 (extends to EOF), type=mdat
        byte[] h = new byte[16];
        // size=0 means "extends to end of file"
        putAtom(h, 0, 0, "mdat");
        assertTrue(probe(h), "MOV starting with 'mdat' atom should be detected as video");
    }

    @Test
    void detectsMoovAtom() throws IOException {
        byte[] h = makeAtom("moov", 100, null);
        assertTrue(probe(h), "MOV starting with 'moov' atom should be detected as video");
    }

    @Test
    void detectsFreeAtom() throws IOException {
        byte[] h = makeAtom("free", 24, null);
        assertTrue(probe(h), "MOV starting with 'free' atom should be detected as video");
    }

    @Test
    void rejectsUnknownAtom() throws IOException {
        byte[] h = makeAtom("blah", 16, null);
        assertFalse(probe(h), "Unknown atom type should NOT be detected as video");
    }

    // ── Matroska / WebM (EBML) ──────────────────────────────────────────

    @Test
    void detectsEBML() throws IOException {
        byte[] h = new byte[16];
        h[0] = 0x1A; h[1] = 0x45; h[2] = (byte) 0xDF; h[3] = (byte) 0xA3;
        assertTrue(probe(h), "EBML header should be detected as video (Matroska/WebM)");
    }

    // ── AVI (RIFF) ─────────────────────────────────────────────────────

    @Test
    void detectsAVI() throws IOException {
        byte[] h = new byte[16];
        System.arraycopy("RIFF".getBytes(), 0, h, 0, 4);
        // bytes 4-7: file size (irrelevant for detection)
        System.arraycopy("AVI ".getBytes(), 0, h, 8, 4);
        assertTrue(probe(h), "AVI header should be detected as video");
    }

    // ── Negative cases ──────────────────────────────────────────────────

    @Test
    void rejectsPNG() throws IOException {
        byte[] h = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};
        assertFalse(probe(h), "PNG should NOT be detected as video");
    }

    @Test
    void rejectsJPEG() throws IOException {
        byte[] h = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0, 0, 0, 0, 0, 0, 0};
        assertFalse(probe(h), "JPEG should NOT be detected as video");
    }

    @Test
    void rejectsTooShort() throws IOException {
        byte[] h = {0, 0, 0};
        assertFalse(probe(h), "Header shorter than 12 bytes should not match");
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    /** Creates a minimal ftyp box header. */
    private static byte[] makeFtyp(String brand, int boxSize) {
        byte[] h = new byte[Math.max(boxSize, 20)];
        ByteBuffer.wrap(h).putInt(0, boxSize);
        System.arraycopy("ftyp".getBytes(StandardCharsets.US_ASCII), 0, h, 4, 4);
        System.arraycopy(brand.getBytes(StandardCharsets.US_ASCII), 0, h, 8, 4);
        return h;
    }

    /** Creates a header with one atom, optionally followed by a second atom type. */
    private static byte[] makeAtom(String type, int size, String nextType) {
        int total = nextType != null ? size + 16 : Math.max(size, 12);
        byte[] h = new byte[total];
        putAtom(h, 0, size, type);
        if (nextType != null) {
            putAtom(h, size, 16, nextType);
        }
        return h;
    }

    private static void putAtom(byte[] h, int offset, int size, String type) {
        ByteBuffer.wrap(h).putInt(offset, size);
        System.arraycopy(type.getBytes(StandardCharsets.US_ASCII), 0, h, offset + 4, 4);
    }
}
