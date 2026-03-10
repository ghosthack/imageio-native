package io.github.ghosthack.imageio.windows;

/**
 * Probes WIC for optional codec availability.
 * <p>
 * On Windows 10+, HEIC requires the <strong>HEVC Video Extensions</strong>
 * (or "HEVC Video Extensions from Device Manufacturer") and AVIF requires the
 * <strong>AV1 Video Extensions</strong>, both from the Microsoft Store.
 * WebP is built-in on Windows 10 1809+.
 * <p>
 * This class uses {@link WicNative#canDecode(byte[], int)} with minimal header
 * blobs that are valid enough for WIC to attempt codec lookup, detecting
 * whether the required extensions are installed.
 */
final class CodecChecker {

    private CodecChecker() {}

    // ── Minimal HEIF ftyp box (major brand "heic") ──────────────────────
    // 20 bytes: box_size(20) + "ftyp" + major_brand("heic") + minor_version(0) + compat("heic")
    private static final byte[] HEIF_PROBE = {
            0x00, 0x00, 0x00, 0x14,  // box size = 20
            'f', 't', 'y', 'p',     // box type
            'h', 'e', 'i', 'c',     // major brand
            0x00, 0x00, 0x00, 0x00,  // minor version
            'h', 'e', 'i', 'c'      // compatible brand
    };

    // ── Minimal AVIF ftyp box (major brand "avif") ──────────────────────
    private static final byte[] AVIF_PROBE = {
            0x00, 0x00, 0x00, 0x14,  // box size = 20
            'f', 't', 'y', 'p',     // box type
            'a', 'v', 'i', 'f',     // major brand
            0x00, 0x00, 0x00, 0x00,  // minor version
            'a', 'v', 'i', 'f'      // compatible brand
    };

    // ── Minimal WebP RIFF header ────────────────────────────────────────
    // 12 bytes: "RIFF" + file_size(0) + "WEBP"
    private static final byte[] WEBP_PROBE = {
            'R', 'I', 'F', 'F',
            0x00, 0x00, 0x00, 0x00,  // file size (don't care for probe)
            'W', 'E', 'B', 'P'
    };

    /**
     * Returns {@code true} if the HEVC Video Extensions are installed,
     * allowing WIC to decode HEIC/HEIF images.
     */
    static boolean isHeicAvailable() {
        return WicNative.canDecode(HEIF_PROBE, HEIF_PROBE.length);
    }

    /**
     * Returns {@code true} if the AV1 Video Extensions are installed,
     * allowing WIC to decode AVIF images.
     */
    static boolean isAvifAvailable() {
        return WicNative.canDecode(AVIF_PROBE, AVIF_PROBE.length);
    }

    /**
     * Returns {@code true} if WIC can decode WebP images.
     * Built-in on Windows 10 1809+ (October 2018 Update).
     */
    static boolean isWebpAvailable() {
        return WicNative.canDecode(WEBP_PROBE, WEBP_PROBE.length);
    }
}
