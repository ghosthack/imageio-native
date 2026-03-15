package io.github.ghosthack.imageio.vips;

import io.github.ghosthack.imageio.common.FormatRegistry.Format;

import java.util.List;

/**
 * Registry of image formats supported by libvips, with metadata
 * (names, file suffixes, MIME types) and configuration for which formats
 * this library should claim.
 * <p>
 * This is a hardcoded list of common formats that libvips supports when
 * compiled with typical dependencies.  Formats not in this list but
 * supported by the installed libvips can still be decoded via the direct
 * {@link VipsNative} API — they just won't be claimed by the ImageIO SPI.
 * <p>
 * The actual availability of each format depends on the libvips build
 * configuration (e.g. HEIC requires libheif, PDF requires poppler).
 * The SPI probes each file via {@code vips_foreign_find_load_buffer}
 * regardless of what's listed here, so unsupported formats are never
 * falsely claimed.
 */
final class FormatRegistry {

    private FormatRegistry() {}

    static final String PROPERTY = io.github.ghosthack.imageio.common.FormatRegistry.PROPERTY;

    // ── Format entries ──────────────────────────────────────────────────

    /** All known libvips-decodable formats with metadata. */
    private static final List<Format> ALL_FORMATS = List.of(
        // ── Java-native formats (built-in readers already exist) ────────
        new Format(a("JPEG","jpeg","JPG","jpg"),  a("jpg","jpeg","jpe","jfif"), a("image/jpeg"),                     true),
        new Format(a("PNG","png"),                 a("png"),                     a("image/png","image/x-png"),        true),
        new Format(a("GIF","gif"),                 a("gif"),                     a("image/gif"),                      true),
        new Format(a("BMP","bmp"),                 a("bmp","dib"),               a("image/bmp","image/x-ms-bmp"),     true),
        new Format(a("TIFF","tiff"),               a("tiff","tif"),              a("image/tiff"),                     true),
        new Format(a("WBMP","wbmp"),               a("wbmp"),                    a("image/vnd.wap.wbmp"),             true),

        // ── Supplemental: formats Java cannot decode natively ───────────

        // HEIF family (requires libheif)
        new Format(a("HEIC","heic"),               a("heic"),                    a("image/heic"),                     false),
        new Format(a("HEIF","heif"),               a("heif"),                    a("image/heif"),                     false),

        // AV1 image (requires libheif with AV1 decoder)
        new Format(a("AVIF","avif"),               a("avif"),                    a("image/avif"),                     false),

        // WebP (requires libwebp)
        new Format(a("WEBP","webp","WebP"),        a("webp"),                    a("image/webp"),                     false),

        // JPEG 2000 (requires libopenjp2)
        new Format(a("JPEG2000","jpeg2000","JP2","jp2"), a("jp2","j2k","jpf","jpx","j2c"), a("image/jp2","image/jpeg2000"), false),

        // PDF (requires poppler)
        new Format(a("PDF","pdf"),                 a("pdf"),                     a("application/pdf"),                false),

        // SVG (requires librsvg)
        new Format(a("SVG","svg"),                 a("svg","svgz"),              a("image/svg+xml"),                  false),

        // OpenEXR (requires OpenEXR library)
        new Format(a("EXR","exr","OpenEXR"),       a("exr"),                     a("image/x-exr"),                   false),

        // FITS (requires cfitsio)
        new Format(a("FITS","fits"),               a("fits","fit","fts"),        a("application/fits"),               false),

        // Netpbm (built-in)
        new Format(a("PBM","pbm"),                 a("pbm"),                     a("image/x-portable-bitmap"),        false),
        new Format(a("PGM","pgm"),                 a("pgm"),                     a("image/x-portable-graymap"),       false),
        new Format(a("PPM","ppm"),                 a("ppm"),                     a("image/x-portable-pixmap"),        false),
        new Format(a("PFM","pfm"),                 a("pfm"),                     a("image/x-portable-floatmap"),      false),

        // Radiance HDR (built-in)
        new Format(a("HDR","hdr","Radiance"),      a("hdr"),                     a("image/vnd.radiance"),             false),

        // Analyze (built-in)
        new Format(a("Analyze","analyze"),          a("hdr","img"),               a("application/x-analyze"),          false)
    );

    static final io.github.ghosthack.imageio.common.FormatRegistry INSTANCE =
            new io.github.ghosthack.imageio.common.FormatRegistry(ALL_FORMATS);

    private static String[] a(String... s) { return s; }
}
