package io.github.ghosthack.imageio.video;

import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.spi.ImageReaderSpi;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;

/**
 * {@link ImageReader} that extracts the poster frame from a video file.
 * <p>
 * This is a concrete class (not abstract) that delegates to a
 * {@link VideoFrameExtractorProvider} for actual frame extraction.  Each
 * video backend's SPI creates an instance of this class with its provider.
 * <p>
 * Supports a single image (the poster frame at t=0).  For time-based
 * extraction or multi-frame access, use {@link VideoFrameExtractor} directly.
 * <p>
 * Requires the input to be a {@link PathAwareImageInputStream} so that
 * the file path can be passed to the native video extraction APIs.
 */
public class NativeVideoReader extends ImageReader {

    private final VideoFrameExtractorProvider provider;
    private volatile VideoInfo cachedInfo;

    public NativeVideoReader(ImageReaderSpi originatingProvider,
                             VideoFrameExtractorProvider extractorProvider) {
        super(originatingProvider);
        this.provider = extractorProvider;
    }

    @Override
    public int getNumImages(boolean allowSearch) {
        return 1;
    }

    @Override
    public int getWidth(int imageIndex) throws IOException {
        checkIndex(imageIndex);
        return ensureInfo().width();
    }

    @Override
    public int getHeight(int imageIndex) throws IOException {
        checkIndex(imageIndex);
        return ensureInfo().height();
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
        processImageStarted(imageIndex);
        BufferedImage result = provider.extractFrame(path, Duration.ZERO);
        processImageProgress(100.0f);
        processImageComplete();
        return result;
    }

    @Override
    public IIOMetadata getStreamMetadata() {
        return null;
    }

    @Override
    public IIOMetadata getImageMetadata(int imageIndex) {
        return null;
    }

    // ── Lifecycle ───────────────────────────────────────────────────────

    @Override
    public void setInput(Object input, boolean seekForwardOnly, boolean ignoreMetadata) {
        super.setInput(input, seekForwardOnly, ignoreMetadata);
        cachedInfo = null;
    }

    @Override
    public void dispose() {
        cachedInfo = null;
        super.dispose();
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
                "NativeVideoReader requires PathAwareImageInputStream input. "
                + "Use VideoFrameExtractor.extractFrame(Path, Duration) for direct access.");
    }

    private VideoInfo ensureInfo() throws IOException {
        VideoInfo info = cachedInfo;
        if (info == null) {
            info = provider.getInfo(getFilePath());
            cachedInfo = info;
        }
        return info;
    }
}
