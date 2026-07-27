package io.github.ghosthack.imageio.common;

import org.junit.jupiter.api.Test;

import javax.imageio.stream.MemoryCacheImageInputStream;
import java.io.ByteArrayInputStream;

import static org.junit.jupiter.api.Assertions.assertFalse;

class RoutingImageReaderSpiTest {

    @Test
    void declinesHostFormatWhenNoBackendServiceIsInstalled() throws Exception {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(png))) {
            assertFalse(new RoutingImageReaderSpi().canDecodeInput(input));
        }
    }
}
