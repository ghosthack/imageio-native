package io.github.ghosthack.imageio.windows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.imageio.IIOException;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the {@link WindowsImageio} direct public API.
 * <p>
 * All tests are gated by {@code @EnabledOnOs(OS.WINDOWS)} so the module
 * compiles and "passes" on macOS/Linux CI (tests are simply skipped).
 * <p>
 * Covers availability checks, format/suffix queries, canDecode probing,
 * codec installation checks, getSize (dimension-only) and full decode.
 */
@EnabledOnOs(OS.WINDOWS)
class WindowsImageioTest {

    // ── Availability ────────────────────────────────────────────────────

    @Test
    void isAvailableReturnsTrue() {
        assertTrue(WindowsImageio.isAvailable(), "WIC backend should be available on Windows");
    }

    // ── Format queries ──────────────────────────────────────────────────

    @Test
    void activeFormatsNotEmpty() {
        Set<String> formats = WindowsImageio.activeFormats();
        assertNotNull(formats);
        assertFalse(formats.isEmpty(), "Active formats should not be empty in default mode");
    }

    @ParameterizedTest(name = "activeFormats contains {0}")
    @ValueSource(strings = {"HEIC", "heic", "AVIF", "avif", "WEBP", "webp"})
    void activeFormatsContainsSupplementalFormat(String name) {
        Set<String> formats = WindowsImageio.activeFormats();
        assertTrue(formats.contains(name),
                "Active formats should contain '" + name + "' in supplemental mode");
    }

    @Test
    void activeSuffixesNotEmpty() {
        Set<String> suffixes = WindowsImageio.activeSuffixes();
        assertNotNull(suffixes);
        assertFalse(suffixes.isEmpty(), "Active suffixes should not be empty in default mode");
    }

    @ParameterizedTest(name = "activeSuffixes contains {0}")
    @ValueSource(strings = {"heic", "avif", "webp", "jxr", "wdp", "dng"})
    void activeSuffixesContainsExpected(String suffix) {
        Set<String> suffixes = WindowsImageio.activeSuffixes();
        assertTrue(suffixes.contains(suffix),
                "Active suffixes should contain '" + suffix + "'");
    }

    // ── Codec availability checks ───────────────────────────────────────

    @Test
    void heicCodecCheckDoesNotThrow() {
        // Just verify the method runs without error; result depends on
        // whether HEVC Video Extensions are installed
        boolean available = WindowsImageio.isHeicCodecInstalled();
        System.out.println("HEIC codec installed: " + available);
    }

    @Test
    void avifCodecCheckDoesNotThrow() {
        boolean available = WindowsImageio.isAvifCodecInstalled();
        System.out.println("AVIF codec installed: " + available);
    }

    @Test
    void webpCodecAvailabilityDoesNotThrow() {
        // WebP is built-in on Windows 10 1809+; may not be present on
        // all CI runners, so just verify the check runs without error.
        boolean available = WindowsImageio.isWebpCodecInstalled();
        System.out.println("WebP codec available: " + available);
    }

    // ── canDecode ───────────────────────────────────────────────────────

    @Test
    void canDecodeRejectsGarbage() {
        byte[] garbage = {0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08};
        assertFalse(WindowsImageio.canDecode(garbage, garbage.length),
                "Should reject random bytes");
    }

    // ── getSize ─────────────────────────────────────────────────────────

    @Test
    void getSizeThrowsOnGarbage() {
        byte[] garbage = {0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08};
        assertThrows(IIOException.class, () -> WindowsImageio.getSize(garbage),
                "getSize should throw on unrecognised data");
    }

    // ── decode ──────────────────────────────────────────────────────────

    @Test
    void decodeThrowsOnGarbage() {
        byte[] garbage = {0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08};
        assertThrows(IIOException.class, () -> WindowsImageio.decode(garbage),
                "decode should throw on unrecognised data");
    }
}
