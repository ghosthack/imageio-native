module io.github.ghosthack.imageio.windows {
    requires transitive io.github.ghosthack.imageio.common;
    requires io.github.ghosthack.panama.media.core;
    requires io.github.ghosthack.panama.media.wic;

    exports io.github.ghosthack.imageio.windows;

    provides io.github.ghosthack.imageio.common.ImageDecoderBackend
            with io.github.ghosthack.imageio.windows.WindowsImageBackend;
    provides io.github.ghosthack.imageio.common.RoutingBackend
            with io.github.ghosthack.imageio.windows.WindowsImageBackend;
}
