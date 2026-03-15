package io.github.ghosthack.imageio.video.ffmpeg;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Panama FFM downcalls to FFmpeg (libavformat, libavcodec, libswscale, libavutil)
 * for video frame extraction.
 * <p>
 * Loads all four libraries at class-init time. If any library is missing or the
 * FFmpeg version is not supported (unknown struct offsets), {@link #isAvailable()}
 * returns {@code false}.
 * <p>
 * The library directory can be overridden with
 * {@code -Dimageio.native.ffmpeg.lib.dir=/path/to/libs}.
 */
final class FFmpegNative {

    private FFmpegNative() {}

    // ── Constants ───────────────────────────────────────────────────────

    static final long AV_TIME_BASE = 1_000_000L;
    static final int AVMEDIA_TYPE_VIDEO = 0;
    static final int AV_PIX_FMT_RGBA = 26;
    static final int SWS_BILINEAR = 2;
    static final int AVSEEK_FLAG_BACKWARD = 1;

    // AVERROR(EAGAIN) — platform-dependent, typically -11 or -35
    // We detect it at runtime by checking against -EAGAIN
    static final int AVERROR_EAGAIN;

    // ── Library + struct offsets ─────────────────────────────────────────

    static final boolean AVAILABLE;
    static final FFmpegStructs S;

    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup LOOKUP;

    // ── Downcall handles ────────────────────────────────────────────────

    // avformat
    static final MethodHandle avformat_open_input;
    static final MethodHandle avformat_find_stream_info;
    static final MethodHandle av_find_best_stream;
    static final MethodHandle av_seek_frame;
    static final MethodHandle av_read_frame;
    static final MethodHandle avformat_close_input;

    // avcodec
    static final MethodHandle avcodec_find_decoder;
    static final MethodHandle avcodec_alloc_context3;
    static final MethodHandle avcodec_parameters_to_context;
    static final MethodHandle avcodec_open2;
    static final MethodHandle avcodec_send_packet;
    static final MethodHandle avcodec_receive_frame;
    static final MethodHandle avcodec_free_context;
    static final MethodHandle avcodec_version;

    // avutil
    static final MethodHandle av_frame_alloc;
    static final MethodHandle av_frame_free;
    static final MethodHandle av_packet_alloc;
    static final MethodHandle av_packet_free;
    static final MethodHandle av_packet_unref;
    static final MethodHandle av_image_get_buffer_size;
    static final MethodHandle av_image_fill_arrays;

    // swscale
    static final MethodHandle sws_getContext;
    static final MethodHandle sws_scale;
    static final MethodHandle sws_freeContext;

    static {
        boolean ok = false;
        SymbolLookup lk = null;
        FFmpegStructs structs = null;
        int eagain = -11; // default for macOS/Linux

        try {
            lk = loadLibraries();
            ok = (lk != null);
        } catch (Throwable t) {
            // Libraries not found
        }
        LOOKUP = lk;

        if (ok) {
            // Version detection
            avcodec_version = downcall("avcodec_version",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT));

            try {
                int version = (int) avcodec_version.invokeExact();
                int major = version >> 16;
                structs = FFmpegStructs.forVersion(major);
                if (structs == null) {
                    System.err.println("imageio-native-video-ffmpeg: unsupported libavcodec major version "
                            + major + " (full version: " + version + "). Disabling FFmpeg backend.");
                    ok = false;
                }
            } catch (Throwable t) {
                ok = false;
            }
        } else {
            avcodec_version = null;
        }

        S = structs;

        // Detect AVERROR(EAGAIN) — on macOS EAGAIN=35, Linux EAGAIN=11
        try {
            String os = System.getProperty("os.name", "").toLowerCase();
            eagain = os.contains("mac") ? -35 : -11;
        } catch (Exception ignored) {}
        AVERROR_EAGAIN = eagain;

        if (ok) {
            // avformat
            avformat_open_input = downcall("avformat_open_input",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            avformat_find_stream_info = downcall("avformat_find_stream_info",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            av_find_best_stream = downcall("av_find_best_stream",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
            av_seek_frame = downcall("av_seek_frame",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT));
            av_read_frame = downcall("av_read_frame",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            avformat_close_input = downcall("avformat_close_input",
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));

            // avcodec
            avcodec_find_decoder = downcall("avcodec_find_decoder",
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
            avcodec_alloc_context3 = downcall("avcodec_alloc_context3",
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            avcodec_parameters_to_context = downcall("avcodec_parameters_to_context",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            avcodec_open2 = downcall("avcodec_open2",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            avcodec_send_packet = downcall("avcodec_send_packet",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            avcodec_receive_frame = downcall("avcodec_receive_frame",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            avcodec_free_context = downcall("avcodec_free_context",
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));

            // avutil
            av_frame_alloc = downcall("av_frame_alloc",
                    FunctionDescriptor.of(ValueLayout.ADDRESS));
            av_frame_free = downcall("av_frame_free",
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
            av_packet_alloc = downcall("av_packet_alloc",
                    FunctionDescriptor.of(ValueLayout.ADDRESS));
            av_packet_free = downcall("av_packet_free",
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
            av_packet_unref = downcall("av_packet_unref",
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
            av_image_get_buffer_size = downcall("av_image_get_buffer_size",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
            av_image_fill_arrays = downcall("av_image_fill_arrays",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_INT));

            // swscale
            sws_getContext = downcall("sws_getContext",
                    FunctionDescriptor.of(ValueLayout.ADDRESS,
                            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            sws_scale = downcall("sws_scale",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            sws_freeContext = downcall("sws_freeContext",
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
        } else {
            avformat_open_input = null;
            avformat_find_stream_info = null;
            av_find_best_stream = null;
            av_seek_frame = null;
            av_read_frame = null;
            avformat_close_input = null;
            avcodec_find_decoder = null;
            avcodec_alloc_context3 = null;
            avcodec_parameters_to_context = null;
            avcodec_open2 = null;
            avcodec_send_packet = null;
            avcodec_receive_frame = null;
            avcodec_free_context = null;
            av_frame_alloc = null;
            av_frame_free = null;
            av_packet_alloc = null;
            av_packet_free = null;
            av_packet_unref = null;
            av_image_get_buffer_size = null;
            av_image_fill_arrays = null;
            sws_getContext = null;
            sws_scale = null;
            sws_freeContext = null;
        }
        AVAILABLE = ok;
    }

    static boolean isAvailable() {
        return AVAILABLE;
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private static MethodHandle downcall(String name, FunctionDescriptor desc) {
        return LINKER.downcallHandle(
                LOOKUP.find(name).orElseThrow(() ->
                        new UnsatisfiedLinkError("FFmpeg symbol not found: " + name)),
                desc);
    }

    private static SymbolLookup loadLibraries() {
        String explicitDir = System.getProperty("imageio.native.ffmpeg.lib.dir");

        // Library load order: avutil first (dependency of avcodec/avformat)
        String[][] libs = {
                {"libavutil", "avutil"},
                {"libswresample", "swresample"},
                {"libavcodec", "avcodec"},
                {"libavformat", "avformat"},
                {"libswscale", "swscale"},
        };

        String[] searchDirs = (explicitDir != null)
                ? new String[]{explicitDir}
                : new String[]{
                    "/opt/local/lib",                          // MacPorts
                    "/usr/local/lib",                          // Homebrew Intel
                    "/opt/homebrew/lib",                       // Homebrew Apple Silicon
                    "/usr/lib/x86_64-linux-gnu",               // Debian x86_64
                    "/usr/lib/aarch64-linux-gnu",              // Debian aarch64
                    "/usr/lib",                                // Generic
                };

        for (String[] lib : libs) {
            String baseName = lib[0];
            String shortName = lib[1];
            boolean loaded = false;

            for (String dir : searchDirs) {
                // Try .dylib (macOS) then .so (Linux)
                for (String ext : new String[]{".dylib", ".so"}) {
                    Path p = Path.of(dir, baseName + ext);
                    if (Files.exists(p)) {
                        try {
                            System.load(p.toString());
                            loaded = true;
                            break;
                        } catch (UnsatisfiedLinkError ignored) {}
                    }
                }
                if (loaded) break;
            }

            if (!loaded) {
                try {
                    System.loadLibrary(shortName);
                } catch (UnsatisfiedLinkError e) {
                    // swresample is optional — some builds don't have it
                    if (!"swresample".equals(shortName)) throw e;
                }
            }
        }

        return SymbolLookup.loaderLookup();
    }
}
