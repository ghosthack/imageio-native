package io.github.ghosthack.imageio.common;

import org.junit.jupiter.api.Test;

import javax.imageio.stream.MemoryCacheImageInputStream;
import java.io.ByteArrayInputStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoutingImageReaderSpiTest {

    @Test
    void declinesHostFormatWhenNoBackendServiceIsInstalled() throws Exception {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(png))) {
            assertFalse(new RoutingImageReaderSpi().canDecodeInput(input));
        }
    }

    @Test
    void routedReaderAdvertisesSourceRenderSizeBeforeInputIsSet() {
        RoutingImageReader reader =
                new RoutingImageReader(new RoutingImageReaderSpi());
        try {
            assertTrue(reader.getDefaultReadParam().canSetSourceRenderSize());
        } finally {
            reader.dispose();
        }
    }

    @Test
    void providerAlwaysCreatesTheDeferredRoutingReader() throws Exception {
        RoutingImageReaderSpi provider = new RoutingImageReaderSpi();
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(png))) {
            provider.canDecodeInput(input);
        }
        assertInstanceOf(RoutingImageReader.class, provider.createReaderInstance(null));
    }

    @Test
    void routedReaderRequiresInputBeforeResolvingABackend() {
        RoutingImageReader reader =
                new RoutingImageReader(new RoutingImageReaderSpi());
        try {
            assertThrows(IllegalStateException.class, reader::backendId);
        } finally {
            reader.dispose();
        }
    }
}
