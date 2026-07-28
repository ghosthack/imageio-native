package io.github.ghosthack.imageio.video;

import io.github.ghosthack.imageio.common.ImageReadParamSupport;
import io.github.ghosthack.imageio.common.NativeDecodeRequest;
import io.github.ghosthack.imageio.common.NativeDecodeResult;

import javax.imageio.IIOException;
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
 * Requires the input to be a
 * {@link io.github.ghosthack.imageio.common.PathAwareImageInputStream} so
 * that the file path can be passed to the native video extraction APIs.
 */
public class NativeVideoReader extends ImageReader {

    private static final long MAX_INTERMEDIATE_BYTES = Long.getLong(
            "imageio.native.maxIntermediateBytes", 512L * 1024 * 1024);

    private VideoFrameExtractorProvider provider;
    private volatile VideoInfo cachedInfo;

    public NativeVideoReader(ImageReaderSpi originatingProvider,
                             VideoFrameExtractorProvider extractorProvider) {
        super(originatingProvider);
        this.provider = extractorProvider;
    }

    public NativeVideoReader(ImageReaderSpi originatingProvider) {
        super(originatingProvider);
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
    public ImageReadParam getDefaultReadParam() {
        return ImageReadParamSupport.createDefaultReadParam();
    }

    @Override
    public BufferedImage read(int imageIndex, ImageReadParam param) throws IOException {
        checkIndex(imageIndex);
        ImageReadParamSupport.validate(param);
        guardIntermediateAllocation(param);
        Path path = getFilePath();
        processImageStarted(imageIndex);
        NativeDecodeRequest request = NativeDecodeRequest.from(param);
        NativeDecodeResult decoded;
        if (request.hasSourceRenderSize()) {
            BufferedImage image = provider().extractFrame(
                    path, Duration.ZERO, request.sourceRenderSize());
            decoded = NativeDecodeResult.sourceRendered(image);
        } else {
            decoded = NativeDecodeResult.fullSize(
                    provider().extractFrame(path, Duration.ZERO));
        }
        BufferedImage result = ImageReadParamSupport.apply(decoded, param);
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

    private void guardIntermediateAllocation(ImageReadParam param)
            throws IOException {
        VideoFrameExtractorProvider extractor = provider();
        boolean renderFallsBack = param != null
                && param.getSourceRenderSize() != null
                && !extractor.supportsRenderSizeReduction();
        boolean spatialFallsBack =
                NativeDecodeRequest.requestsSpatialSelection(param);
        if (!renderFallsBack && !spatialFallsBack) {
            return;
        }

        int width;
        int height;
        if (!renderFallsBack && param.getSourceRenderSize() != null) {
            width = param.getSourceRenderSize().width;
            height = param.getSourceRenderSize().height;
        } else {
            VideoInfo info = ensureInfo();
            width = info.width();
            height = info.height();
        }
        if ((long) width * height > MAX_INTERMEDIATE_BYTES / 4L) {
            throw new IIOException(
                    "Read parameters require a full "
                            + width + "x" + height
                            + " intermediate, exceeding imageio.native."
                            + "maxIntermediateBytes="
                            + MAX_INTERMEDIATE_BYTES);
        }
    }

    private void checkIndex(int imageIndex) throws IOException {
        if (imageIndex != 0) {
            throw new IndexOutOfBoundsException("imageIndex must be 0, got " + imageIndex);
        }
    }

    private Path getFilePath() throws IOException {
        Object in = getInput();
        if (in instanceof io.github.ghosthack.imageio.common.PathAwareImageInputStream pais) {
            return pais.getPath();
        }
        throw new IOException(
                "NativeVideoReader requires a path-aware ImageInputStream. "
                + "Use VideoFrameExtractor.extractFrame(Path, Duration) for direct access.");
    }

    private VideoInfo ensureInfo() throws IOException {
        VideoInfo info = cachedInfo;
        if (info == null) {
            info = provider().getInfo(getFilePath());
            cachedInfo = info;
        }
        return info;
    }

    private VideoFrameExtractorProvider provider() throws IOException {
        if (provider != null) return provider;
        VideoRouting.Decision decision = VideoRouting.select(getFilePath());
        if (decision == null) {
            throw new IOException("No imageio-native video backend owns this input");
        }
        provider = decision.backend();
        return provider;
    }
}
