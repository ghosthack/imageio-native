module io.github.ghosthack.imageio.video.apple {
    requires transitive io.github.ghosthack.imageio.video.common;
    requires io.github.ghosthack.panama.media.core;
    requires io.github.ghosthack.panama.media.corefoundation;
    requires io.github.ghosthack.panama.media.avfoundation;

    exports io.github.ghosthack.imageio.video.apple;

    provides io.github.ghosthack.imageio.common.RoutingBackend
            with io.github.ghosthack.imageio.video.apple.AppleVideoFrameExtractor;
    provides io.github.ghosthack.imageio.video.VideoFrameExtractorProvider
            with io.github.ghosthack.imageio.video.apple.AppleVideoFrameExtractor;
}
