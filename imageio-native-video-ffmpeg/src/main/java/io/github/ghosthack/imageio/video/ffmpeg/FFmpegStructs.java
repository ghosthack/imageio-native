package io.github.ghosthack.imageio.video.ffmpeg;

/**
 * FFmpeg struct field offsets, selected at runtime by {@code avcodec_version()}.
 * <p>
 * FFmpeg struct layouts are not ABI-stable across major versions. This class
 * provides offset tables for known versions. If the running FFmpeg version
 * is not recognized, {@link FFmpegNative#isAvailable()} returns {@code false}.
 * <p>
 * Offsets were measured using {@code offsetof()} on each target platform
 * and architecture (arm64/x86_64).
 */
final class FFmpegStructs {

    private FFmpegStructs() {}

    // ── AVFormatContext ─────────────────────────────────────────────────
    int avfmt_nb_streams;
    int avfmt_streams;
    int avfmt_duration;

    // ── AVStream ────────────────────────────────────────────────────────
    int avstream_time_base;      // AVRational (8 bytes: num + den)
    int avstream_r_frame_rate;   // AVRational
    int avstream_duration;
    int avstream_codecpar;

    // ── AVCodecParameters ───────────────────────────────────────────────
    int avpar_codec_type;
    int avpar_codec_id;
    int avpar_format;
    int avpar_width;
    int avpar_height;

    // ── AVFrame ─────────────────────────────────────────────────────────
    int avframe_data;       // uint8_t*[8]
    int avframe_linesize;   // int[8]
    int avframe_width;
    int avframe_height;
    int avframe_format;
    int avframe_pts;

    // ── AVPacket ────────────────────────────────────────────────────────
    int avpkt_stream_index;

    // ── Factory ─────────────────────────────────────────────────────────

    /**
     * Returns the offset table for the given libavcodec major version,
     * or {@code null} if the version is not supported.
     */
    static FFmpegStructs forVersion(int avcodecMajor) {
        return switch (avcodecMajor) {
            case 58 -> ffmpeg4();   // FFmpeg 4.x
            default -> null;
        };
    }

    /** FFmpeg 4.x (libavcodec major 58). */
    private static FFmpegStructs ffmpeg4() {
        FFmpegStructs s = new FFmpegStructs();
        // AVFormatContext
        s.avfmt_nb_streams = 44;
        s.avfmt_streams = 48;
        s.avfmt_duration = 1096;
        // AVStream
        s.avstream_time_base = 24;
        s.avstream_r_frame_rate = 192;
        s.avstream_duration = 40;
        s.avstream_codecpar = 208;
        // AVCodecParameters
        s.avpar_codec_type = 0;
        s.avpar_codec_id = 4;
        s.avpar_format = 28;
        s.avpar_width = 56;
        s.avpar_height = 60;
        // AVFrame
        s.avframe_data = 0;
        s.avframe_linesize = 64;
        s.avframe_width = 104;
        s.avframe_height = 108;
        s.avframe_format = 116;
        s.avframe_pts = 136;
        // AVPacket
        s.avpkt_stream_index = 36;
        return s;
    }
}
