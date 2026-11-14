package io.github.ghosthack.imageio.common;

import javax.imageio.IIOException;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.event.IIOReadProgressListener;
import javax.imageio.event.IIOReadUpdateListener;
import javax.imageio.event.IIOReadWarningListener;
import javax.imageio.metadata.IIOMetadata;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Iterator;

/**
 * Reader returned by the routing SPI. It selects the owning backend from the
 * input that is actually set and delegates every operation to that backend's
 * reader.
 *
 * <p>Listeners registered here are forwarded to the backend reader, so the
 * events they receive name that reader as their source.</p>
 */
public final class RoutingImageReader extends ImageReader {

    private ImageReader delegate;
    private String backendId;

    RoutingImageReader(RoutingImageReaderSpi provider) {
        super(provider);
    }

    /**
     * Returns the ID of the backend that owns the current input, such as
     * {@code apple} or {@code vips}.
     *
     * @throws IIOException if no installed backend owns the input
     */
    public String backendId() throws IOException {
        delegate();
        return backendId;
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
        releaseDelegate();
    }

    @Override
    public void dispose() {
        releaseDelegate();
        super.dispose();
    }

    @Override
    public synchronized void abort() {
        super.abort();
        if (delegate != null) delegate.abort();
    }

    // ── Listener forwarding ─────────────────────────────────────────────

    @Override
    public void addIIOReadProgressListener(IIOReadProgressListener listener) {
        super.addIIOReadProgressListener(listener);
        if (delegate != null) delegate.addIIOReadProgressListener(listener);
    }

    @Override
    public void removeIIOReadProgressListener(IIOReadProgressListener listener) {
        super.removeIIOReadProgressListener(listener);
        if (delegate != null) delegate.removeIIOReadProgressListener(listener);
    }

    @Override
    public void removeAllIIOReadProgressListeners() {
        super.removeAllIIOReadProgressListeners();
        if (delegate != null) delegate.removeAllIIOReadProgressListeners();
    }

    @Override
    public void addIIOReadWarningListener(IIOReadWarningListener listener) {
        super.addIIOReadWarningListener(listener);
        if (delegate != null) delegate.addIIOReadWarningListener(listener);
    }

    @Override
    public void removeIIOReadWarningListener(IIOReadWarningListener listener) {
        super.removeIIOReadWarningListener(listener);
        if (delegate != null) delegate.removeIIOReadWarningListener(listener);
    }

    @Override
    public void removeAllIIOReadWarningListeners() {
        super.removeAllIIOReadWarningListeners();
        if (delegate != null) delegate.removeAllIIOReadWarningListeners();
    }

    @Override
    public void addIIOReadUpdateListener(IIOReadUpdateListener listener) {
        super.addIIOReadUpdateListener(listener);
        if (delegate != null) delegate.addIIOReadUpdateListener(listener);
    }

    @Override
    public void removeIIOReadUpdateListener(IIOReadUpdateListener listener) {
        super.removeIIOReadUpdateListener(listener);
        if (delegate != null) delegate.removeIIOReadUpdateListener(listener);
    }

    @Override
    public void removeAllIIOReadUpdateListeners() {
        super.removeAllIIOReadUpdateListeners();
        if (delegate != null) delegate.removeAllIIOReadUpdateListeners();
    }

    // ── Delegate lifecycle ──────────────────────────────────────────────

    private void releaseDelegate() {
        if (delegate != null) {
            delegate.dispose();
            delegate = null;
        }
        backendId = null;
    }

    private ImageReader delegate() throws IOException {
        if (delegate != null) return delegate;
        if (getInput() == null) {
            throw new IllegalStateException("No input set");
        }
        ImageRouting.Decision decision = ImageRouting.select(getInput());
        if (decision == null) {
            throw new IIOException("No imageio-native backend owns this input");
        }
        ImageReader reader = decision.backend().createReader(getOriginatingProvider());
        if (progressListeners != null) {
            progressListeners.forEach(reader::addIIOReadProgressListener);
        }
        if (warningListeners != null) {
            warningListeners.forEach(reader::addIIOReadWarningListener);
        }
        if (updateListeners != null) {
            updateListeners.forEach(reader::addIIOReadUpdateListener);
        }
        reader.setInput(getInput(), isSeekForwardOnly(), isIgnoringMetadata());
        delegate = reader;
        backendId = decision.backend().id();
        return delegate;
    }
}
