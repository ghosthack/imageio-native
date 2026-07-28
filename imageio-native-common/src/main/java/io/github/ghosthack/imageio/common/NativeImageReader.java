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
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

/**
 * Shared {@link ImageReader} base for platform-native image decoders.
 * <p>
 * Subclasses supply a lightweight dimension query and pixel decode hooks.
 * Capability-aware overloads may apply safe {@code ImageReadParam} reductions
 * before materializing Java pixels. This class provides the complete
 * {@code ImageReader} contract including parameter validation, forward-only
 * stream handling, and dimension caching.
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
 *   <li>Other inputs are spooled to a temporary file in fixed-size chunks
 *       before using the path-based hooks, keeping encoded image data out of
 *       the Java heap when those hooks are supported.</li>
 * </ul>
 */
public abstract class NativeImageReader extends ImageReader {

    /** Cached dimensions from the lightweight native size query. */
    private int[] cachedSize;

    /** Cached raw image bytes from a prior size query, reused by {@link #read}. */
    private byte[] cachedData;

    /** Cached path input from a prior size query, reused by {@link #read}. */
    private PathInput cachedPath;

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

    /**
     * Decodes bytes while optionally applying safe read operations.
     * Subclasses may override this hook and must accurately report applied
     * operations in the returned result.
     */
    protected NativeDecodeResult nativeDecode(
            byte[] data, NativeDecodeRequest request) throws IOException {
        return NativeDecodeResult.fullSize(nativeDecode(data));
    }

    /**
     * Returns whether this backend can apply exact source regions and
     * subsampling before materializing its Java image.
     */
    protected boolean supportsNativeSpatialSelection() {
        return false;
    }

    /**
     * Returns whether this backend reduces native output to the requested
     * source render size.
     */
    protected boolean supportsNativeSourceRenderSize() {
        return false;
    }

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

    /**
     * Path-based counterpart to
     * {@link #nativeDecode(byte[], NativeDecodeRequest)}.
     */
    protected NativeDecodeResult nativeDecodeFromPath(
            String path, NativeDecodeRequest request) throws IOException {
        BufferedImage image = nativeDecodeFromPath(path);
        return image != null ? NativeDecodeResult.fullSize(image) : null;
    }

    // ── Lifecycle ───────────────────────────────────────────────────────

    @Override
    public void setInput(Object input, boolean seekForwardOnly, boolean ignoreMetadata) {
        discardCachedPath();
        super.setInput(input, seekForwardOnly, ignoreMetadata);
        cachedSize = null;
        cachedData = null;
    }

    @Override
    public void dispose() {
        discardCachedPath();
        cachedSize = null;
        cachedData = null;
        super.dispose();
    }

    // ── Dimension queries (lightweight, no pixel decode) ────────────────

