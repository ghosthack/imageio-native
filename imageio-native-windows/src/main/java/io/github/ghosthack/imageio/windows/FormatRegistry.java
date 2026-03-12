package io.github.ghosthack.imageio.windows;

import io.github.ghosthack.imageio.common.FormatRegistry.Format;

import java.util.List;

/**
 * Registry of image formats supported by WIC, with metadata (names, file
 * suffixes, MIME types) and configuration for which formats this library
 * should claim.
 * <p>
 * Extends the shared {@link io.github.ghosthack.imageio.common.FormatRegistry}
 * with WIC-specific formats (JPEG-XR, DDS, etc.).
 */
final class FormatRegistry {

    private FormatRegistry() {}

    static final String PROPERTY = io.github.ghosthack.imageio.common.FormatRegistry.PROPERTY;

    // ── Format entries ──────────────────────────────────────────────────

    /** All known WIC-decodable formats with metadata. */
    private static final List<Format> ALL_FORMATS = List.of(
        // ── Java-native formats (built-in readers already exist) ────────
        new Format(a("JPEG","jpeg","JPG","jpg"),  a("jpg","jpeg","jpe","jfif"), a("image/jpeg"),                     true),
        new Format(a("PNG","png"),                 a("png"),                     a("image/png","image/x-png"),        true),
        new Format(a("GIF","gif"),                 a("gif"),                     a("image/gif"),                      true),
        new Format(a("BMP","bmp"),                 a("bmp","dib"),               a("image/bmp","image/x-ms-bmp"),     true),
        new Format(a("TIFF","tiff"),               a("tiff","tif"),              a("image/tiff"),                     true),
        new Format(a("WBMP","wbmp"),               a("wbmp"),                    a("image/vnd.wap.wbmp"),             true),

        // ── Supplemental: formats Java cannot decode natively ───────────

        // HEIF family (requires HEVC Video Extensions from Microsoft Store)
        new Format(a("HEIC","heic"),               a("heic"),                    a("image/heic"),                     false),
        new Format(a("HEIF","heif"),               a("heif"),                    a("image/heif"),                     false),

        // AV1 image (requires AV1 Video Extensions from Microsoft Store)
        new Format(a("AVIF","avif"),               a("avif"),                    a("image/avif"),                     false),

        // WebP (built-in on Windows 10 1809+)
        new Format(a("WEBP","webp","WebP"),        a("webp"),                    a("image/webp"),                     false),

        // JPEG-XR / HD Photo (built-in WIC codec)
        new Format(a("JPEGXR","jpegxr","JXR","jxr","WDP","wdp","HDP","hdp"),
                                                   a("jxr","wdp","hdp"),         a("image/vnd.ms-photo","image/jxr"), false),

        // DDS (DirectDraw Surface, built-in WIC codec)
        new Format(a("DDS","dds"),                 a("dds"),                     a("image/vnd.ms-dds"),               false),

        // Icons / cursors
        new Format(a("ICO","ico"),                 a("ico"),                     a("image/x-icon"),                   false),
        new Format(a("CUR","cur"),                 a("cur"),                     a("image/x-win-bitmap"),             false),

        // Camera RAW (WIC codec available via Microsoft Camera Codec Pack or built-in on Win10+)
        new Format(a("DNG","dng"),                 a("dng"),                     a("image/x-adobe-dng"),              false),
        new Format(a("CR2","cr2"),                 a("cr2"),                     a("image/x-canon-cr2"),              false),
        new Format(a("CR3","cr3"),                 a("cr3"),                     a("image/x-canon-cr3"),              false),
        new Format(a("CRW","crw"),                 a("crw"),                     a("image/x-canon-crw"),              false),
        new Format(a("NEF","nef"),                 a("nef"),                     a("image/x-nikon-nef"),              false),
        new Format(a("NRW","nrw"),                 a("nrw"),                     a("image/x-nikon-nrw"),              false),
        new Format(a("ARW","arw"),                 a("arw"),                     a("image/x-sony-arw"),               false),
        new Format(a("SR2","sr2"),                 a("sr2"),                     a("image/x-sony-sr2"),               false),
        new Format(a("SRF","srf"),                 a("srf"),                     a("image/x-sony-srf"),               false),
        new Format(a("ORF","orf"),                 a("orf"),                     a("image/x-olympus-orf"),            false),
        new Format(a("RAF","raf"),                 a("raf"),                     a("image/x-fuji-raf"),               false),
        new Format(a("RW2","rw2"),                 a("rw2"),                     a("image/x-panasonic-rw2"),          false),
        new Format(a("PEF","pef"),                 a("pef"),                     a("image/x-pentax-pef"),             false),
        new Format(a("SRW","srw"),                 a("srw"),                     a("image/x-samsung-srw"),            false),
        new Format(a("ERF","erf"),                 a("erf"),                     a("image/x-epson-erf"),              false),
        new Format(a("MRW","mrw"),                 a("mrw"),                     a("image/x-minolta-mrw"),            false),
        new Format(a("DCR","dcr"),                 a("dcr"),                     a("image/x-kodak-dcr"),              false),
        new Format(a("3FR","3fr"),                 a("3fr"),                     a("image/x-hasselblad-3fr"),         false),
        new Format(a("FFF","fff"),                 a("fff"),                     a("image/x-hasselblad-fff"),         false),
        new Format(a("IIQ","iiq"),                 a("iiq"),                     a("image/x-phaseone-iiq"),           false),
        new Format(a("RWL","rwl"),                 a("rwl"),                     a("image/x-leica-rwl"),              false)
    );

    static final io.github.ghosthack.imageio.common.FormatRegistry INSTANCE =
            new io.github.ghosthack.imageio.common.FormatRegistry(ALL_FORMATS);

    // ── Delegating queries ──────────────────────────────────────────────

    static String[] activeFormatNames() { return INSTANCE.activeFormatNames(); }
    static String[] activeSuffixes()    { return INSTANCE.activeSuffixes(); }
    static String[] activeMimeTypes()   { return INSTANCE.activeMimeTypes(); }

    private static String[] a(String... s) { return s; }
}
