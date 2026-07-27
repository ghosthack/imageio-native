module io.github.ghosthack.imageio.common {
    requires transitive java.desktop;

    exports io.github.ghosthack.imageio.common;

    uses io.github.ghosthack.imageio.common.ImageDecoderBackend;
    uses io.github.ghosthack.imageio.common.RoutingBackend;

    provides javax.imageio.spi.ImageReaderSpi
            with io.github.ghosthack.imageio.common.RoutingImageReaderSpi;
}
