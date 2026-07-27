module io.github.ghosthack.imageio.apple {
    requires transitive io.github.ghosthack.imageio.common;

    exports io.github.ghosthack.imageio.apple;

    provides io.github.ghosthack.imageio.common.ImageDecoderBackend
            with io.github.ghosthack.imageio.apple.AppleImageBackend;
    provides io.github.ghosthack.imageio.common.RoutingBackend
            with io.github.ghosthack.imageio.apple.AppleImageBackend;
}
