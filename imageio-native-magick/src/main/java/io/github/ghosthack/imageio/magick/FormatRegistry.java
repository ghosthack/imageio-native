package io.github.ghosthack.imageio.magick;

import io.github.ghosthack.imageio.common.FormatRegistry.Format;

import java.util.List;

/**
 * Registry of image formats supported by ImageMagick 7, with metadata
 * (names, file suffixes, MIME types).
 * <p>
 * This is a hardcoded list of common formats.  ImageMagick supports 200+
 * formats; those not listed here can still be decoded via the direct
 * {@link MagickNative} API but won't be claimed by the ImageIO SPI.
 * The SPI probes each file via {@code MagickPingImageBlob} regardless,
 * so unsupported formats are never falsely claimed.
 */
final class FormatRegistry {

    private FormatRegistry() {}

    private static final List<Format> ALL_FORMATS = List.of(
        // ── Java-native formats ─────────────────────────────────────────
        new Format(a("JPEG","jpeg","JPG","jpg"),  a("jpg","jpeg","jpe","jfif"), a("image/jpeg")),
        new Format(a("PNG","png"),                 a("png"),                     a("image/png","image/x-png")),
        new Format(a("GIF","gif"),                 a("gif"),                     a("image/gif")),
        new Format(a("BMP","bmp"),                 a("bmp","dib"),               a("image/bmp","image/x-ms-bmp")),
        new Format(a("TIFF","tiff"),               a("tiff","tif"),              a("image/tiff")),
        new Format(a("WBMP","wbmp"),               a("wbmp"),                    a("image/vnd.wap.wbmp")),

        // ── Supplemental ────────────────────────────────────────────────

        // HEIF family
        new Format(a("HEIC","heic"),               a("heic"),                    a("image/heic")),
        new Format(a("HEIF","heif"),               a("heif"),                    a("image/heif")),
        new Format(a("AVIF","avif"),               a("avif"),                    a("image/avif")),

        // WebP
        new Format(a("WEBP","webp","WebP"),        a("webp"),                    a("image/webp")),

        // JPEG 2000
        new Format(a("JPEG2000","jpeg2000","JP2","jp2"), a("jp2","j2k","jpf","jpx","j2c"), a("image/jp2","image/jpeg2000")),

        // PDF / EPS / AI (rasterisation)
        new Format(a("PDF","pdf"),                 a("pdf"),                     a("application/pdf")),
        new Format(a("EPS","eps"),                 a("eps","epsf","epsi"),       a("application/postscript")),

        // SVG
        new Format(a("SVG","svg"),                 a("svg","svgz"),              a("image/svg+xml")),

        // Photoshop
        new Format(a("PSD","psd"),                 a("psd","psb"),               a("image/vnd.adobe.photoshop")),

        // OpenEXR
        new Format(a("EXR","exr","OpenEXR"),       a("exr"),                     a("image/x-exr")),

        // GIMP
        new Format(a("XCF","xcf"),                 a("xcf"),                     a("image/x-xcf")),

        // DPX / Cineon
        new Format(a("DPX","dpx"),                 a("dpx"),                     a("image/x-dpx")),

        // TGA
        new Format(a("TGA","tga"),                 a("tga","tpic"),              a("image/x-tga")),

        // ICO / CUR
        new Format(a("ICO","ico"),                 a("ico"),                     a("image/x-icon","image/vnd.microsoft.icon")),
        new Format(a("CUR","cur"),                 a("cur"),                     a("image/x-win-bitmap")),

        // PCX
        new Format(a("PCX","pcx"),                 a("pcx"),                     a("image/x-pcx")),

        // Netpbm
        new Format(a("PBM","pbm"),                 a("pbm"),                     a("image/x-portable-bitmap")),
        new Format(a("PGM","pgm"),                 a("pgm"),                     a("image/x-portable-graymap")),
        new Format(a("PPM","ppm"),                 a("ppm"),                     a("image/x-portable-pixmap")),

        // Radiance HDR
        new Format(a("HDR","hdr","Radiance"),      a("hdr"),                     a("image/vnd.radiance")),

        // X bitmap / pixmap
        new Format(a("XBM","xbm"),                 a("xbm"),                     a("image/x-xbitmap")),
        new Format(a("XPM","xpm"),                 a("xpm"),                     a("image/x-xpixmap")),

        // FITS
        new Format(a("FITS","fits"),               a("fits","fit","fts"),        a("application/fits")),

        // DDS
        new Format(a("DDS","dds"),                 a("dds"),                     a("image/x-dds"))
    );

    static final io.github.ghosthack.imageio.common.FormatRegistry INSTANCE =
            new io.github.ghosthack.imageio.common.FormatRegistry(ALL_FORMATS);

    private static String[] a(String... s) { return s; }
}
