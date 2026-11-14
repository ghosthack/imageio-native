package io.github.ghosthack.imageio.windows;

import io.github.ghosthack.imageio.common.ImageDecoderBackend;
import io.github.ghosthack.imageio.common.RoutingBackend;
import io.github.ghosthack.panama.media.wic.WIC;

import javax.imageio.ImageReader;
import javax.imageio.spi.ImageReaderSpi;
import java.util.Locale;

/** Windows Imaging Component backend service consumed by the common router. */
public final class WindowsImageBackend implements ImageDecoderBackend {

    private static final boolean IS_WINDOWS = System.getProperty("os.name", "")
            .toLowerCase(Locale.ROOT).contains("windows");

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
        return IS_WINDOWS && WIC.isAvailable();
    }

    @Override
    public boolean canDecode(byte[] header, int length) {
        return IS_WINDOWS && WIC.canDecode(header, length);
    }

    @Override
    public ImageReader createReader(ImageReaderSpi routingProvider) {
        return new WicImageReader(routingProvider);
    }
}
