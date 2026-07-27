module io.github.ghosthack.imageio.vips {
    requires transitive io.github.ghosthack.imageio.common;

    exports io.github.ghosthack.imageio.vips;

    provides io.github.ghosthack.imageio.common.ImageDecoderBackend
            with io.github.ghosthack.imageio.vips.VipsImageBackend;
    provides io.github.ghosthack.imageio.common.RoutingBackend
            with io.github.ghosthack.imageio.vips.VipsImageBackend;
}
