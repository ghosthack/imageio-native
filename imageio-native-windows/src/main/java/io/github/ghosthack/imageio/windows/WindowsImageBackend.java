package io.github.ghosthack.imageio.windows;

import io.github.ghosthack.imageio.common.ImageDecoderBackend;
import io.github.ghosthack.imageio.common.RoutingBackend;
import io.github.ghosthack.panama.media.wic.WIC;

import javax.imageio.ImageReader;
import javax.imageio.spi.ImageReaderSpi;

/** Windows Imaging Component backend service consumed by the common router. */
public final class WindowsImageBackend implements ImageDecoderBackend {

    @Override
    public String id() {
        return "windows";
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
        return WIC.isAvailable();
    }

    @Override
    public boolean canDecode(byte[] header, int length) {
        return WIC.canDecode(header, length);
    }

    @Override
    public ImageReader createReader(ImageReaderSpi routingProvider) {
        return new WicImageReader(routingProvider);
    }
}
