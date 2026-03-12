package io.github.ghosthack.imageio.windows;

import javax.imageio.IIOException;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.Set;

/**
 * Direct public API for Windows Imaging Component (WIC) decoding via Project Panama.
 * <p>
 * Use this when you want more control than {@code javax.imageio.ImageIO.read()}
 * provides — meaningful exceptions, codec availability checks, or lightweight
 * dimension queries without full pixel decode.
 * <p>
 * All methods are safe to call on any OS. On non-Windows platforms,
 * {@link #isAvailable()} returns {@code false} and decode methods throw
 * {@link IIOException} with a clear message.
 *
 * <pre>{@code
 * if (WindowsImageio.isAvailable()) {
 *     if (WindowsImageio.isHeicCodecInstalled()) {
 *         BufferedImage img = WindowsImageio.decode(heicBytes);
 *     }
 * }
 * }</pre>
 */
public final class WindowsImageio {

    private WindowsImageio() {}

    /**
     * Returns {@code true} if the WIC backend is available
     * (i.e., the current OS is Windows).
     */
    public static boolean isAvailable() {
        return WicNative.IS_WINDOWS;
    }

    /**
     * Probes whether WIC can decode the given data.
     *
     * @param header first bytes of the image (4 KB is sufficient)
     * @param length number of valid bytes in {@code header}
     * @return {@code true} if the format is recognised
     */
    public static boolean canDecode(byte[] header, int length) {
        return WicNative.canDecode(header, length);
    }

    private static final Set<String> ACTIVE_FORMATS  = Set.of(FormatRegistry.activeFormatNames());
    private static final Set<String> ACTIVE_SUFFIXES = Set.of(FormatRegistry.activeSuffixes());

    /**
     * Returns the set of format names currently active, as controlled by
     * the {@code imageio.native.formats} system property.
     *
     * @return unmodifiable set of lower-case format names
     */
    public static Set<String> activeFormats() { return ACTIVE_FORMATS; }

    /**
     * Returns the set of file suffixes currently active.
     *
     * @return unmodifiable set of lower-case suffixes
     */
    public static Set<String> activeSuffixes() { return ACTIVE_SUFFIXES; }

    // ── Codec availability checks ───────────────────────────────────────

    /**
     * Returns {@code true} if the HEVC Video Extensions are installed,
     * allowing WIC to decode HEIC/HEIF images.
     * <p>
     * Always returns {@code false} on non-Windows platforms.
     */
    public static boolean isHeicCodecInstalled() {
        return CodecChecker.isHeicAvailable();
    }

    /**
     * Returns {@code true} if the AV1 Video Extensions are installed,
     * allowing WIC to decode AVIF images.
     * <p>
     * Always returns {@code false} on non-Windows platforms.
     */
    public static boolean isAvifCodecInstalled() {
        return CodecChecker.isAvifAvailable();
    }

    /**
     * Returns {@code true} if WIC can decode WebP images.
     * Built-in on Windows 10 1809+ (October 2018 Update).
     * <p>
     * Always returns {@code false} on non-Windows platforms.
     */
    public static boolean isWebpCodecInstalled() {
        return CodecChecker.isWebpAvailable();
    }

    // ── Size and decode ─────────────────────────────────────────────────

    /**
     * Returns image dimensions without full pixel decode.
     * <p>
     * Creates a WIC decoder to read frame size but does not create a format
     * converter or copy pixels — significantly cheaper than {@link #decode}.
     *
     * @param imageData the raw image file bytes
     * @return image dimensions
     * @throws IIOException if the format is unsupported, file is corrupt, or OS is not Windows
     */
    public static Dimension getSize(byte[] imageData) throws IIOException {
        try {
            int[] wh = WicNative.getSize(imageData);
            return new Dimension(wh[0], wh[1]);
        } catch (IIOException e) {
            throw e;
        } catch (java.io.IOException e) {
            throw new IIOException(e.getMessage(), e);
        }
    }

    /**
     * Decodes raw image bytes through WIC.
     * <p>
     * Unlike {@code ImageIO.read()} which returns {@code null} on failure,
     * this method throws {@link IIOException} with a diagnostic message
     * including the HRESULT error code.
     *
     * @param imageData the raw image file bytes
     * @return decoded image ({@code TYPE_INT_ARGB_PRE})
     * @throws IIOException if the format is unsupported, decode fails, or OS is not Windows
     */
    public static BufferedImage decode(byte[] imageData) throws IIOException {
        try {
            return WicNative.decode(imageData);
        } catch (IIOException e) {
            throw e;
        } catch (java.io.IOException e) {
            throw new IIOException(e.getMessage(), e);
        }
    }
}
