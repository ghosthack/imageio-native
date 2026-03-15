package io.github.ghosthack.imageio.vips;

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
 * Panama FFM downcalls to libvips for image decoding.
 * <p>
 * Requires libvips (8.x) and its GLib/GObject dependencies installed on the
 * system.  Use {@link #isAvailable()} to check at runtime.
 * <p>
 * The library path can be overridden with the system property
 * {@code imageio.native.vips.lib} (e.g.
 * {@code -Dimageio.native.vips.lib=/custom/path/libvips.dylib}).
 * <p>
 * All methods are thread-safe — libvips is fully re-entrant.
 */
final class VipsNative {

    private VipsNative() {}

    // ── Constants ───────────────────────────────────────────────────────

    private static final int VIPS_INTERPRETATION_sRGB = 22;
    private static final int VIPS_ACCESS_SEQUENTIAL = 1;

    /** Maximum total pixel count to prevent OOM (same as AppleNative/WicNative). */
    private static final long MAX_PIXELS = 256L * 1024 * 1024;

    // ── Library loading ─────────────────────────────────────────────────

    private static final boolean AVAILABLE;
    private static final SymbolLookup LOOKUP;

    private static final Linker LINKER = Linker.nativeLinker();

    // ── Downcall handles ────────────────────────────────────────────────

    // vips
    private static final MethodHandle vips_init;
    private static final MethodHandle vips_image_new_from_file;
    private static final MethodHandle vips_image_new_from_buffer;
    private static final MethodHandle vips_image_get_width;
    private static final MethodHandle vips_image_get_height;
    private static final MethodHandle vips_image_get_bands;
    private static final MethodHandle vips_image_hasalpha;
    private static final MethodHandle vips_colourspace;
    private static final MethodHandle vips_premultiply;
    private static final MethodHandle vips_addalpha;
    private static final MethodHandle vips_cast_uchar;
    private static final MethodHandle vips_image_write_to_memory;
    private static final MethodHandle vips_foreign_find_load_buffer;
    private static final MethodHandle vips_error_buffer;
    private static final MethodHandle vips_error_clear;

    // GLib
    private static final MethodHandle g_object_unref;
    private static final MethodHandle g_free;

    static {
        boolean ok = false;
        SymbolLookup lk = null;
        try {
            lk = loadLibraries();
            ok = (lk != null);
        } catch (Throwable t) {
            // Library not found — isAvailable() returns false
        }
        LOOKUP = lk;

        if (ok) {
            // vips_init
            vips_init = downcall("vips_init",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

            // vips_image_new_from_file(const char* name, ...) — variadic, 1 extra arg (NULL)
            vips_image_new_from_file = LINKER.downcallHandle(
                    LOOKUP.find("vips_image_new_from_file").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS),
                    Linker.Option.firstVariadicArg(1));

            // vips_image_new_from_buffer(void*, size_t, const char* option_string, ...) — variadic
            vips_image_new_from_buffer = LINKER.downcallHandle(
                    LOOKUP.find("vips_image_new_from_buffer").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            ValueLayout.JAVA_LONG, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS),
                    Linker.Option.firstVariadicArg(3));

            // Dimension queries
            vips_image_get_width = downcall("vips_image_get_width",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
            vips_image_get_height = downcall("vips_image_get_height",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
            vips_image_get_bands = downcall("vips_image_get_bands",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
            vips_image_hasalpha = downcall("vips_image_hasalpha",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

            // vips_colourspace(VipsImage* in, VipsImage** out, VipsInterpretation space, ...) — variadic
            vips_colourspace = LINKER.downcallHandle(
                    LOOKUP.find("vips_colourspace").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS),
                    Linker.Option.firstVariadicArg(3));

            // vips_premultiply(VipsImage* in, VipsImage** out, ...) — variadic
            vips_premultiply = LINKER.downcallHandle(
                    LOOKUP.find("vips_premultiply").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS),
                    Linker.Option.firstVariadicArg(2));

            // vips_addalpha(VipsImage* in, VipsImage** out, ...) — variadic
            vips_addalpha = LINKER.downcallHandle(
                    LOOKUP.find("vips_addalpha").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS),
                    Linker.Option.firstVariadicArg(2));

            // vips_cast_uchar(VipsImage* in, VipsImage** out, ...) — variadic
            vips_cast_uchar = LINKER.downcallHandle(
                    LOOKUP.find("vips_cast_uchar").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS),
                    Linker.Option.firstVariadicArg(2));

            // vips_image_write_to_memory(VipsImage*, size_t*)
            vips_image_write_to_memory = downcall("vips_image_write_to_memory",
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));

            // vips_foreign_find_load_buffer(void*, size_t)
            vips_foreign_find_load_buffer = downcall("vips_foreign_find_load_buffer",
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));

            // Error handling
            vips_error_buffer = downcall("vips_error_buffer",
                    FunctionDescriptor.of(ValueLayout.ADDRESS));
            vips_error_clear = downcall("vips_error_clear",
                    FunctionDescriptor.ofVoid());

            // GLib
            g_object_unref = downcall("g_object_unref",
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
            g_free = downcall("g_free",
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));

            // Initialize vips
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment argv0 = arena.allocateFrom("imageio-native", StandardCharsets.UTF_8);
                int rc = (int) vips_init.invokeExact(argv0);
                if (rc != 0) ok = false;
            } catch (Throwable t) {
                ok = false;
            }
        } else {
            vips_init = null;
            vips_image_new_from_file = null;
            vips_image_new_from_buffer = null;
            vips_image_get_width = null;
            vips_image_get_height = null;
            vips_image_get_bands = null;
            vips_image_hasalpha = null;
            vips_colourspace = null;
            vips_premultiply = null;
            vips_addalpha = null;
            vips_cast_uchar = null;
            vips_image_write_to_memory = null;
            vips_foreign_find_load_buffer = null;
            vips_error_buffer = null;
            vips_error_clear = null;
            g_object_unref = null;
            g_free = null;
        }
        AVAILABLE = ok;
    }

    // ── Public API ──────────────────────────────────────────────────────

    /**
     * Returns {@code true} if libvips was loaded and initialised successfully.
     */
    static boolean isAvailable() {
        return AVAILABLE;
    }

    /**
     * Probes whether libvips can decode data with the given header bytes.
     */
    static boolean canDecode(byte[] header, int len) {
        if (!AVAILABLE) return false;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buf = arena.allocateFrom(ValueLayout.JAVA_BYTE, header);
            MemorySegment loader = (MemorySegment) vips_foreign_find_load_buffer.invokeExact(
                    buf, (long) len);
            // Clear any error from a failed probe (expected for unknown formats)
            vips_error_clear.invokeExact();
            return !MemorySegment.NULL.equals(loader)
                    && loader.address() != 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Returns image dimensions without full pixel decode.
     */
    static int[] getSize(byte[] imageData) throws IOException {
        if (!AVAILABLE) throw new IIOException("libvips is not available");
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buf = arena.allocateFrom(ValueLayout.JAVA_BYTE, imageData);
            MemorySegment img = (MemorySegment) vips_image_new_from_buffer.invokeExact(
                    buf, (long) imageData.length, MemorySegment.NULL, MemorySegment.NULL);
            if (MemorySegment.NULL.equals(img))
                throw new IIOException("vips load failed: " + errorMessage());
            try {
                int w = (int) vips_image_get_width.invokeExact(img);
                int h = (int) vips_image_get_height.invokeExact(img);
                if (w <= 0 || h <= 0)
                    throw new IIOException("Invalid image dimensions: " + w + "x" + h);
                return new int[]{w, h};
            } finally {
                g_object_unref.invokeExact(img);
            }
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw new IIOException("vips getSize failed", t);
        }
    }

    /**
     * Returns image dimensions by reading directly from a file path.
     */
    static int[] getSizeFromPath(String path) throws IOException {
        if (!AVAILABLE) throw new IIOException("libvips is not available");
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment cpath = arena.allocateFrom(path, StandardCharsets.UTF_8);
            MemorySegment img = (MemorySegment) vips_image_new_from_file.invokeExact(
                    cpath, MemorySegment.NULL);
            if (MemorySegment.NULL.equals(img))
                throw new IIOException("vips load failed for: " + path + " — " + errorMessage());
            try {
                int w = (int) vips_image_get_width.invokeExact(img);
                int h = (int) vips_image_get_height.invokeExact(img);
                if (w <= 0 || h <= 0)
                    throw new IIOException("Invalid image dimensions: " + w + "x" + h);
                return new int[]{w, h};
            } finally {
                g_object_unref.invokeExact(img);
            }
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw new IIOException("vips getSizeFromPath failed for: " + path, t);
        }
    }

    /**
     * Decodes raw image bytes through libvips into a {@code TYPE_INT_ARGB_PRE} BufferedImage.
     */
    static BufferedImage decode(byte[] imageData) throws IOException {
        if (!AVAILABLE) throw new IIOException("libvips is not available");
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buf = arena.allocateFrom(ValueLayout.JAVA_BYTE, imageData);
            MemorySegment img = (MemorySegment) vips_image_new_from_buffer.invokeExact(
                    buf, (long) imageData.length, MemorySegment.NULL, MemorySegment.NULL);
            if (MemorySegment.NULL.equals(img))
                throw new IIOException("vips load failed: " + errorMessage());
            try {
                return decodeFromVipsImage(arena, img);
            } finally {
                g_object_unref.invokeExact(img);
            }
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw new IIOException("vips decode failed", t);
        }
    }

    /**
     * Decodes an image directly from a file path.
     * Avoids loading the entire file into the Java heap.
     */
    static BufferedImage decodeFromPath(String path) throws IOException {
        if (!AVAILABLE) throw new IIOException("libvips is not available");
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment accessKey = arena.allocateFrom("access", StandardCharsets.UTF_8);
            MemorySegment cpath = arena.allocateFrom(path, StandardCharsets.UTF_8);

            // Load with sequential access hint for streaming performance.
            // vips_image_new_from_file is variadic: (path, "access", int, NULL)
            // We need a specific descriptor for this particular variadic invocation.
            MethodHandle loadWithAccess = LINKER.downcallHandle(
                    LOOKUP.find("vips_image_new_from_file").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            ValueLayout.JAVA_INT, ValueLayout.ADDRESS),
                    Linker.Option.firstVariadicArg(1));

            MemorySegment img = (MemorySegment) loadWithAccess.invokeExact(
                    cpath, accessKey, VIPS_ACCESS_SEQUENTIAL, MemorySegment.NULL);
            if (MemorySegment.NULL.equals(img))
                throw new IIOException("vips load failed for: " + path + " — " + errorMessage());
            try {
                return decodeFromVipsImage(arena, img);
            } finally {
                g_object_unref.invokeExact(img);
            }
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw new IIOException("vips decodeFromPath failed for: " + path, t);
        }
    }

    // ── Internal decode pipeline ────────────────────────────────────────

    /**
     * Converts a loaded VipsImage to BufferedImage(TYPE_INT_ARGB_PRE).
     * Pipeline: colourspace(sRGB) → [premultiply if alpha] → cast_uchar → write_to_memory → repack RGBA→ARGB.
     */
    private static BufferedImage decodeFromVipsImage(Arena arena, MemorySegment img)
            throws Throwable {

        int w = (int) vips_image_get_width.invokeExact(img);
        int h = (int) vips_image_get_height.invokeExact(img);
        if (w <= 0 || h <= 0)
            throw new IIOException("Invalid image dimensions: " + w + "x" + h);

        long totalPixels = (long) w * h;
        if (totalPixels > MAX_PIXELS)
            throw new IIOException("Image too large: " + w + "x" + h + " (" + totalPixels
                    + " pixels exceeds limit of " + MAX_PIXELS + ")");

        boolean hasAlpha = ((int) vips_image_hasalpha.invokeExact(img)) != 0;

        // Step 1: Convert to sRGB
        MemorySegment ppSrgb = arena.allocate(ValueLayout.ADDRESS);
        int rc = (int) vips_colourspace.invokeExact(img, ppSrgb,
                VIPS_INTERPRETATION_sRGB, MemorySegment.NULL);
        if (rc != 0) throw new IIOException("vips_colourspace failed: " + errorMessage());
        MemorySegment srgb = ppSrgb.get(ValueLayout.ADDRESS, 0);

        MemorySegment current = srgb;
        MemorySegment premul = MemorySegment.NULL;
        MemorySegment added = MemorySegment.NULL;
        MemorySegment uchar = MemorySegment.NULL;
        MemorySegment pixelBuf = MemorySegment.NULL;

        try {
            // Step 2: Premultiply alpha (if present)
            if (hasAlpha) {
                MemorySegment ppPremul = arena.allocate(ValueLayout.ADDRESS);
                rc = (int) vips_premultiply.invokeExact(current, ppPremul, MemorySegment.NULL);
                if (rc != 0) throw new IIOException("vips_premultiply failed: " + errorMessage());
                premul = ppPremul.get(ValueLayout.ADDRESS, 0);
                current = premul;
            } else {
                // Add opaque alpha for uniform 4-band output
                MemorySegment ppAdded = arena.allocate(ValueLayout.ADDRESS);
                rc = (int) vips_addalpha.invokeExact(current, ppAdded, MemorySegment.NULL);
                if (rc != 0) throw new IIOException("vips_addalpha failed: " + errorMessage());
                added = ppAdded.get(ValueLayout.ADDRESS, 0);
                current = added;
            }

            // Step 3: Cast to unsigned 8-bit
            MemorySegment ppUchar = arena.allocate(ValueLayout.ADDRESS);
            rc = (int) vips_cast_uchar.invokeExact(current, ppUchar, MemorySegment.NULL);
            if (rc != 0) throw new IIOException("vips_cast_uchar failed: " + errorMessage());
            uchar = ppUchar.get(ValueLayout.ADDRESS, 0);

            // Step 4: Write to memory (RGBA bytes, band-interleaved)
            MemorySegment pSize = arena.allocate(ValueLayout.JAVA_LONG);
            pixelBuf = (MemorySegment) vips_image_write_to_memory.invokeExact(uchar, pSize);
            if (MemorySegment.NULL.equals(pixelBuf))
                throw new IIOException("vips_image_write_to_memory failed: " + errorMessage());

            long bufSize = pSize.get(ValueLayout.JAVA_LONG, 0);
            long expectedSize = (long) w * h * 4;
            if (bufSize < expectedSize)
                throw new IIOException("Pixel buffer too small: " + bufSize + " < " + expectedSize);

            // Step 5: Repack RGBA → ARGB int[] for TYPE_INT_ARGB_PRE
            pixelBuf = pixelBuf.reinterpret(bufSize);
            BufferedImage result = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB_PRE);
            int[] dest = ((DataBufferInt) result.getRaster().getDataBuffer()).getData();

            for (int i = 0, off = 0; i < dest.length; i++, off += 4) {
                int r = pixelBuf.get(ValueLayout.JAVA_BYTE, off) & 0xFF;
                int g = pixelBuf.get(ValueLayout.JAVA_BYTE, off + 1) & 0xFF;
                int b = pixelBuf.get(ValueLayout.JAVA_BYTE, off + 2) & 0xFF;
                int a = pixelBuf.get(ValueLayout.JAVA_BYTE, off + 3) & 0xFF;
                dest[i] = (a << 24) | (r << 16) | (g << 8) | b;
            }

            return result;
        } finally {
            if (!MemorySegment.NULL.equals(pixelBuf)) g_free.invokeExact(pixelBuf);
            if (!MemorySegment.NULL.equals(uchar)) g_object_unref.invokeExact(uchar);
            if (!MemorySegment.NULL.equals(added)) g_object_unref.invokeExact(added);
            if (!MemorySegment.NULL.equals(premul)) g_object_unref.invokeExact(premul);
            g_object_unref.invokeExact(srgb);
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private static String errorMessage() {
        try {
            MemorySegment buf = (MemorySegment) vips_error_buffer.invokeExact();
            if (MemorySegment.NULL.equals(buf) || buf.address() == 0) return "(no error message)";
            String msg = buf.reinterpret(1024).getString(0, StandardCharsets.UTF_8);
            vips_error_clear.invokeExact();
            return msg;
        } catch (Throwable t) {
            return "(failed to read error buffer)";
        }
    }

    private static MethodHandle downcall(String name, FunctionDescriptor desc) {
        return LINKER.downcallHandle(
                LOOKUP.find(name).orElseThrow(() ->
                        new UnsatisfiedLinkError("Symbol not found: " + name)),
                desc);
    }

    private static SymbolLookup loadLibraries() {
        // Load GLib first (needed for g_object_unref / g_free)
        loadLib("libgobject-2.0", new String[]{
                "/opt/local/lib/libgobject-2.0.dylib",          // MacPorts
                "/usr/local/lib/libgobject-2.0.dylib",          // Homebrew
                "/usr/lib/x86_64-linux-gnu/libgobject-2.0.so",  // Debian x86_64
                "/usr/lib/aarch64-linux-gnu/libgobject-2.0.so", // Debian aarch64
        });
        loadLib("libglib-2.0", new String[]{
                "/opt/local/lib/libglib-2.0.dylib",
                "/usr/local/lib/libglib-2.0.dylib",
                "/usr/lib/x86_64-linux-gnu/libglib-2.0.so",
                "/usr/lib/aarch64-linux-gnu/libglib-2.0.so",
        });

        // Load libvips
        String explicit = System.getProperty("imageio.native.vips.lib");
        if (explicit != null) {
            System.load(explicit);
            return SymbolLookup.loaderLookup();
        }

        String[] paths = {
                "/opt/local/lib/libvips.dylib",
                "/usr/local/lib/libvips.dylib",
                "/usr/lib/x86_64-linux-gnu/libvips.so",
                "/usr/lib/aarch64-linux-gnu/libvips.so",
                "/usr/lib/libvips.so",
        };
        for (String p : paths) {
            if (Files.exists(Path.of(p))) {
                System.load(p);
                return SymbolLookup.loaderLookup();
            }
        }

        // Fallback: system default
        System.loadLibrary("vips");
        return SymbolLookup.loaderLookup();
    }

    /**
     * Loads a shared library by trying known paths, silently ignoring if already loaded.
     */
    private static void loadLib(String baseName, String[] paths) {
        for (String p : paths) {
            if (Files.exists(Path.of(p))) {
                try {
                    System.load(p);
                    return;
                } catch (UnsatisfiedLinkError ignored) {
                    // Already loaded or wrong arch — try next
                }
            }
        }
        try {
            System.loadLibrary(baseName.replace("lib", ""));
        } catch (UnsatisfiedLinkError ignored) {
            // Will fail later when symbols are looked up
        }
    }
}
