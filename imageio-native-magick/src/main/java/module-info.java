module io.github.ghosthack.imageio.magick {
    requires transitive io.github.ghosthack.imageio.common;

    exports io.github.ghosthack.imageio.magick;

    provides io.github.ghosthack.imageio.common.ImageDecoderBackend
            with io.github.ghosthack.imageio.magick.MagickImageBackend;
    provides io.github.ghosthack.imageio.common.RoutingBackend
            with io.github.ghosthack.imageio.magick.MagickImageBackend;
}
