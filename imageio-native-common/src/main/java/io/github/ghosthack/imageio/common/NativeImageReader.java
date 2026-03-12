package io.github.ghosthack.imageio.common;

import javax.imageio.IIOException;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.stream.ImageInputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

/**
 * Shared {@link ImageReader} base for platform-native image decoders.
 * <p>
 * Subclasses supply two hooks — a lightweight dimension query and a full
 * pixel decode — and this class provides the complete {@code ImageReader}
 * contract including {@code ImageReadParam} validation, forward-only stream
 * handling, and dimension caching.
 * <p>
 * Behaviour:
 * <ul>
 *   <li>Dimension queries ({@code getWidth}, {@code getHeight}) use the
 *       lightweight {@link #nativeGetSize} — no pixel decode.</li>
 *   <li>Full pixel decode happens only in {@link #read}.</li>
 *   <li>Only still-image index 0 is supported.</li>
 * </ul>
 */
public abstract class NativeImageReader extends ImageReader {

    /** Cached dimensions from the lightweight native size query. */
    private int[] cachedSize;

    /** Cached raw image bytes from a prior size query, reused by {@link #read}. */
    private byte[] cachedData;

    protected NativeImageReader(ImageReaderSpi originatingProvider) {
        super(originatingProvider);
    }

    // ── Platform hooks (implemented by subclasses) ──────────────────────

    /**
     * Returns image dimensions as {@code [width, height]} via a lightweight
     * native metadata query.  Must not perform a full pixel decode.
     */
    protected abstract int[] nativeGetSize(byte[] data) throws IOException;

    /**
     * Decodes raw image bytes through the platform's native decoder.
     *
     * @return decoded image ({@code TYPE_INT_ARGB_PRE})
     */
    protected abstract BufferedImage nativeDecode(byte[] data) throws IOException;

    // ── Lifecycle ───────────────────────────────────────────────────────

    @Override
    public void setInput(Object input, boolean seekForwardOnly, boolean ignoreMetadata) {
        super.setInput(input, seekForwardOnly, ignoreMetadata);
        cachedSize = null;
        cachedData = null;
    }

    @Override
    public void dispose() {
        cachedSize = null;
        cachedData = null;
        super.dispose();
    }

    // ── Dimension queries (lightweight, no pixel decode) ────────────────

    private int[] ensureSize() throws IOException {
        if (cachedSize != null) return cachedSize;
        byte[] data = readAllBytes((ImageInputStream) getInput());
        cachedSize = nativeGetSize(data);
        cachedData = data;
        return cachedSize;
    }

    // ── ImageReader contract ────────────────────────────────────────────

    @Override
    public int getNumImages(boolean allowSearch) throws IOException {
        return 1;
    }

    @Override
    public int getWidth(int imageIndex) throws IOException {
        checkIndex(imageIndex);
        return ensureSize()[0];
    }

    @Override
    public int getHeight(int imageIndex) throws IOException {
        checkIndex(imageIndex);
        return ensureSize()[1];
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
        checkParam(param);
        byte[] data = cachedData;
        cachedData = null;          // allow GC after decode
        if (data == null) {
            data = readAllBytes((ImageInputStream) getInput());
        }
        processImageStarted(imageIndex);
        BufferedImage result = nativeDecode(data);
        processImageProgress(100.0f);
        processImageComplete();
        return result;
    }

    @Override
    public IIOMetadata getStreamMetadata() {
        return null;
    }

    @Override
    public IIOMetadata getImageMetadata(int imageIndex) throws IOException {
        checkIndex(imageIndex);
        return null;
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private static void checkIndex(int imageIndex) {
        if (imageIndex != 0)
            throw new IndexOutOfBoundsException("Only image index 0 is supported, got: " + imageIndex);
    }

    private static void checkParam(ImageReadParam param) throws IIOException {
        if (param == null) return;
        if (param.getSourceRegion() != null)
            throw new IIOException("Source region selection is not supported by this reader");
        if (param.getSourceXSubsampling() != 1 || param.getSourceYSubsampling() != 1)
            throw new IIOException("Subsampling is not supported by this reader");
        Point destOffset = param.getDestinationOffset();
        if (destOffset != null && (destOffset.x != 0 || destOffset.y != 0))
            throw new IIOException("Destination offset is not supported by this reader");
        if (param.getDestination() != null)
            throw new IIOException("Destination image is not supported by this reader");
        ImageTypeSpecifier destType = param.getDestinationType();
        if (destType != null
                && destType.getBufferedImageType() != BufferedImage.TYPE_INT_ARGB_PRE)
            throw new IIOException("Only TYPE_INT_ARGB_PRE is supported as destination type");
    }

    private byte[] readAllBytes(ImageInputStream stream) throws IOException {
        if (stream == null)
            throw new IllegalStateException("No input set");
        if (!isSeekForwardOnly()) {
            stream.seek(0);
        }

        // Fast path: known length — single allocation, no resize overhead
        long streamLength = stream.length();
        if (streamLength > 0 && streamLength <= Integer.MAX_VALUE) {
            byte[] data = new byte[(int) streamLength];
            int off = 0;
            while (off < data.length) {
                int n = stream.read(data, off, data.length - off);
                if (n < 0) break;
                off += n;
            }
            return (off == data.length) ? data : Arrays.copyOf(data, off);
        }

        // Slow path: unknown length — read directly into a growing buffer (64 KB chunks)
        byte[] data = new byte[65536];
        int total = 0;
        while (true) {
            if (total == data.length) {
                int newLen = (int) Math.min((long) data.length * 2, Integer.MAX_VALUE - 8);
                if (newLen == data.length)
                    throw new IOException("Image data exceeds maximum buffer size (" + newLen + " bytes)");
                data = Arrays.copyOf(data, newLen);
            }
            int n = stream.read(data, total, data.length - total);
            if (n < 0) break;
            total += n;
        }
        return (total == data.length) ? data : Arrays.copyOf(data, total);
    }
}
