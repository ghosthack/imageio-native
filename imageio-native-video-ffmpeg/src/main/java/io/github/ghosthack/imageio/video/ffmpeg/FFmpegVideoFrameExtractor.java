package io.github.ghosthack.imageio.video.ffmpeg;

import io.github.ghosthack.ffmpegffm.ffmpeg.AVCodecParameters;
import io.github.ghosthack.ffmpegffm.ffmpeg.AVFormatContext;
import io.github.ghosthack.ffmpegffm.ffmpeg.AVFrame;
import io.github.ghosthack.ffmpegffm.ffmpeg.AVPacket;
import io.github.ghosthack.ffmpegffm.ffmpeg.AVRational;
import io.github.ghosthack.ffmpegffm.ffmpeg.AVStream;
import io.github.ghosthack.ffmpegffm.ffmpeg.FFmpeg;
import io.github.ghosthack.imageio.common.RoutingBackend;
import io.github.ghosthack.imageio.video.VideoFrameExtractorProvider;
import io.github.ghosthack.imageio.video.VideoInfo;

import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Video frame extraction backed by the {@code ffmpeg-ffm} Panama bindings and
 * their matching bundled FFmpeg native libraries.
 * <p>
 * Implements the full decode pipeline: open → find stream → seek → decode →
 * pixel format conversion → {@link BufferedImage}.
 * <p>
 * Frames are returned as displayed: the stream's display matrix (the rotation
 * phones record for portrait video) is applied, and {@link #getInfo} reports
 * the displayed size. A seek decodes forward from the preceding keyframe to
 * the first frame at or after the requested time.
 */
public class FFmpegVideoFrameExtractor implements VideoFrameExtractorProvider {

    private static final int EXPECTED_AVFORMAT_MAJOR = 62; // FFmpeg 8.x
    private static final int AVSEEK_FLAG_BACKWARD = 1;

    /** {@code AV_PKT_DATA_DISPLAYMATRIX}: nine 16.16 fixed-point int32 values. */
    private static final int AV_PKT_DATA_DISPLAYMATRIX = 5;
    /**
     * {@code AVPacketSideData} is {@code { uint8_t *data; size_t size; enum type; }}.
     * The bindings expose only the array pointer, so entries are read by offset.
     */
    private static final long SIDE_DATA_ENTRY_BYTES = 24;
    private static final long SIDE_DATA_SIZE_OFFSET = 8;
    private static final long SIDE_DATA_TYPE_OFFSET = 16;
    private static final int AVERROR_EAGAIN = isMac() ? -35 : -11;
    private static final boolean AVAILABLE = detectAvailability();

    @Override
    public boolean isAvailable() {
        return AVAILABLE;
    }

    @Override
    public String id() {
        return "ffmpeg";
    }

    @Override
    public Kind kind() {
        return RoutingBackend.Kind.PORTABLE;
    }

    @Override
    public boolean canDecode(Path videoFile) {
        if (!AVAILABLE || videoFile == null) return false;

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment ppFmtCtx = arena.allocate(ValueLayout.ADDRESS);
            ppFmtCtx.set(ValueLayout.ADDRESS, 0, MemorySegment.NULL);
            MemorySegment ppCodecCtx = arena.allocate(ValueLayout.ADDRESS);
            ppCodecCtx.set(ValueLayout.ADDRESS, 0, MemorySegment.NULL);
            MemorySegment cpath =
                    arena.allocateFrom(
                            videoFile.toAbsolutePath().toString(),
                            StandardCharsets.UTF_8);

            try {
                if (FFmpeg.avformat_open_input(
                                ppFmtCtx,
                                cpath,
                                MemorySegment.NULL,
                                MemorySegment.NULL)
                        < 0) {
                    return false;
                }

                MemorySegment fmtCtx =
                        ppFmtCtx
                                .get(ValueLayout.ADDRESS, 0)
                                .reinterpret(AVFormatContext.layout().byteSize());
                if (FFmpeg.avformat_find_stream_info(
                                fmtCtx, MemorySegment.NULL)
                        < 0) {
                    return false;
                }

                int streamIdx =
                        FFmpeg.av_find_best_stream(
                                fmtCtx,
                                FFmpeg.AVMEDIA_TYPE_VIDEO(),
                                -1,
                                -1,
                                MemorySegment.NULL,
                                0);
                if (streamIdx < 0) return false;

                MemorySegment streams =
                        AVFormatContext.streams(fmtCtx)
                                .reinterpret(
                                        (streamIdx + 1L)
                                                * ValueLayout.ADDRESS.byteSize());
                MemorySegment stream =
                        streams
                                .getAtIndex(ValueLayout.ADDRESS, streamIdx)
                                .reinterpret(AVStream.layout().byteSize());
                MemorySegment codecpar =
                        AVStream.codecpar(stream)
                                .reinterpret(
                                        AVCodecParameters.layout().byteSize());
                MemorySegment codec =
                        FFmpeg.avcodec_find_decoder(
                                AVCodecParameters.codec_id(codecpar));
                if (MemorySegment.NULL.equals(codec)) return false;

                MemorySegment codecCtx =
                        FFmpeg.avcodec_alloc_context3(codec);
                if (MemorySegment.NULL.equals(codecCtx)) return false;
                ppCodecCtx.set(ValueLayout.ADDRESS, 0, codecCtx);

                if (FFmpeg.avcodec_parameters_to_context(codecCtx, codecpar)
                        < 0) {
                    return false;
                }
                return FFmpeg.avcodec_open2(
                                codecCtx, codec, MemorySegment.NULL)
                        >= 0;
            } finally {
                if (ppCodecCtx.get(ValueLayout.ADDRESS, 0).address() != 0) {
                    FFmpeg.avcodec_free_context(ppCodecCtx);
                }
                if (ppFmtCtx.get(ValueLayout.ADDRESS, 0).address() != 0) {
                    FFmpeg.avformat_close_input(ppFmtCtx);
                }
            }
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Override
    public BufferedImage extractFrame(Path videoFile, Duration time) throws IOException {
        return extractFrame(videoFile, time, null);
    }

    @Override
    public BufferedImage extractFrame(
            Path videoFile, Duration time, Dimension renderSize)
            throws IOException {
        if (!AVAILABLE) throw new IOException("FFmpeg is not available");

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment ppFmtCtx = arena.allocate(ValueLayout.ADDRESS);
            ppFmtCtx.set(ValueLayout.ADDRESS, 0, MemorySegment.NULL);
            MemorySegment cpath = arena.allocateFrom(videoFile.toAbsolutePath().toString(),
                    StandardCharsets.UTF_8);

            check(FFmpeg.avformat_open_input(ppFmtCtx, cpath,
                    MemorySegment.NULL, MemorySegment.NULL), "avformat_open_input");

            MemorySegment fmtCtx = ppFmtCtx.get(ValueLayout.ADDRESS, 0)
                    .reinterpret(AVFormatContext.layout().byteSize());
            MemorySegment ppCodecCtx = arena.allocate(ValueLayout.ADDRESS);
            MemorySegment ppFrame = arena.allocate(ValueLayout.ADDRESS);
            MemorySegment ppNextFrame = arena.allocate(ValueLayout.ADDRESS);
            MemorySegment ppPacket = arena.allocate(ValueLayout.ADDRESS);
            MemorySegment swsCtx = MemorySegment.NULL;

            try {
                check(FFmpeg.avformat_find_stream_info(fmtCtx, MemorySegment.NULL),
                        "avformat_find_stream_info");

                int streamIdx = FFmpeg.av_find_best_stream(
                        fmtCtx, FFmpeg.AVMEDIA_TYPE_VIDEO(), -1, -1, MemorySegment.NULL, 0);
                if (streamIdx < 0) throw new IOException("No video stream found: " + streamIdx);

                MemorySegment streams = AVFormatContext.streams(fmtCtx)
                        .reinterpret((streamIdx + 1L) * ValueLayout.ADDRESS.byteSize());
                MemorySegment stream = streams.getAtIndex(ValueLayout.ADDRESS, streamIdx)
                        .reinterpret(AVStream.layout().byteSize());
                MemorySegment codecpar = AVStream.codecpar(stream)
                        .reinterpret(AVCodecParameters.layout().byteSize());

                int codecId = AVCodecParameters.codec_id(codecpar);
                MemorySegment codec = FFmpeg.avcodec_find_decoder(codecId);
                if (MemorySegment.NULL.equals(codec))
                    throw new IOException("No decoder found for codec ID " + codecId);

                MemorySegment codecCtx = FFmpeg.avcodec_alloc_context3(codec);
                if (MemorySegment.NULL.equals(codecCtx))
                    throw new IOException("avcodec_alloc_context3 failed");
                ppCodecCtx.set(ValueLayout.ADDRESS, 0, codecCtx);

                check(FFmpeg.avcodec_parameters_to_context(codecCtx, codecpar),
                        "avcodec_parameters_to_context");
                check(FFmpeg.avcodec_open2(codecCtx, codec, MemorySegment.NULL), "avcodec_open2");

                MemorySegment frame = FFmpeg.av_frame_alloc();
                if (MemorySegment.NULL.equals(frame))
                    throw new IOException("av_frame_alloc failed");
                frame = frame.reinterpret(AVFrame.layout().byteSize());
                ppFrame.set(ValueLayout.ADDRESS, 0, frame);
                int quarterTurns = displayQuarterTurns(codecpar);

                MemorySegment packet = FFmpeg.av_packet_alloc();
                if (MemorySegment.NULL.equals(packet))
                    throw new IOException("av_packet_alloc failed");
                packet = packet.reinterpret(AVPacket.layout().byteSize());
                ppPacket.set(ValueLayout.ADDRESS, 0, packet);

                long targetTs = FFmpeg.AV_NOPTS_VALUE();
                if (!time.isZero()) {
                    MemorySegment timeBase = AVStream.time_base(stream);
                    int tbNum = AVRational.num(timeBase);
                    int tbDen = AVRational.den(timeBase);
                    if (tbNum > 0 && tbDen > 0) {
                        long targetMicros = time.toNanos() / 1_000;
                        targetTs = Math.multiplyExact(targetMicros, tbDen)
                                / ((long) tbNum * FFmpeg.AV_TIME_BASE());
                        long startTime = AVStream.start_time(stream);
                        if (startTime != FFmpeg.AV_NOPTS_VALUE()) {
                            targetTs += startTime;
                        }
                        check(FFmpeg.av_seek_frame(
                                fmtCtx, streamIdx, targetTs, AVSEEK_FLAG_BACKWARD),
                                "av_seek_frame");
                    }
                }

                boolean[] drained = {false};
                if (!receiveFrame(fmtCtx, codecCtx, frame, packet, streamIdx, drained))
                    throw new IOException("Failed to decode any frame from " + videoFile);

                // The seek lands on the keyframe at or before the target.
                // Decode forward to the first frame at or after it; if the
                // stream ends first, keep the last frame that decoded.
                if (targetTs != FFmpeg.AV_NOPTS_VALUE()
                        && precedes(frame, targetTs)) {
                    MemorySegment next = FFmpeg.av_frame_alloc();
                    if (MemorySegment.NULL.equals(next))
                        throw new IOException("av_frame_alloc failed");
                    next = next.reinterpret(AVFrame.layout().byteSize());
                    ppNextFrame.set(ValueLayout.ADDRESS, 0, next);
                    while (precedes(frame, targetTs)
                            && receiveFrame(fmtCtx, codecCtx, next, packet, streamIdx, drained)) {
                        MemorySegment previous = frame;
                        frame = next;
                        next = previous;
                        FFmpeg.av_frame_unref(next);
                    }
                }

                int width = AVFrame.width(frame);
                int height = AVFrame.height(frame);
                int frameFormat = AVFrame.format(frame);
                if (width <= 0 || height <= 0)
                    throw new IOException("Invalid frame dimensions: " + width + "x" + height);

                // The render size describes the displayed frame; scale in
                // the stored orientation and rotate afterwards.
                boolean transposed = (quarterTurns & 1) != 0;
                int outputWidth = renderSize == null ? width
                        : transposed ? renderSize.height : renderSize.width;
                int outputHeight = renderSize == null ? height
                        : transposed ? renderSize.width : renderSize.height;
                swsCtx = FFmpeg.sws_getContext(
                        width, height, frameFormat,
                        outputWidth, outputHeight, FFmpeg.AV_PIX_FMT_BGRA(),
                        FFmpeg.SWS_BILINEAR(),
                        MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL);
                if (MemorySegment.NULL.equals(swsCtx))
                    throw new IOException("sws_getContext failed");

                long dstBufSize = Math.multiplyExact(
                        Math.multiplyExact(
                                (long) outputWidth, outputHeight), 4L);
                MemorySegment dstBuf = arena.allocate(dstBufSize, 16);
                MemorySegment dstData = arena.allocate(ValueLayout.ADDRESS, 4);
                MemorySegment dstLinesize = arena.allocate(ValueLayout.JAVA_INT, 4);
                dstData.setAtIndex(ValueLayout.ADDRESS, 0, dstBuf);
                dstLinesize.setAtIndex(
                        ValueLayout.JAVA_INT, 0,
                        Math.multiplyExact(outputWidth, 4));

                // Use the field-slice accessors. jextract 22's indexed accessors for
                // array fields are relative to the field layout, not the struct.
                MemorySegment srcData = AVFrame.data(frame);
                MemorySegment srcLinesize = AVFrame.linesize(frame);
                int scaledRows = FFmpeg.sws_scale(
                        swsCtx, srcData, srcLinesize, 0, height, dstData, dstLinesize);
                if (scaledRows <= 0) throw new IOException("sws_scale failed: " + scaledRows);

                BufferedImage result = new BufferedImage(
                        outputWidth, outputHeight,
                        BufferedImage.TYPE_INT_ARGB_PRE);
                int[] dest = ((DataBufferInt) result.getRaster().getDataBuffer()).getData();
                for (int i = 0, offset = 0; i < dest.length; i++, offset += 4) {
                    int b = dstBuf.get(ValueLayout.JAVA_BYTE, offset) & 0xFF;
                    int g = dstBuf.get(ValueLayout.JAVA_BYTE, offset + 1) & 0xFF;
                    int r = dstBuf.get(ValueLayout.JAVA_BYTE, offset + 2) & 0xFF;
                    int a = dstBuf.get(ValueLayout.JAVA_BYTE, offset + 3) & 0xFF;
                    dest[i] = (a << 24) | (r << 16) | (g << 8) | b;
                }
                return rotateClockwise(result, quarterTurns);
            } finally {
                if (!MemorySegment.NULL.equals(swsCtx))
                    FFmpeg.sws_freeContext(swsCtx);
                if (ppNextFrame.get(ValueLayout.ADDRESS, 0).address() != 0)
                    FFmpeg.av_frame_free(ppNextFrame);
                if (ppPacket.get(ValueLayout.ADDRESS, 0).address() != 0)
                    FFmpeg.av_packet_free(ppPacket);
                if (ppFrame.get(ValueLayout.ADDRESS, 0).address() != 0)
                    FFmpeg.av_frame_free(ppFrame);
                if (ppCodecCtx.get(ValueLayout.ADDRESS, 0).address() != 0)
                    FFmpeg.avcodec_free_context(ppCodecCtx);
                FFmpeg.avformat_close_input(ppFmtCtx);
            }
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw new IOException("FFmpeg frame extraction failed for: " + videoFile, t);
        }
    }

    @Override
    public boolean supportsRenderSizeReduction() {
        return true;
    }

    @Override
    public VideoInfo getInfo(Path videoFile) throws IOException {
        if (!AVAILABLE) throw new IOException("FFmpeg is not available");

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment ppFmtCtx = arena.allocate(ValueLayout.ADDRESS);
            ppFmtCtx.set(ValueLayout.ADDRESS, 0, MemorySegment.NULL);
            MemorySegment cpath = arena.allocateFrom(videoFile.toAbsolutePath().toString(),
                    StandardCharsets.UTF_8);

            check(FFmpeg.avformat_open_input(ppFmtCtx, cpath,
                    MemorySegment.NULL, MemorySegment.NULL), "avformat_open_input");

            try {
                MemorySegment fmtCtx = ppFmtCtx.get(ValueLayout.ADDRESS, 0)
                        .reinterpret(AVFormatContext.layout().byteSize());
                check(FFmpeg.avformat_find_stream_info(fmtCtx, MemorySegment.NULL),
                        "avformat_find_stream_info");

                long duration = AVFormatContext.duration(fmtCtx);
                Duration videoDuration = duration > 0
                        ? Duration.ofMillis(duration * 1_000 / FFmpeg.AV_TIME_BASE())
                        : Duration.ZERO;

                int streamIdx = FFmpeg.av_find_best_stream(
                        fmtCtx, FFmpeg.AVMEDIA_TYPE_VIDEO(), -1, -1, MemorySegment.NULL, 0);
                if (streamIdx < 0) throw new IOException("No video stream found: " + streamIdx);

                MemorySegment streams = AVFormatContext.streams(fmtCtx)
                        .reinterpret((streamIdx + 1L) * ValueLayout.ADDRESS.byteSize());
                MemorySegment stream = streams.getAtIndex(ValueLayout.ADDRESS, streamIdx)
                        .reinterpret(AVStream.layout().byteSize());
                MemorySegment codecpar = AVStream.codecpar(stream)
                        .reinterpret(AVCodecParameters.layout().byteSize());

                boolean transposed = (displayQuarterTurns(codecpar) & 1) != 0;
                int storedWidth = AVCodecParameters.width(codecpar);
                int storedHeight = AVCodecParameters.height(codecpar);
                int width = transposed ? storedHeight : storedWidth;
                int height = transposed ? storedWidth : storedHeight;
                int codecId = AVCodecParameters.codec_id(codecpar);

                MemorySegment frameRate = AVStream.r_frame_rate(stream);
                int fpsNum = AVRational.num(frameRate);
                int fpsDen = AVRational.den(frameRate);
                double fps = fpsDen > 0 ? (double) fpsNum / fpsDen : 0.0;

                MemorySegment codecName = FFmpeg.avcodec_get_name(codecId);
                String codec = MemorySegment.NULL.equals(codecName)
                        ? null
                        : codecName.getString(0);

                return new VideoInfo(width, height, videoDuration, codec, fps);
            } finally {
                FFmpeg.avformat_close_input(ppFmtCtx);
            }
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw new IOException("FFmpeg getInfo failed for: " + videoFile, t);
        }
    }

    /**
     * Receives the next decoded frame of the selected stream, feeding the
     * decoder packets as it asks for them.
     *
     * @param drained single-element flag recording that the demuxer reached
     *                the end and the decoder has been flushed
     * @return {@code false} once the decoder has no more frames
     */
    private static boolean receiveFrame(
            MemorySegment fmtCtx,
            MemorySegment codecCtx,
            MemorySegment frame,
            MemorySegment packet,
            int streamIdx,
            boolean[] drained) throws IOException {
        while (true) {
            int receiveResult = FFmpeg.avcodec_receive_frame(codecCtx, frame);
            if (receiveResult == 0) return true;
            if (receiveResult == FFmpeg.AVERROR_EOF()) return false;
            if (receiveResult != AVERROR_EAGAIN)
                throw new IOException("avcodec_receive_frame failed: " + receiveResult);
            if (drained[0]) return false;

            if (FFmpeg.av_read_frame(fmtCtx, packet) < 0) {
                int sendResult = FFmpeg.avcodec_send_packet(codecCtx, MemorySegment.NULL);
                if (sendResult < 0 && sendResult != FFmpeg.AVERROR_EOF())
                    throw new IOException("avcodec_send_packet flush failed: " + sendResult);
                drained[0] = true;
                continue;
            }
            if (AVPacket.stream_index(packet) != streamIdx) {
                FFmpeg.av_packet_unref(packet);
                continue;
            }
            int sendResult;
            try {
                sendResult = FFmpeg.avcodec_send_packet(codecCtx, packet);
            } finally {
                FFmpeg.av_packet_unref(packet);
            }
            if (sendResult < 0)
                throw new IOException("avcodec_send_packet failed: " + sendResult);
        }
    }

    /** Returns whether a frame is presented strictly before the target. */
    private static boolean precedes(MemorySegment frame, long targetTs) {
        long pts = AVFrame.pts(frame);
        return pts != FFmpeg.AV_NOPTS_VALUE() && pts < targetTs;
    }

    /**
     * Returns the clockwise quarter turns (0-3) the stream's display matrix
     * asks for, or 0 when the stream has none.
     * <p>
     * Mirrors {@code av_display_rotation_get}, whose result is the
     * counter-clockwise angle; players rotate the picture by its negation.
     */
    private static int displayQuarterTurns(MemorySegment codecpar) {
        int count = AVCodecParameters.nb_coded_side_data(codecpar);
        MemorySegment entries = AVCodecParameters.coded_side_data(codecpar);
        if (count <= 0 || MemorySegment.NULL.equals(entries)) return 0;
        entries = entries.reinterpret(count * SIDE_DATA_ENTRY_BYTES);
        for (int i = 0; i < count; i++) {
            long entry = i * SIDE_DATA_ENTRY_BYTES;
            int type = entries.get(
                    ValueLayout.JAVA_INT, entry + SIDE_DATA_TYPE_OFFSET);
            long size = entries.get(
                    ValueLayout.JAVA_LONG, entry + SIDE_DATA_SIZE_OFFSET);
            if (type != AV_PKT_DATA_DISPLAYMATRIX || size < 9 * 4) continue;
            MemorySegment matrix = entries.get(ValueLayout.ADDRESS, entry)
                    .reinterpret(9 * 4);
            double m0 = matrix.getAtIndex(ValueLayout.JAVA_INT, 0) / 65536.0;
            double m1 = matrix.getAtIndex(ValueLayout.JAVA_INT, 1) / 65536.0;
            double m3 = matrix.getAtIndex(ValueLayout.JAVA_INT, 3) / 65536.0;
            double m4 = matrix.getAtIndex(ValueLayout.JAVA_INT, 4) / 65536.0;
            double scaleX = Math.hypot(m0, m3);
            double scaleY = Math.hypot(m1, m4);
            if (scaleX == 0 || scaleY == 0) return 0;
            double clockwiseDegrees =
                    Math.toDegrees(Math.atan2(m1 / scaleY, m0 / scaleX));
            long turns = Math.round(clockwiseDegrees / 90.0);
            return (int) (((turns % 4) + 4) % 4);
        }
        return 0;
    }

    /** Rotates an image clockwise by the given number of quarter turns. */
    private static BufferedImage rotateClockwise(
            BufferedImage source, int quarterTurns) {
        if (quarterTurns == 0) return source;
        int w = source.getWidth();
        int h = source.getHeight();
        boolean transposed = (quarterTurns & 1) != 0;
        int outW = transposed ? h : w;
        int outH = transposed ? w : h;
        BufferedImage result = new BufferedImage(
                outW, outH, BufferedImage.TYPE_INT_ARGB_PRE);
        int[] src = ((DataBufferInt) source.getRaster().getDataBuffer()).getData();
        int[] dst = ((DataBufferInt) result.getRaster().getDataBuffer()).getData();
        for (int y = 0; y < outH; y++) {
            for (int x = 0; x < outW; x++) {
                int sx;
                int sy;
                switch (quarterTurns) {
                    case 1 -> { sx = y; sy = h - 1 - x; }
                    case 2 -> { sx = w - 1 - x; sy = h - 1 - y; }
                    default -> { sx = w - 1 - y; sy = x; }
                }
                dst[y * outW + x] = src[sy * w + sx];
            }
        }
        return result;
    }

    private static void check(int result, String operation) throws IOException {
        if (result < 0) throw new IOException(operation + " failed: " + result);
    }

    private static boolean detectAvailability() {
        try {
            int version = FFmpeg.avformat_version();
            int major = version >>> 16;
            if (major != EXPECTED_AVFORMAT_MAJOR) {
                System.err.println("imageio-native-video-ffmpeg: expected FFmpeg 8.x "
                        + "(libavformat major " + EXPECTED_AVFORMAT_MAJOR + ") but found major "
                        + major + ". Disabling FFmpeg backend.");
                return false;
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase().contains("mac");
    }
}
