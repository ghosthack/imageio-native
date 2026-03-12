package io.github.ghosthack.imageio.apple;

import javax.imageio.IIOException;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.Set;

/**
 * Direct public API for Apple ImageIO decoding via Project Panama.
 * <p>
 * Use this when you want more control than {@code javax.imageio.ImageIO.read()}
 * provides — meaningful exceptions, codec availability checks, or lightweight
 * dimension queries without full pixel decode.
 * <p>
 * All methods are safe to call on any OS. On non-macOS platforms,
 * {@link #isAvailable()} returns {@code false} and decode methods throw
 * {@link IIOException} with a clear message.
 *
 * <pre>{@code
 * if (AppleImageio.isAvailable()) {
 *     Dimension size = AppleImageio.getSize(bytes);
 *     BufferedImage img = AppleImageio.decode(bytes);
 * }
 * }</pre>
 */
public final class AppleImageio {

    private AppleImageio() {}

    /**
     * Returns {@code true} if the Apple ImageIO backend is available
     * (i.e., the current OS is macOS).
     */
    public static boolean isAvailable() {
        return AppleNative.IS_MACOS;
    }

    /**
     * Probes whether Apple's CGImageSource can decode the given data.
     *
     * @param header first bytes of the image (4 KB is sufficient)
     * @param length number of valid bytes in {@code header}
     * @return {@code true} if the format is recognised
     */
    public static boolean canDecode(byte[] header, int length) {
        return AppleNative.canDecode(header, length);
    }

    private static final Set<String> ACTIVE_FORMATS  = Set.of(FormatRegistry.activeFormatNames());
    private static final Set<String> ACTIVE_SUFFIXES = Set.of(FormatRegistry.activeSuffixes());

    /**
     * Returns the set of format names currently active, as controlled by
     * the {@code imageio.native.formats} system property.
     *
     * @return unmodifiable set of lower-case format names (e.g. "heic", "avif", "webp")
     */
    public static Set<String> activeFormats() { return ACTIVE_FORMATS; }

    /**
     * Returns the set of file suffixes currently active.
     *
     * @return unmodifiable set of lower-case suffixes (e.g. "heic", "avif", "webp", "jp2")
     */
    public static Set<String> activeSuffixes() { return ACTIVE_SUFFIXES; }

    /**
     * Returns image dimensions without full pixel decode.
     * <p>
     * Creates a CGImage to read width/height but does not render pixels —
     * significantly cheaper than {@link #decode}.
     *
     * @param imageData the raw image file bytes
     * @return image dimensions
     * @throws IIOException if the format is unsupported, file is corrupt, or OS is not macOS
     */
    public static Dimension getSize(byte[] imageData) throws IIOException {
        try {
            int[] wh = AppleNative.getSize(imageData);
            return new Dimension(wh[0], wh[1]);
        } catch (IIOException e) {
            throw e;
        } catch (java.io.IOException e) {
            throw new IIOException(e.getMessage(), e);
        }
    }

    /**
     * Decodes raw image bytes through Apple's CGImageSource.
     * <p>
     * Unlike {@code ImageIO.read()} which returns {@code null} on failure,
     * this method throws {@link IIOException} with a diagnostic message
     * explaining what went wrong.
     *
     * @param imageData the raw image file bytes
     * @return decoded image ({@code TYPE_INT_ARGB_PRE})
     * @throws IIOException if the format is unsupported, decode fails, or OS is not macOS
     */
    public static BufferedImage decode(byte[] imageData) throws IIOException {
        try {
            return AppleNative.decode(imageData);
        } catch (IIOException e) {
            throw e;
        } catch (java.io.IOException e) {
            throw new IIOException(e.getMessage(), e);
        }
    }
}
