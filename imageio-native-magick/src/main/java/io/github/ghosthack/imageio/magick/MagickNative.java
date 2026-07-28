package io.github.ghosthack.imageio.magick;

import io.github.ghosthack.imageio.common.NativeDecodeRequest;
import io.github.ghosthack.imageio.common.ImageReadParamSupport;

import javax.imageio.IIOException;
import java.awt.Dimension;
import java.awt.Rectangle;
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

    /** FilterType: TriangleFilter (bilinear reconstruction). */
    private static final int TriangleFilter = 3;

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
    private static final MethodHandle MagickPingImage;
    private static final MethodHandle MagickPingImageBlob;
    private static final MethodHandle MagickGetImageWidth;
    private static final MethodHandle MagickGetImageHeight;
    private static final MethodHandle MagickSetImageAlphaChannel;
    private static final MethodHandle MagickResizeImage;
    private static final MethodHandle MagickExportImagePixels;
    private static final MethodHandle MagickGetException;
    private static final MethodHandle MagickRelinquishMemory;

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
            MagickReadImage = downcall("MagickReadImage",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            MagickReadImageBlob = downcall("MagickReadImageBlob",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
            MagickPingImage = downcall("MagickPingImage",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS));
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
            MagickResizeImage = downcall("MagickResizeImage",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS,
                            ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_INT));
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
            MagickReadImage = null;
            MagickReadImageBlob = null;
            MagickPingImage = null;
            MagickPingImageBlob = null;
            MagickGetImageWidth = null;
            MagickGetImageHeight = null;
            MagickSetImageAlphaChannel = null;
            MagickResizeImage = null;
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
     * Uses {@code MagickPingImageBlob}, which reads metadata without decoding
     * the pixel raster. A failed probe declines the input; routing never runs a
     * speculative full decode or a decode-time fallback.
     */
    static boolean canDecode(byte[] header, int len) {
        if (!AVAILABLE) return false;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment wand = (MemorySegment) NewMagickWand.invokeExact();
            try {
                MemorySegment buf = arena.allocateFrom(ValueLayout.JAVA_BYTE, header);
                int rc = (int) MagickPingImageBlob.invokeExact(wand, buf, (long) len);
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
                int rc = (int) MagickPingImage.invokeExact(wand, cpath);
                if (rc != MagickTrue)
                    throw new IIOException("ImageMagick ping failed for: " + path
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
        return decode(imageData, (Dimension) null);
    }

    static BufferedImage decode(byte[] imageData, Dimension renderSize)
            throws IOException {
        return decode(imageData, renderSize, null);
    }

    static BufferedImage decode(
            byte[] imageData, NativeDecodeRequest request)
            throws IOException {
        return decode(imageData, request.sourceRenderSize(), request);
    }

    private static BufferedImage decode(
            byte[] imageData,
            Dimension renderSize,
            NativeDecodeRequest request) throws IOException {
        if (!AVAILABLE) throw new IIOException("ImageMagick is not available");
        int[] sourceSize = getSize(imageData);
        ImageReadParamSupport.validateIntermediateDimensions(
                sourceSize[0], sourceSize[1]);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment wand = (MemorySegment) NewMagickWand.invokeExact();
            try {
                MemorySegment buf = arena.allocateFrom(ValueLayout.JAVA_BYTE, imageData);
                int rc = (int) MagickReadImageBlob.invokeExact(wand, buf, (long) imageData.length);
                if (rc != MagickTrue)
                    throw new IIOException("ImageMagick read failed: " + errorMessage(arena, wand));

                resize(wand, renderSize);
                return exportPixels(arena, wand, request);
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
        return decodeFromPath(path, (Dimension) null);
    }

    static BufferedImage decodeFromPath(String path, Dimension renderSize)
            throws IOException {
        return decodeFromPath(path, renderSize, null);
    }

    static BufferedImage decodeFromPath(
            String path, NativeDecodeRequest request) throws IOException {
        return decodeFromPath(path, request.sourceRenderSize(), request);
    }

    private static BufferedImage decodeFromPath(
            String path,
            Dimension renderSize,
            NativeDecodeRequest request) throws IOException {
        if (!AVAILABLE) throw new IIOException("ImageMagick is not available");
        int[] sourceSize = getSizeFromPath(path);
        ImageReadParamSupport.validateIntermediateDimensions(
                sourceSize[0], sourceSize[1]);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment wand = (MemorySegment) NewMagickWand.invokeExact();
            try {
                MemorySegment cpath = arena.allocateFrom(path, StandardCharsets.UTF_8);
                int rc = (int) MagickReadImage.invokeExact(wand, cpath);
                if (rc != MagickTrue)
                    throw new IIOException("ImageMagick read failed for: " + path
                            + " — " + errorMessage(arena, wand));

                resize(wand, renderSize);
                return exportPixels(arena, wand, request);
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

    private static void resize(MemorySegment wand, Dimension renderSize)
            throws Throwable {
        if (renderSize == null) {
            return;
        }
        int rc = (int) MagickResizeImage.invokeExact(
                wand,
                (long) renderSize.width,
                (long) renderSize.height,
                TriangleFilter);
        if (rc != MagickTrue) {
            throw new IIOException("ImageMagick resize failed");
        }
    }

    /**
     * Exports pixels from the current wand image into a BufferedImage.
     */
    private static BufferedImage exportPixels(Arena arena, MemorySegment wand)
            throws Throwable {
        return exportPixels(arena, wand, null);
    }

    private static BufferedImage exportPixels(
            Arena arena, MemorySegment wand, NativeDecodeRequest request)
            throws Throwable {
        int w = (int) (long) MagickGetImageWidth.invokeExact(wand);
        int h = (int) (long) MagickGetImageHeight.invokeExact(wand);
        if (w <= 0 || h <= 0)
            throw new IIOException("Invalid image dimensions: " + w + "x" + h);

        Rectangle sourceRegion = request != null
                && request.hasSpatialSelection()
                ? request.sourceRegion()
                : new Rectangle(0, 0, w, h);
        int sourceXSubsampling = request != null
                ? request.sourceXSubsampling() : 1;
        int sourceYSubsampling = request != null
                ? request.sourceYSubsampling() : 1;
        int outputWidth = request != null
                && request.hasSpatialSelection()
                ? request.destinationRegion().width : w;
        int outputHeight = request != null
                && request.hasSpatialSelection()
                ? request.destinationRegion().height : h;

        ImageReadParamSupport.validateIntermediateDimensions(
                outputWidth, outputHeight);

        // Ensure alpha channel exists (opaque if image has no alpha)
        int alphaRc = (int) MagickSetImageAlphaChannel.invokeExact(wand, SetAlphaChannel);

        // Export as ARGB, 8-bit per channel
        long rowSize = (long) sourceRegion.width * 4;
        MemorySegment pixelBuf = arena.allocate(rowSize);
        MemorySegment map = arena.allocateFrom("ARGB", StandardCharsets.UTF_8);

        BufferedImage result = new BufferedImage(
                outputWidth, outputHeight, BufferedImage.TYPE_INT_ARGB_PRE);
        int[] dest = ((DataBufferInt) result.getRaster().getDataBuffer()).getData();

        for (int outputY = 0; outputY < outputHeight; outputY++) {
            long sourceY =
                    sourceRegion.y + (long) outputY * sourceYSubsampling;
            int rc = (int) MagickExportImagePixels.invokeExact(
                    wand,
                    (long) sourceRegion.x,
                    sourceY,
                    (long) sourceRegion.width,
                    1L,
                    map,
                    CharPixel,
                    pixelBuf);
            if (rc != MagickTrue) {
                throw new IIOException(
                        "MagickExportImagePixels failed: "
                                + errorMessage(arena, wand));
            }

            for (int outputX = 0; outputX < outputWidth; outputX++) {
                long off = (long) outputX * sourceXSubsampling * 4;
                int a = pixelBuf.get(ValueLayout.JAVA_BYTE, off) & 0xFF;
                int r = pixelBuf.get(ValueLayout.JAVA_BYTE, off + 1) & 0xFF;
                int g = pixelBuf.get(ValueLayout.JAVA_BYTE, off + 2) & 0xFF;
                int b = pixelBuf.get(ValueLayout.JAVA_BYTE, off + 3) & 0xFF;

                if (a == 0) {
                    dest[outputY * outputWidth + outputX] = 0;
                } else if (a == 255) {
                    dest[outputY * outputWidth + outputX] =
                            0xFF000000 | (r << 16) | (g << 8) | b;
                } else {
                    r = (r * a + 127) / 255;
                    g = (g * a + 127) / 255;
                    b = (b * a + 127) / 255;
                    dest[outputY * outputWidth + outputX] =
                            (a << 24) | (r << 16) | (g << 8) | b;
                }
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
