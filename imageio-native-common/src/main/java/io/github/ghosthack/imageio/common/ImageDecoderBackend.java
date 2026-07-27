package io.github.ghosthack.imageio.common;

import javax.imageio.ImageReader;
import javax.imageio.spi.ImageReaderSpi;

/**
 * Backend service consumed by the single still-image routing SPI.
 *
 * <p>Implementations are registered as this service, never as competing
 * {@link ImageReaderSpi} providers.</p>
 */
public interface ImageDecoderBackend extends RoutingBackend {

    FormatRegistry formats();

    boolean isAvailable();

    boolean canDecode(byte[] header, int length);

    ImageReader createReader(ImageReaderSpi routingProvider);
}