    private int[] ensureSize() throws IOException {
        if (cachedSize != null) return cachedSize;

        // Fast path: file-backed input → pass path directly to native decoder
        PathInput pathInput = inputPath();
        if (pathInput == null) {
            pathInput = spoolToTemporaryFile((ImageInputStream) getInput());
        }
        byte[] fallbackData = null;
        try {
            int[] size = nativeGetSizeFromPath(pathInput.path().toString());
            if (size != null) {
                cachedSize = size;
                cachedPath = pathInput;
                pathInput = null;
                return size;
            }
            if (pathInput.temporary()) {
                fallbackData = Files.readAllBytes(pathInput.path());
            }
        } finally {
            deleteIfTemporary(pathInput);
        }

        // Compatibility fallback for subclasses without path-based hooks.
        byte[] data = fallbackData != null
                ? fallbackData
                : readAllBytes((ImageInputStream) getInput());
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
    public ImageReadParam getDefaultReadParam() {
        return ImageReadParamSupport.createDefaultReadParam();
    }

    @Override
    public BufferedImage read(int imageIndex, ImageReadParam param) throws IOException {
        checkIndex(imageIndex);
        ImageReadParamSupport.validate(param);
        guardIntermediateAllocation(param);
        NativeDecodeRequest decodeRequest;
        if (supportsNativeSpatialSelection()
                && NativeDecodeRequest.requestsSpatialSelection(param)) {
            int[] sourceSize = ensureSize();
            decodeRequest = NativeDecodeRequest.from(
                    param, sourceSize[0], sourceSize[1]);
        } else {
            decodeRequest = NativeDecodeRequest.from(param);
        }

        // Fast path: decode directly from file path (no Java heap copy)
        PathInput pathInput = cachedPath;
        cachedPath = null;
        if (pathInput == null) pathInput = inputPath();
        if (pathInput == null && cachedData == null) {
            pathInput = spoolToTemporaryFile((ImageInputStream) getInput());
        }
        try {
            if (pathInput != null) {
                NativeDecodeResult decoded =
                        nativeDecodeFromPath(
                                pathInput.path().toString(), decodeRequest);
                if (decoded != null) {
                    cachedData = null;
                    processImageStarted(imageIndex);
                    BufferedImage result =
                            ImageReadParamSupport.apply(decoded, param);
                    processImageProgress(100.0f);
                    processImageComplete();
                    return result;
                }
            }

            // Compatibility fallback for subclasses without path-based hooks.
            byte[] data = cachedData;
            cachedData = null;          // allow GC after decode
            if (data == null) {
                data = pathInput != null && pathInput.temporary()
                        ? Files.readAllBytes(pathInput.path())
                        : readAllBytes((ImageInputStream) getInput());
            }
            processImageStarted(imageIndex);
            NativeDecodeResult decoded = nativeDecode(data, decodeRequest);
            BufferedImage result = ImageReadParamSupport.apply(decoded, param);
            processImageProgress(100.0f);
            processImageComplete();
            return result;
        } finally {
            deleteIfTemporary(pathInput);
        }
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

    private void guardIntermediateAllocation(ImageReadParam param)
            throws IOException {
        boolean renderFallsBack = param != null
                && param.getSourceRenderSize() != null
                && !supportsNativeSourceRenderSize();
        boolean spatialFallsBack =
                NativeDecodeRequest.requestsSpatialSelection(param)
                && !supportsNativeSpatialSelection();
        if (!renderFallsBack && !spatialFallsBack) {
            return;
        }

        int width;
        int height;
        if (!renderFallsBack && param.getSourceRenderSize() != null) {
            width = param.getSourceRenderSize().width;
            height = param.getSourceRenderSize().height;
        } else {
            int[] sourceSize = ensureSize();
            width = sourceSize[0];
            height = sourceSize[1];
        }
        ImageReadParamSupport.validateIntermediateDimensions(width, height);
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
    private PathInput inputPath() {
        Object in = getInput();
        if (in == null) return null;
        try {
            java.lang.reflect.Method m = in.getClass().getMethod("getPath");
            if (Path.class.isAssignableFrom(m.getReturnType())) {
                Object result = m.invoke(in);
                return result != null ? new PathInput((Path) result, false) : null;
            }
        } catch (Exception ignored) {
            // Not a path-aware stream
        }
        return null;
    }

    /**
     * Copies a non-path input to disk without retaining the encoded image in
     * the Java heap. The returned file belongs to this reader and must be
     * deleted after decoding or when the reader is reset/disposed.
     */
    private PathInput spoolToTemporaryFile(ImageInputStream stream) throws IOException {
        if (stream == null)
            throw new IllegalStateException("No input set");
        if (!isSeekForwardOnly()) {
            stream.seek(0);
        }

        Path path = Files.createTempFile("imageio-native-", ".image");
        boolean complete = false;
        try (OutputStream output = Files.newOutputStream(path)) {
            byte[] buffer = new byte[65536];
            int read;
            while ((read = stream.read(buffer)) >= 0) {
                if (read > 0) {
                    output.write(buffer, 0, read);
                    if (isSeekForwardOnly()) {
                        stream.flushBefore(stream.getStreamPosition());
                    }
                }
            }
            complete = true;
            return new PathInput(path, true);
        } finally {
            if (!complete) Files.deleteIfExists(path);
        }
    }

    private void discardCachedPath() {
        PathInput pathInput = cachedPath;
        cachedPath = null;
        deleteIfTemporary(pathInput);
    }

    private static void deleteIfTemporary(PathInput pathInput) {
        if (pathInput == null || !pathInput.temporary()) return;
        try {
            Files.deleteIfExists(pathInput.path());
        } catch (IOException ignored) {
            pathInput.path().toFile().deleteOnExit();
        }
    }

    private record PathInput(Path path, boolean temporary) {}

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
