module io.github.ghosthack.imageio.video.windows {
    requires transitive io.github.ghosthack.imageio.video.common;
    requires io.github.ghosthack.panama.media.core;
    requires io.github.ghosthack.panama.media.mediafoundation;

    exports io.github.ghosthack.imageio.video.windows;

    provides io.github.ghosthack.imageio.common.RoutingBackend
            with io.github.ghosthack.imageio.video.windows.WindowsVideoFrameExtractor;
    provides io.github.ghosthack.imageio.video.VideoFrameExtractorProvider
            with io.github.ghosthack.imageio.video.windows.WindowsVideoFrameExtractor;
}
