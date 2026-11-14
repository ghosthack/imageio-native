package io.github.ghosthack.imageio.apple;

import io.github.ghosthack.imageio.common.FormatRegistry.Format;

import java.util.List;

/**
 * Registry of image formats supported by Apple's ImageIO, with metadata
 * (names, file suffixes, MIME types) and configuration for which formats
 * this library should claim.
 * <p>
 * Extends the shared {@link io.github.ghosthack.imageio.common.FormatRegistry}
 * with Apple-specific formats (JPEG 2000, JXL, PSD, EXR, GPU textures,
 * camera RAW, etc.).
 */
final class FormatRegistry {

    private FormatRegistry() {}

    // ── Format entries ──────────────────────────────────────────────────

    /** All known Apple ImageIO source formats with metadata. */
    private static final List<Format> ALL_FORMATS = List.of(
        // ── Java-native formats (built-in readers already exist) ────────
        new Format(a("JPEG","jpeg","JPG","jpg"),  a("jpg","jpeg","jpe","jfif"), a("image/jpeg")),
        new Format(a("PNG","png"),                 a("png"),                     a("image/png","image/x-png")),
        new Format(a("GIF","gif"),                 a("gif"),                     a("image/gif")),
        new Format(a("BMP","bmp"),                 a("bmp","dib"),               a("image/bmp","image/x-ms-bmp")),
        new Format(a("TIFF","tiff"),               a("tiff","tif"),              a("image/tiff")),
        new Format(a("WBMP","wbmp"),               a("wbmp"),                    a("image/vnd.wap.wbmp")),

        // ── Supplemental: formats Java cannot decode natively ───────────

        // HEIF family
        new Format(a("HEIC","heic"),               a("heic"),                    a("image/heic")),
        new Format(a("HEIF","heif"),               a("heif"),                    a("image/heif")),

        // AV1 image
        new Format(a("AVIF","avif"),               a("avif"),                    a("image/avif")),

        // WebP
        new Format(a("WEBP","webp","WebP"),        a("webp"),                    a("image/webp")),

        // JPEG 2000
        new Format(a("JPEG2000","jpeg2000","JP2","jp2"), a("jp2","j2k","jpf","jpx","j2c"), a("image/jp2","image/jpeg2000")),

        // JPEG XL
        new Format(a("JXL","jxl","JPEGXL"),        a("jxl"),                     a("image/jxl")),

        // Adobe PSD
        new Format(a("PSD","psd"),                 a("psd"),                     a("image/vnd.adobe.photoshop")),

        // Icons / cursors
        new Format(a("ICO","ico"),                 a("ico"),                     a("image/x-icon")),
        new Format(a("ICNS","icns"),               a("icns"),                    a("image/x-icns")),
        new Format(a("CUR","cur"),                 a("cur"),                     a("image/x-win-bitmap")),

        // HDR / scientific
        new Format(a("EXR","exr"),                 a("exr"),                     a("image/x-exr")),
        new Format(a("RADIANCE","hdr","rgbe"),     a("hdr","pic","rgbe"),        a("image/vnd.radiance")),

        // Legacy / niche
        new Format(a("TGA","tga"),                 a("tga","targa"),             a("image/x-tga")),
        new Format(a("SGI","sgi"),                 a("sgi","rgb","bw"),          a("image/x-sgi")),
        new Format(a("PBM","pbm","PGM","pgm","PPM","ppm"), a("pbm","pgm","ppm"),a("image/x-portable-bitmap")),
        new Format(a("PICT","pict"),               a("pict","pct"),              a("image/x-pict")),
        new Format(a("DDS","dds"),                 a("dds"),                     a("image/vnd.ms-dds")),
        new Format(a("MPO","mpo"),                 a("mpo"),                     a("image/mpo")),
        new Format(a("DICOM","dcm"),               a("dcm","dicom"),             a("application/dicom")),

        // GPU textures
        new Format(a("KTX","ktx"),                 a("ktx"),                     a("image/ktx")),
        new Format(a("KTX2","ktx2"),               a("ktx2"),                    a("image/ktx2")),
        new Format(a("ASTC","astc"),               a("astc"),                    a("image/astc")),
        new Format(a("PVR","pvr"),                 a("pvr"),                     a("image/x-pvr")),
        new Format(a("ATX","atx"),                 a("atx"),                     a("image/x-atx")),

        // Camera RAW (DNG + vendor)
        new Format(a("DNG","dng"),                 a("dng"),                     a("image/x-adobe-dng")),
        new Format(a("CR2","cr2"),                 a("cr2"),                     a("image/x-canon-cr2")),
        new Format(a("CR3","cr3"),                 a("cr3"),                     a("image/x-canon-cr3")),
        new Format(a("CRW","crw"),                 a("crw"),                     a("image/x-canon-crw")),
        new Format(a("NEF","nef"),                 a("nef"),                     a("image/x-nikon-nef")),
        new Format(a("NRW","nrw"),                 a("nrw"),                     a("image/x-nikon-nrw")),
        new Format(a("ARW","arw"),                 a("arw"),                     a("image/x-sony-arw")),
        new Format(a("SR2","sr2"),                 a("sr2"),                     a("image/x-sony-sr2")),
        new Format(a("SRF","srf"),                 a("srf"),                     a("image/x-sony-srf")),
        new Format(a("ORF","orf"),                 a("orf"),                     a("image/x-olympus-orf")),
        new Format(a("RAF","raf"),                 a("raf"),                     a("image/x-fuji-raf")),
        new Format(a("RW2","rw2"),                 a("rw2"),                     a("image/x-panasonic-rw2")),
        new Format(a("PEF","pef"),                 a("pef"),                     a("image/x-pentax-pef")),
        new Format(a("SRW","srw"),                 a("srw"),                     a("image/x-samsung-srw")),
        new Format(a("ERF","erf"),                 a("erf"),                     a("image/x-epson-erf")),
        new Format(a("MRW","mrw"),                 a("mrw"),                     a("image/x-minolta-mrw")),
        new Format(a("DCR","dcr"),                 a("dcr"),                     a("image/x-kodak-dcr")),
        new Format(a("3FR","3fr"),                 a("3fr"),                     a("image/x-hasselblad-3fr")),
        new Format(a("FFF","fff"),                 a("fff"),                     a("image/x-hasselblad-fff")),
        new Format(a("IIQ","iiq"),                 a("iiq"),                     a("image/x-phaseone-iiq")),
        new Format(a("MOS","mos"),                 a("mos"),                     a("image/x-leaf-mos")),
        new Format(a("RWL","rwl"),                 a("rwl"),                     a("image/x-leica-rwl"))
    );

    static final io.github.ghosthack.imageio.common.FormatRegistry INSTANCE =
            new io.github.ghosthack.imageio.common.FormatRegistry(ALL_FORMATS);

    // ── Delegating queries ──────────────────────────────────────────────

    static String[] activeFormatNames() { return INSTANCE.formatNames(); }
    static String[] activeSuffixes()    { return INSTANCE.suffixes(); }
    static String[] activeMimeTypes()   { return INSTANCE.mimeTypes(); }

    private static String[] a(String... s) { return s; }
}
