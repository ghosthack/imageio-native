package io.github.ghosthack.imageio.apple;

import io.github.ghosthack.imageio.common.ImageDecoderBackend;
import io.github.ghosthack.imageio.common.RoutingBackend;

import javax.imageio.ImageReader;
import javax.imageio.spi.ImageReaderSpi;

/** Apple ImageIO backend service consumed by the common routing SPI. */
public final class AppleImageBackend implements ImageDecoderBackend {

    @Override
    public String id() {
        return "apple";
    }

    @Override
    public Kind kind() {
        return RoutingBackend.Kind.PLATFORM_NATIVE;
    }

    @Override
    public io.github.ghosthack.imageio.common.FormatRegistry formats() {
        return FormatRegistry.INSTANCE;
    }

    @Override
    public boolean isAvailable() {
        return AppleNative.IS_MACOS;
    }

    @Override
    public boolean canDecode(byte[] header, int length) {
        return AppleNative.canDecode(header, length);
    }

    @Override
    public ImageReader createReader(ImageReaderSpi routingProvider) {
        return new AppleImageReader(routingProvider);
    }
}
