package io.github.ghosthack.imageio.video;

import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;

/**
 * ImageReader that extracts the poster frame from a video file.
 * <p>
 * This reader supports a single image (the poster frame at t=0).
 * It requires the input to be a {@link PathAwareImageInputStream} so that
 * the file path can be passed to the native video extraction APIs.
 * <p>
 * For time-based extraction or multi-frame access, use
 * {@link VideoFrameExtractor} directly.
 */
class VideoFrameReader extends ImageReader {

    VideoFrameReader(ImageReaderSpi originatingProvider) {
        super(originatingProvider);
    }

    @Override
    public int getNumImages(boolean allowSearch) {
        return 1;
    }

    @Override
    public int getWidth(int imageIndex) throws IOException {
        checkIndex(imageIndex);
        return getInfo().width();
    }

    @Override
    public int getHeight(int imageIndex) throws IOException {
        checkIndex(imageIndex);
        return getInfo().height();
    }

    @Override
    public Iterator<ImageTypeSpecifier> getImageTypes(int imageIndex) throws IOException {
        checkIndex(imageIndex);
        return List.of(
                ImageTypeSpecifier.createFromBufferedImageType(BufferedImage.TYPE_INT_ARGB_PRE)
        ).iterator();
    }

    @Override
    public BufferedImage read(int imageIndex, ImageReadParam param) throws IOException {
        checkIndex(imageIndex);
        Path path = getFilePath();
        return VideoFrameExtractor.extractThumbnail(path);
    }

    @Override
    public IIOMetadata getStreamMetadata() {
        return null;
    }

    @Override
    public IIOMetadata getImageMetadata(int imageIndex) {
        return null;
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private void checkIndex(int imageIndex) throws IOException {
        if (imageIndex != 0) {
            throw new IndexOutOfBoundsException("imageIndex must be 0, got " + imageIndex);
        }
    }

    private Path getFilePath() throws IOException {
        Object in = getInput();
        if (in instanceof PathAwareImageInputStream pais) {
            return pais.getPath();
        }
        throw new IOException(
                "VideoFrameReader requires PathAwareImageInputStream input. " +
                "Use VideoFrameExtractor.extractFrame(Path, Duration) for direct access.");
    }

    private volatile VideoInfo info;

    private VideoInfo getInfo() throws IOException {
        if (info == null) {
            info = VideoFrameExtractor.getInfo(getFilePath());
        }
        return info;
    }
}
