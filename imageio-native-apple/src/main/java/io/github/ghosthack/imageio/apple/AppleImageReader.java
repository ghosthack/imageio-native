package io.github.ghosthack.imageio.apple;

import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.List;

/**
 * An {@link ImageReader} that delegates to Apple's CGImageSource via Project Panama.
 * <p>
 * Supports any format that macOS ImageIO can decode (HEIC, AVIF, WEBP, …).
 * The format-specific detection is handled by the corresponding
 * {@link ImageReaderSpi} subclasses; this reader simply decodes whatever bytes
 * it receives.
 * <p>
 * Behaviour:
 * <ul>
 *   <li>Decode is performed lazily on first access to width/height/pixels.</li>
 *   <li>The decoded {@link BufferedImage} is cached until {@link #dispose()}
 *       or a new input is set.</li>
 *   <li>Only still-image index 0 is supported ({@code getNumImages} returns 1).</li>
 * </ul>
 */
public class AppleImageReader extends ImageReader {

    private BufferedImage cachedImage;

    protected AppleImageReader(ImageReaderSpi originatingProvider) {
        super(originatingProvider);
    }

    // ── Lifecycle ───────────────────────────────────────────────────────

    @Override
    public void setInput(Object input, boolean seekForwardOnly, boolean ignoreMetadata) {
        super.setInput(input, seekForwardOnly, ignoreMetadata);
        cachedImage = null; // invalidate cache on new input
    }

    @Override
    public void dispose() {
        cachedImage = null;
        super.dispose();
    }

    // ── Lazy decode ─────────────────────────────────────────────────────

    private void ensureDecoded() throws IOException {
        if (cachedImage != null) return;

        ImageInputStream stream = (ImageInputStream) getInput();
        if (stream == null)
            throw new IllegalStateException("No input set");

        byte[] data = readAllBytes(stream);
        cachedImage = AppleNative.decode(data);
    }

    // ── ImageReader contract ────────────────────────────────────────────

    @Override
    public int getNumImages(boolean allowSearch) throws IOException {
        return 1; // still images only
    }

    @Override
    public int getWidth(int imageIndex) throws IOException {
        checkIndex(imageIndex);
        ensureDecoded();
        return cachedImage.getWidth();
    }

    @Override
    public int getHeight(int imageIndex) throws IOException {
        checkIndex(imageIndex);
        ensureDecoded();
        return cachedImage.getHeight();
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
        ensureDecoded();
        return cachedImage;
    }

    @Override
    public IIOMetadata getStreamMetadata() {
        return null; // no stream metadata
    }

    @Override
    public IIOMetadata getImageMetadata(int imageIndex) throws IOException {
        checkIndex(imageIndex);
        return null; // no image metadata (future enhancement)
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private static void checkIndex(int imageIndex) {
        if (imageIndex != 0)
            throw new IndexOutOfBoundsException("Only image index 0 is supported, got: " + imageIndex);
    }

    private static byte[] readAllBytes(ImageInputStream stream) throws IOException {
        stream.seek(0);
        ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
        byte[] buf = new byte[8192];
        int n;
        while ((n = stream.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }
}
