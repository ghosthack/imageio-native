package io.github.ghosthack.imageio.magick;

import javax.imageio.IIOException;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.IOException;
import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Panama FFM downcalls to ImageMagick 7's MagickWand C API for image decoding.
 * <p>
 * Requires ImageMagick 7 ({@code libMagickWand-7}) installed on the system.
 * Both Q16 (integer) and Q16HDRI (floating-point) builds are supported — the
 * library discovery logic tries both variants.
 * <p>
 * Use {@link #isAvailable()} to check at runtime.  The library path can be
 * overridden with {@code -Dimageio.native.magick.lib=/path/to/libMagickWand-7.Q16.dylib}.
 */
final class MagickNative {

    private MagickNative() {}

    // ── Constants ───────────────────────────────────────────────────────

    /** MagickBooleanType: MagickTrue */
    private static final int MagickTrue = 1;

    /** StorageType: CharPixel (unsigned 8-bit per channel) */
    private static final int CharPixel = 1;

    /** AlphaChannelOption: SetAlphaChannel (ensure alpha exists, opaque if absent) */
    private static final int SetAlphaChannel = 10;

    /** Maximum total pixel count to prevent OOM. */
    private static final long MAX_PIXELS = 256L * 1024 * 1024;

    // ── Library loading ─────────────────────────────────────────────────

    private static final boolean AVAILABLE;
    private static final SymbolLookup LOOKUP;
    private static final Linker LINKER = Linker.nativeLinker();

    // ── Downcall handles ────────────────────────────────────────────────

    private static final MethodHandle MagickWandGenesis;
    private static final MethodHandle NewMagickWand;
    private static final MethodHandle DestroyMagickWand;
    private static final MethodHandle MagickReadImage;
    private static final MethodHandle MagickReadImageBlob;
    private static final MethodHandle MagickPingImageBlob;
    private static final MethodHandle MagickGetImageWidth;
    private static final MethodHandle MagickGetImageHeight;
    private static final MethodHandle MagickSetImageAlphaChannel;
    private static final MethodHandle MagickExportImagePixels;
    private static final MethodHandle MagickGetException;
    private static final MethodHandle MagickRelinquishMemory;
    private static final MethodHandle ClearMagickWand;

    static {
        boolean ok = false;
        SymbolLookup lk = null;
        try {
            lk = loadLibrary();
            ok = (lk != null);
        } catch (Throwable t) {
            // Library not found
        }
        LOOKUP = lk;

        if (ok) {
            MagickWandGenesis = downcall("MagickWandGenesis",
                    FunctionDescriptor.ofVoid());
            NewMagickWand = downcall("NewMagickWand",
                    FunctionDescriptor.of(ValueLayout.ADDRESS));
            DestroyMagickWand = downcall("DestroyMagickWand",
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            ClearMagickWand = downcall("ClearMagickWand",
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
            MagickReadImage = downcall("MagickReadImage",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            MagickReadImageBlob = downcall("MagickReadImageBlob",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
            MagickPingImageBlob = downcall("MagickPingImageBlob",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
            MagickGetImageWidth = downcall("MagickGetImageWidth",
                    FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));
            MagickGetImageHeight = downcall("MagickGetImageHeight",
                    FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));
            MagickSetImageAlphaChannel = downcall("MagickSetImageAlphaChannel",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
            // MagickExportImagePixels(wand, x, y, columns, rows, map, storage, pixels)
            MagickExportImagePixels = downcall("MagickExportImagePixels",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS,
                            ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS));
            MagickGetException = downcall("MagickGetException",
                    FunctionDescriptor.of(ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            MagickRelinquishMemory = downcall("MagickRelinquishMemory",
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));

            // Initialize MagickWand
            try {
                MagickWandGenesis.invokeExact();
            } catch (Throwable t) {
                ok = false;
            }
        } else {
            MagickWandGenesis = null;
            NewMagickWand = null;
            DestroyMagickWand = null;
            ClearMagickWand = null;
            MagickReadImage = null;
            MagickReadImageBlob = null;
            MagickPingImageBlob = null;
            MagickGetImageWidth = null;
            MagickGetImageHeight = null;
            MagickSetImageAlphaChannel = null;
            MagickExportImagePixels = null;
            MagickGetException = null;
            MagickRelinquishMemory = null;
        }
        AVAILABLE = ok;
    }

    // ── Public API ──────────────────────────────────────────────────────

    static boolean isAvailable() {
        return AVAILABLE;
    }

    /**
     * Probes whether ImageMagick can decode data with the given header bytes.
     * <p>
     * Uses {@code MagickPingImageBlob} first (lightweight, no pixel decode).
     * Falls back to {@code MagickReadImageBlob} if ping fails — some
     * ImageMagick delegates require a full read to identify the format.
     */
    static boolean canDecode(byte[] header, int len) {
        if (!AVAILABLE) return false;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment wand = (MemorySegment) NewMagickWand.invokeExact();
            try {
                MemorySegment buf = arena.allocateFrom(ValueLayout.JAVA_BYTE, header);
                int rc = (int) MagickPingImageBlob.invokeExact(wand, buf, (long) len);
                if (rc == MagickTrue) return true;

                // Some delegates (HEIC, AVIF) need a full read to identify
                ClearMagickWand.invokeExact(wand);
                rc = (int) MagickReadImageBlob.invokeExact(wand, buf, (long) len);
                return rc == MagickTrue;
            } finally {
                destroyWand(wand);
            }
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Returns image dimensions without full pixel decode.
     */
    static int[] getSize(byte[] imageData) throws IOException {
        if (!AVAILABLE) throw new IIOException("ImageMagick is not available");
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment wand = (MemorySegment) NewMagickWand.invokeExact();
            try {
                MemorySegment buf = arena.allocateFrom(ValueLayout.JAVA_BYTE, imageData);
                int rc = (int) MagickPingImageBlob.invokeExact(wand, buf, (long) imageData.length);
                if (rc != MagickTrue)
                    throw new IIOException("ImageMagick ping failed: " + errorMessage(arena, wand));

                int w = (int) (long) MagickGetImageWidth.invokeExact(wand);
                int h = (int) (long) MagickGetImageHeight.invokeExact(wand);
                if (w <= 0 || h <= 0)
                    throw new IIOException("Invalid image dimensions: " + w + "x" + h);
                return new int[]{w, h};
            } finally {
                destroyWand(wand);
            }
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw new IIOException("ImageMagick getSize failed", t);
        }
    }

    /**
     * Returns image dimensions by reading directly from a file path.
     */
    static int[] getSizeFromPath(String path) throws IOException {
        if (!AVAILABLE) throw new IIOException("ImageMagick is not available");
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment wand = (MemorySegment) NewMagickWand.invokeExact();
            try {
                MemorySegment cpath = arena.allocateFrom(path, StandardCharsets.UTF_8);
                // Ping reads header only (no pixel decode)
                int rc = (int) MagickReadImage.invokeExact(wand, cpath);
                if (rc != MagickTrue)
                    throw new IIOException("ImageMagick read failed for: " + path
                            + " — " + errorMessage(arena, wand));

                int w = (int) (long) MagickGetImageWidth.invokeExact(wand);
                int h = (int) (long) MagickGetImageHeight.invokeExact(wand);
                if (w <= 0 || h <= 0)
                    throw new IIOException("Invalid image dimensions: " + w + "x" + h);
                return new int[]{w, h};
            } finally {
                destroyWand(wand);
            }
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw new IIOException("ImageMagick getSizeFromPath failed for: " + path, t);
        }
    }

    /**
     * Decodes raw image bytes through ImageMagick into a {@code TYPE_INT_ARGB_PRE} BufferedImage.
     */
    static BufferedImage decode(byte[] imageData) throws IOException {
        if (!AVAILABLE) throw new IIOException("ImageMagick is not available");
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment wand = (MemorySegment) NewMagickWand.invokeExact();
            try {
                MemorySegment buf = arena.allocateFrom(ValueLayout.JAVA_BYTE, imageData);
                int rc = (int) MagickReadImageBlob.invokeExact(wand, buf, (long) imageData.length);
                if (rc != MagickTrue)
                    throw new IIOException("ImageMagick read failed: " + errorMessage(arena, wand));

                return exportPixels(arena, wand);
            } finally {
                destroyWand(wand);
            }
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw new IIOException("ImageMagick decode failed", t);
        }
    }

    /**
     * Decodes an image directly from a file path.
     */
    static BufferedImage decodeFromPath(String path) throws IOException {
        if (!AVAILABLE) throw new IIOException("ImageMagick is not available");
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment wand = (MemorySegment) NewMagickWand.invokeExact();
            try {
                MemorySegment cpath = arena.allocateFrom(path, StandardCharsets.UTF_8);
                int rc = (int) MagickReadImage.invokeExact(wand, cpath);
                if (rc != MagickTrue)
                    throw new IIOException("ImageMagick read failed for: " + path
                            + " — " + errorMessage(arena, wand));

                return exportPixels(arena, wand);
            } finally {
                destroyWand(wand);
            }
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw new IIOException("ImageMagick decodeFromPath failed for: " + path, t);
        }
    }

    // ── Internal ────────────────────────────────────────────────────────

    /**
     * Exports pixels from the current wand image into a BufferedImage.
     */
    private static BufferedImage exportPixels(Arena arena, MemorySegment wand)
            throws Throwable {
        int w = (int) (long) MagickGetImageWidth.invokeExact(wand);
        int h = (int) (long) MagickGetImageHeight.invokeExact(wand);
        if (w <= 0 || h <= 0)
            throw new IIOException("Invalid image dimensions: " + w + "x" + h);

        long totalPixels = (long) w * h;
        if (totalPixels > MAX_PIXELS)
            throw new IIOException("Image too large: " + w + "x" + h + " (" + totalPixels
                    + " pixels exceeds limit of " + MAX_PIXELS + ")");

        // Ensure alpha channel exists (opaque if image has no alpha)
        int alphaRc = (int) MagickSetImageAlphaChannel.invokeExact(wand, SetAlphaChannel);

        // Export as ARGB, 8-bit per channel
        long bufSize = totalPixels * 4;
        MemorySegment pixelBuf = arena.allocate(bufSize);
        MemorySegment map = arena.allocateFrom("ARGB", StandardCharsets.UTF_8);

        int rc = (int) MagickExportImagePixels.invokeExact(
                wand, 0L, 0L, (long) w, (long) h,
                map, CharPixel, pixelBuf);
        if (rc != MagickTrue)
            throw new IIOException("MagickExportImagePixels failed: " + errorMessage(arena, wand));

        // Repack ARGB bytes → 0xAARRGGBB int[] for TYPE_INT_ARGB_PRE
        // MagickExportImagePixels with "ARGB" + CharPixel gives [A,R,G,B] per pixel.
        // We need to premultiply and pack into ints.
        BufferedImage result = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB_PRE);
        int[] dest = ((DataBufferInt) result.getRaster().getDataBuffer()).getData();

        for (int i = 0, off = 0; i < dest.length; i++, off += 4) {
            int a = pixelBuf.get(ValueLayout.JAVA_BYTE, off) & 0xFF;
            int r = pixelBuf.get(ValueLayout.JAVA_BYTE, off + 1) & 0xFF;
            int g = pixelBuf.get(ValueLayout.JAVA_BYTE, off + 2) & 0xFF;
            int b = pixelBuf.get(ValueLayout.JAVA_BYTE, off + 3) & 0xFF;

            // Premultiply RGB by alpha
            if (a == 0) {
                dest[i] = 0;
            } else if (a == 255) {
                dest[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
            } else {
                r = (r * a + 127) / 255;
                g = (g * a + 127) / 255;
                b = (b * a + 127) / 255;
                dest[i] = (a << 24) | (r << 16) | (g << 8) | b;
            }
        }

        return result;
    }

    /** Destroys a wand, ignoring the returned pointer. */
    private static void destroyWand(MemorySegment wand) throws Throwable {
        MemorySegment ignored = (MemorySegment) DestroyMagickWand.invokeExact(wand);
    }

    private static String errorMessage(Arena arena, MemorySegment wand) {
        try {
            MemorySegment pSeverity = arena.allocate(ValueLayout.JAVA_INT);
            MemorySegment msg = (MemorySegment) MagickGetException.invokeExact(wand, pSeverity);
            if (MemorySegment.NULL.equals(msg) || msg.address() == 0)
                return "(no error message)";
            String text = msg.reinterpret(1024).getString(0, StandardCharsets.UTF_8);
            MemorySegment ignored = (MemorySegment) MagickRelinquishMemory.invokeExact(msg);
            return text;
        } catch (Throwable t) {
            return "(failed to read error)";
        }
    }

    private static MethodHandle downcall(String name, FunctionDescriptor desc) {
        return LINKER.downcallHandle(
                LOOKUP.find(name).orElseThrow(() ->
                        new UnsatisfiedLinkError("Symbol not found: " + name)),
                desc);
    }

    private static SymbolLookup loadLibrary() {
        String explicit = System.getProperty("imageio.native.magick.lib");
        if (explicit != null) {
            System.load(explicit);
            return SymbolLookup.loaderLookup();
        }

        // Try Q16HDRI first (Homebrew default), then Q16 (MacPorts default)
        String[] paths = {
                // macOS — MacPorts (non-standard prefix)
                "/opt/local/lib/ImageMagick7/lib/libMagickWand-7.Q16HDRI.dylib",
                "/opt/local/lib/ImageMagick7/lib/libMagickWand-7.Q16.dylib",
                // macOS — Homebrew
                "/usr/local/lib/libMagickWand-7.Q16HDRI.dylib",
                "/usr/local/lib/libMagickWand-7.Q16.dylib",
                // Homebrew Apple Silicon
                "/opt/homebrew/lib/libMagickWand-7.Q16HDRI.dylib",
                "/opt/homebrew/lib/libMagickWand-7.Q16.dylib",
                // Linux — Debian/Ubuntu x86_64
                "/usr/lib/x86_64-linux-gnu/libMagickWand-7.Q16HDRI.so",
                "/usr/lib/x86_64-linux-gnu/libMagickWand-7.Q16.so",
                // Linux — Debian/Ubuntu aarch64
                "/usr/lib/aarch64-linux-gnu/libMagickWand-7.Q16HDRI.so",
                "/usr/lib/aarch64-linux-gnu/libMagickWand-7.Q16.so",
                // Linux — generic
                "/usr/lib/libMagickWand-7.Q16HDRI.so",
                "/usr/lib/libMagickWand-7.Q16.so",
        };

        for (String p : paths) {
            if (Files.exists(Path.of(p))) {
                System.load(p);
                return SymbolLookup.loaderLookup();
            }
        }

        // Fallback: system default
        try {
            System.loadLibrary("MagickWand-7.Q16HDRI");
            return SymbolLookup.loaderLookup();
        } catch (UnsatisfiedLinkError ignored) {}

        System.loadLibrary("MagickWand-7.Q16");
        return SymbolLookup.loaderLookup();
    }
}
