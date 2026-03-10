package io.github.ghosthack.imageio;

import io.github.ghosthack.imageio.apple.AppleImageio;
import io.github.ghosthack.imageio.windows.WindowsImageio;

import javax.imageio.IIOException;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.Set;

/**
 * Unified cross-platform API for native image decoding.
 * <p>
 * Automatically delegates to the correct platform backend:
 * <ul>
 *   <li><strong>macOS</strong> → Apple ImageIO (CGImageSource / Apple Media Engine)</li>
 *   <li><strong>Windows</strong> → Windows Imaging Component (WIC / DXVA)</li>
 * </ul>
 * <p>
 * Use this when you want more control than {@code javax.imageio.ImageIO.read()}
 * provides. The SPI-based auto-registration still works in parallel — this API
 * is an alternative, not a replacement.
 *
 * <pre>{@code
 * if (ImageioNative.isAvailable()) {
 *     Dimension size = ImageioNative.getSize(bytes);
 *     BufferedImage img = ImageioNative.decode(bytes);
 * }
 * }</pre>
 *
 * For platform-specific features (e.g., Windows codec installation checks),
 * use {@link AppleImageio} or {@link WindowsImageio} directly.
 */
public final class ImageioNative {

    private ImageioNative() {}

    /**
     * Returns {@code true} if a native image decoding backend is available
     * on the current platform (macOS or Windows).
     */
    public static boolean isAvailable() {
        return AppleImageio.isAvailable() || WindowsImageio.isAvailable();
    }

    /**
     * Probes whether the platform's native decoder can handle the given data.
     *
     * @param header first bytes of the image (4 KB is sufficient)
     * @param length number of valid bytes in {@code header}
     * @return {@code true} if the format is recognised by the current platform
     */
    public static boolean canDecode(byte[] header, int length) {
        if (AppleImageio.isAvailable()) return AppleImageio.canDecode(header, length);
        if (WindowsImageio.isAvailable()) return WindowsImageio.canDecode(header, length);
        return false;
    }

    /**
     * Returns the set of format names currently active on this platform,
     * as controlled by the {@code imageio.native.formats} system property.
     *
     * @return unmodifiable set of format names (e.g. "HEIC", "AVIF", "WEBP")
     */
    public static Set<String> activeFormats() {
        if (AppleImageio.isAvailable()) return AppleImageio.activeFormats();
        if (WindowsImageio.isAvailable()) return WindowsImageio.activeFormats();
        return Set.of();
    }

    /**
     * Returns the set of file suffixes currently active on this platform.
     *
     * @return unmodifiable set of suffixes (e.g. "heic", "avif", "webp", "jp2")
     */
    public static Set<String> activeSuffixes() {
        if (AppleImageio.isAvailable()) return AppleImageio.activeSuffixes();
        if (WindowsImageio.isAvailable()) return WindowsImageio.activeSuffixes();
        return Set.of();
    }

    /**
     * Returns image dimensions without full pixel decode.
     * <p>
     * Queries the native decoder for image metadata only — no pixel buffer
     * allocation, no colour-space conversion. Significantly cheaper than
     * {@link #decode} for cases where only dimensions are needed.
     *
     * @param imageData the raw image file bytes
     * @return image dimensions
     * @throws IIOException if the format is unsupported, file is corrupt, or no backend is available
     */
    public static Dimension getSize(byte[] imageData) throws IIOException {
        if (AppleImageio.isAvailable()) return AppleImageio.getSize(imageData);
        if (WindowsImageio.isAvailable()) return WindowsImageio.getSize(imageData);
        throw new IIOException("No native image decoding backend available on this platform");
    }

    /**
     * Decodes raw image bytes through the platform's native decoder.
     * <p>
     * Unlike {@code ImageIO.read()} which silently returns {@code null} on failure,
     * this method throws {@link IIOException} with a diagnostic message explaining
     * what went wrong (unsupported format, missing codec, corrupt data, etc.).
     *
     * @param imageData the raw image file bytes
     * @return decoded image ({@code TYPE_INT_ARGB_PRE})
     * @throws IIOException if the format is unsupported, decode fails, or no backend is available
     */
    public static BufferedImage decode(byte[] imageData) throws IIOException {
        if (AppleImageio.isAvailable()) return AppleImageio.decode(imageData);
        if (WindowsImageio.isAvailable()) return WindowsImageio.decode(imageData);
        throw new IIOException("No native image decoding backend available on this platform");
    }
}
