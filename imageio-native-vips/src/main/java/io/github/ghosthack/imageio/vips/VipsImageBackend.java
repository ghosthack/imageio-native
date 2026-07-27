package io.github.ghosthack.imageio.vips;

import io.github.ghosthack.imageio.common.ImageDecoderBackend;
import io.github.ghosthack.imageio.common.RoutingBackend;

import javax.imageio.ImageReader;
import javax.imageio.spi.ImageReaderSpi;

/** libvips backend service consumed by the common routing SPI. */
public final class VipsImageBackend implements ImageDecoderBackend {

    @Override
    public String id() {
        return "vips";
    }

    @Override
    public Kind kind() {
        return RoutingBackend.Kind.PORTABLE;
    }

    @Override
    public io.github.ghosthack.imageio.common.FormatRegistry formats() {
        return FormatRegistry.INSTANCE;
    }

    @Override
    public boolean isAvailable() {
        return VipsNative.isAvailable();
    }

    @Override
    public boolean canDecode(byte[] header, int length) {
        return VipsNative.canDecode(header, length);
    }

    @Override
    public ImageReader createReader(ImageReaderSpi routingProvider) {
        return new VipsImageReader(routingProvider);
    }
}
