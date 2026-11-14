package io.github.ghosthack.imageio.apple;

import io.github.ghosthack.imageio.common.ImageDecoderBackend;
import io.github.ghosthack.imageio.common.RoutingBackend;

import javax.imageio.ImageReader;
import javax.imageio.spi.ImageReaderSpi;
import java.util.Locale;

/** Apple ImageIO backend service consumed by the common routing SPI. */
public final class AppleImageBackend implements ImageDecoderBackend {

    private static final boolean IS_MACOS = System.getProperty("os.name", "")
            .toLowerCase(Locale.ROOT).contains("mac");

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
        return IS_MACOS;
    }

    @Override
    public boolean canDecode(byte[] header, int length) {
        return IS_MACOS && AppleNative.canDecode(header, length);
    }

    @Override
    public ImageReader createReader(ImageReaderSpi routingProvider) {
        return new AppleImageReader(routingProvider);
    }
}
