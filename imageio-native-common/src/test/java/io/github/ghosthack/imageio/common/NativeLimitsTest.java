package io.github.ghosthack.imageio.common;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.imageio.IIOException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NativeLimitsTest {

    @AfterEach
    void clearOverride() {
        System.clearProperty(NativeLimits.MAX_PIXELS_PROPERTY);
    }

    @Test
    void defaultsTo256Megapixels() {
        assertEquals(256L * 1024 * 1024, NativeLimits.maxPixels());
        assertDoesNotThrow(() -> NativeLimits.requireDecodable(16384, 16384));
        assertThrows(IIOException.class,
                () -> NativeLimits.requireDecodable(16385, 16384));
    }

    @Test
    void honoursThePropertyOnEveryCall() {
        System.setProperty(NativeLimits.MAX_PIXELS_PROPERTY, "100");

        assertDoesNotThrow(() -> NativeLimits.requireDecodable(10, 10));
        assertThrows(IIOException.class,
                () -> NativeLimits.requireDecodable(11, 10));
    }

    @Test
    void rejectsNonPositiveAndOverflowingDimensions() {
        assertThrows(IIOException.class,
                () -> NativeLimits.requireDecodable(0, 10));
        assertThrows(IIOException.class,
                () -> NativeLimits.requireDecodable(Integer.MAX_VALUE, Integer.MAX_VALUE));
    }
}
