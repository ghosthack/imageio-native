module io.github.ghosthack.imageio.video.ffmpeg {
    requires transitive io.github.ghosthack.imageio.video.common;

    exports io.github.ghosthack.imageio.video.ffmpeg;

    provides io.github.ghosthack.imageio.common.RoutingBackend
            with io.github.ghosthack.imageio.video.ffmpeg.FFmpegVideoFrameExtractor;
    provides io.github.ghosthack.imageio.video.VideoFrameExtractorProvider
            with io.github.ghosthack.imageio.video.ffmpeg.FFmpegVideoFrameExtractor;
}
