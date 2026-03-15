package io.github.ghosthack.imageio.common;

import javax.imageio.IIOException;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
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
 *   <li>When the input is file-backed and a subclass overrides the
 *       path-based hooks ({@link #nativeGetSizeFromPath},
 *       {@link #nativeDecodeFromPath}), the file path is passed directly
 *       to the native decoder — avoiding loading the entire file into the
 *       Java heap.</li>
 * </ul>
 */
public abstract class NativeImageReader extends ImageReader {

    /** Cached dimensions from the lightweight native size query. */
    private int[] cachedSize;

    /** Cached raw image bytes from a prior size query, reused by {@link #read}. */
    private byte[] cachedData;

    /** Cached file path from a prior path-based size query, reused by {@link #read}. */
    private String cachedPath;

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

    // ── Optional path-based hooks (subclasses override for zero-copy) ──

    /**
     * Returns image dimensions by reading directly from a file path, without
     * loading the file into the Java heap.
     * <p>
     * The default implementation returns {@code null}, which signals the
     * caller to fall back to the byte[]-based {@link #nativeGetSize}.
     * Subclasses should override this when the native decoder supports
     * path/URL-based input (e.g. {@code CGImageSourceCreateWithURL} on macOS,
     * {@code CreateDecoderFromFilename} on Windows).
     *
     * @param path absolute path to the image file
     * @return dimensions as {@code [width, height]}, or {@code null} if
     *         path-based access is not supported
     */
    protected int[] nativeGetSizeFromPath(String path) throws IOException {
        return null;
    }

    /**
     * Decodes an image directly from a file path, without loading the file
     * into the Java heap.
     * <p>
     * The default implementation returns {@code null}, which signals the
     * caller to fall back to the byte[]-based {@link #nativeDecode}.
     *
     * @param path absolute path to the image file
     * @return decoded image, or {@code null} if path-based access is not supported
     */
    protected BufferedImage nativeDecodeFromPath(String path) throws IOException {
        return null;
    }

    // ── Lifecycle ───────────────────────────────────────────────────────

    @Override
    public void setInput(Object input, boolean seekForwardOnly, boolean ignoreMetadata) {
        super.setInput(input, seekForwardOnly, ignoreMetadata);
        cachedSize = null;
        cachedData = null;
        cachedPath = null;
    }

    @Override
    public void dispose() {
        cachedSize = null;
        cachedData = null;
        cachedPath = null;
        super.dispose();
    }

    // ── Dimension queries (lightweight, no pixel decode) ────────────────

    private int[] ensureSize() throws IOException {
        if (cachedSize != null) return cachedSize;

        // Fast path: file-backed input → pass path directly to native decoder
        String path = inputFilePath();
        if (path != null) {
            int[] size = nativeGetSizeFromPath(path);
            if (size != null) {
                cachedSize = size;
                cachedPath = path;
                return size;
            }
        }

        // Fallback: read entire file into byte[]
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

        // Fast path: decode directly from file path (no Java heap copy)
        String path = cachedPath;
        cachedPath = null;
        if (path == null) path = inputFilePath();
        if (path != null) {
            BufferedImage result = nativeDecodeFromPath(path);
            if (result != null) {
                cachedData = null;
                processImageStarted(imageIndex);
                processImageProgress(100.0f);
                processImageComplete();
                return result;
            }
        }

        // Fallback: decode from byte[]
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

    /**
     * Validates the {@code ImageReadParam}.
     * <p>
     * This reader always decodes the full image at full resolution into a
     * {@code TYPE_INT_ARGB_PRE} buffer, so source region, subsampling,
     * destination offset, and destination image are silently ignored
     * (per the {@link ImageReader} contract for unsupported parameters).
     * <p>
     * The only check retained is {@code destinationType}: if the caller
     * explicitly requests a type other than {@code TYPE_INT_ARGB_PRE},
     * we reject it because the output type is fixed.
     */
    private static void checkParam(ImageReadParam param) throws IIOException {
        if (param == null) return;
        // Reject an explicit destination type that conflicts with our output
        ImageTypeSpecifier destType = param.getDestinationType();
        if (destType != null
                && destType.getBufferedImageType() != BufferedImage.TYPE_INT_ARGB_PRE)
            throw new IIOException("Only TYPE_INT_ARGB_PRE is supported as destination type");
        // All other unsupported params (region, subsampling, destination,
        // offset) are silently ignored per the ImageReader contract.
    }

    /**
     * Attempts to extract a file path from the current input stream.
     * <p>
     * Uses duck-typing: if the input object has a public {@code getPath()}
     * method returning a {@link Path}, the path is extracted and returned
     * as a string.  This works transparently with
     * {@code PathAwareImageInputStream} (from the video module) and any
     * future stream implementation that exposes a file path.
     *
     * @return absolute file path, or {@code null} if the input is not file-backed
     */
    private String inputFilePath() {
        Object in = getInput();
        if (in == null) return null;
        try {
            java.lang.reflect.Method m = in.getClass().getMethod("getPath");
            if (Path.class.isAssignableFrom(m.getReturnType())) {
                Object result = m.invoke(in);
                return result != null ? result.toString() : null;
            }
        } catch (Exception ignored) {
            // Not a path-aware stream
        }
        return null;
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
