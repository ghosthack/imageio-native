package io.github.ghosthack.imageio.common;

import javax.imageio.IIOException;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.metadata.IIOMetadata;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Iterator;

/**
 * Deferred reader used when a caller creates the routing SPI by format name
 * rather than by probing an input first.
 */
public final class RoutingImageReader extends ImageReader {

    private ImageReader delegate;

    RoutingImageReader(RoutingImageReaderSpi provider) {
        super(provider);
    }

    @Override
    public int getNumImages(boolean allowSearch) throws IOException {
        return delegate().getNumImages(allowSearch);
    }

    @Override
    public int getWidth(int imageIndex) throws IOException {
        return delegate().getWidth(imageIndex);
    }

    @Override
    public int getHeight(int imageIndex) throws IOException {
        return delegate().getHeight(imageIndex);
    }

    @Override
    public Iterator<ImageTypeSpecifier> getImageTypes(int imageIndex) throws IOException {
        return delegate().getImageTypes(imageIndex);
    }

    @Override
    public IIOMetadata getStreamMetadata() throws IOException {
        return delegate().getStreamMetadata();
    }

    @Override
    public IIOMetadata getImageMetadata(int imageIndex) throws IOException {
        return delegate().getImageMetadata(imageIndex);
    }

    @Override
    public ImageReadParam getDefaultReadParam() {
        return ImageReadParamSupport.createDefaultReadParam();
    }

    @Override
    public BufferedImage read(int imageIndex, ImageReadParam param) throws IOException {
        return delegate().read(imageIndex, param);
    }

    @Override
    public void setInput(Object input, boolean seekForwardOnly, boolean ignoreMetadata) {
        super.setInput(input, seekForwardOnly, ignoreMetadata);
        if (delegate != null) {
            delegate.dispose();
            delegate = null;
        }
    }

    @Override
    public void dispose() {
        if (delegate != null) {
            delegate.dispose();
            delegate = null;
        }
        super.dispose();
    }

    private ImageReader delegate() throws IOException {
        if (delegate != null) return delegate;
        ImageRouting.Decision decision = ImageRouting.select(getInput());
        if (decision == null) {
            throw new IIOException("No imageio-native backend owns this input");
        }
        delegate = decision.backend().createReader(getOriginatingProvider());
        delegate.setInput(getInput(), isSeekForwardOnly(), isIgnoringMetadata());
        return delegate;
    }
}
