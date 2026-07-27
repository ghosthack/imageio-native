module io.github.ghosthack.imageio.video.common {
    requires transitive io.github.ghosthack.imageio.common;

    exports io.github.ghosthack.imageio.video;

    uses io.github.ghosthack.imageio.video.VideoFrameExtractorProvider;

    provides javax.imageio.spi.ImageReaderSpi
            with io.github.ghosthack.imageio.video.VideoRoutingImageReaderSpi;
}
