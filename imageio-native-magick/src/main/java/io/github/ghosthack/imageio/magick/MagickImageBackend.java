package io.github.ghosthack.imageio.magick;

import io.github.ghosthack.imageio.common.ImageDecoderBackend;
import io.github.ghosthack.imageio.common.RoutingBackend;

import javax.imageio.ImageReader;
import javax.imageio.spi.ImageReaderSpi;

/** ImageMagick backend service consumed by the common routing SPI. */
public final class MagickImageBackend implements ImageDecoderBackend {

    @Override
    public String id() {
        return "magick";
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
        return MagickNative.isAvailable();
    }

    @Override
    public boolean canDecode(byte[] header, int length) {
        return MagickNative.canDecode(header, length);
    }

    @Override
    public ImageReader createReader(ImageReaderSpi routingProvider) {
        return new MagickImageReader(routingProvider);
    }
}
