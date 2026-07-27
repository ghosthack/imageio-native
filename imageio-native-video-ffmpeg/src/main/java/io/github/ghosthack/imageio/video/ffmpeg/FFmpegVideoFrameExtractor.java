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
 */
public class FFmpegVideoFrameExtractor implements VideoFrameExtractorProvider {

    private static final int EXPECTED_AVFORMAT_MAJOR = 62; // FFmpeg 8.x
    private static final int AVSEEK_FLAG_BACKWARD = 1;
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
    public BufferedImage extractFrame(Path videoFile, Duration time) throws IOException {
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

                MemorySegment packet = FFmpeg.av_packet_alloc();
                if (MemorySegment.NULL.equals(packet))
                    throw new IOException("av_packet_alloc failed");
                packet = packet.reinterpret(AVPacket.layout().byteSize());
                ppPacket.set(ValueLayout.ADDRESS, 0, packet);

                if (!time.isZero()) {
                    MemorySegment timeBase = AVStream.time_base(stream);
                    int tbNum = AVRational.num(timeBase);
                    int tbDen = AVRational.den(timeBase);
                    if (tbNum > 0 && tbDen > 0) {
                        long targetMicros = time.toNanos() / 1_000;
                        long targetTs = Math.multiplyExact(targetMicros, tbDen)
                                / ((long) tbNum * FFmpeg.AV_TIME_BASE());
                        check(FFmpeg.av_seek_frame(
                                fmtCtx, streamIdx, targetTs, AVSEEK_FLAG_BACKWARD),
                                "av_seek_frame");
                    }
                }

                boolean gotFrame = decodeFrame(fmtCtx, codecCtx, frame, packet, streamIdx);
                if (!gotFrame)
                    throw new IOException("Failed to decode any frame from " + videoFile);

                int width = AVFrame.width(frame);
                int height = AVFrame.height(frame);
                int frameFormat = AVFrame.format(frame);
                if (width <= 0 || height <= 0)
                    throw new IOException("Invalid frame dimensions: " + width + "x" + height);

                swsCtx = FFmpeg.sws_getContext(
                        width, height, frameFormat,
                        width, height, FFmpeg.AV_PIX_FMT_BGRA(),
                        FFmpeg.SWS_BILINEAR(),
                        MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL);
                if (MemorySegment.NULL.equals(swsCtx))
                    throw new IOException("sws_getContext failed");

                long dstBufSize = Math.multiplyExact(
                        Math.multiplyExact((long) width, height), 4L);
                MemorySegment dstBuf = arena.allocate(dstBufSize, 16);
                MemorySegment dstData = arena.allocate(ValueLayout.ADDRESS, 4);
                MemorySegment dstLinesize = arena.allocate(ValueLayout.JAVA_INT, 4);
                dstData.setAtIndex(ValueLayout.ADDRESS, 0, dstBuf);
                dstLinesize.setAtIndex(ValueLayout.JAVA_INT, 0, Math.multiplyExact(width, 4));

                // Use the field-slice accessors. jextract 22's indexed accessors for
                // array fields are relative to the field layout, not the struct.
                MemorySegment srcData = AVFrame.data(frame);
                MemorySegment srcLinesize = AVFrame.linesize(frame);
                int scaledRows = FFmpeg.sws_scale(
                        swsCtx, srcData, srcLinesize, 0, height, dstData, dstLinesize);
                if (scaledRows <= 0) throw new IOException("sws_scale failed: " + scaledRows);

                BufferedImage result = new BufferedImage(
                        width, height, BufferedImage.TYPE_INT_ARGB_PRE);
                int[] dest = ((DataBufferInt) result.getRaster().getDataBuffer()).getData();
                for (int i = 0, offset = 0; i < dest.length; i++, offset += 4) {
                    int b = dstBuf.get(ValueLayout.JAVA_BYTE, offset) & 0xFF;
                    int g = dstBuf.get(ValueLayout.JAVA_BYTE, offset + 1) & 0xFF;
                    int r = dstBuf.get(ValueLayout.JAVA_BYTE, offset + 2) & 0xFF;
                    int a = dstBuf.get(ValueLayout.JAVA_BYTE, offset + 3) & 0xFF;
                    dest[i] = (a << 24) | (r << 16) | (g << 8) | b;
                }
                return result;
            } finally {
                if (!MemorySegment.NULL.equals(swsCtx))
                    FFmpeg.sws_freeContext(swsCtx);
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

                int width = AVCodecParameters.width(codecpar);
                int height = AVCodecParameters.height(codecpar);
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

    private static boolean decodeFrame(
            MemorySegment fmtCtx,
            MemorySegment codecCtx,
            MemorySegment frame,
            MemorySegment packet,
            int streamIdx) throws IOException {
        while (true) {
            int readResult = FFmpeg.av_read_frame(fmtCtx, packet);
            if (readResult < 0) {
                int sendResult = FFmpeg.avcodec_send_packet(codecCtx, MemorySegment.NULL);
                if (sendResult < 0 && sendResult != FFmpeg.AVERROR_EOF())
                    throw new IOException("avcodec_send_packet flush failed: " + sendResult);
                int receiveResult = FFmpeg.avcodec_receive_frame(codecCtx, frame);
                return receiveResult == 0;
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

            int receiveResult = FFmpeg.avcodec_receive_frame(codecCtx, frame);
            if (receiveResult == 0) return true;
            if (receiveResult != AVERROR_EAGAIN && receiveResult != FFmpeg.AVERROR_EOF())
                throw new IOException("avcodec_receive_frame failed: " + receiveResult);
        }
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
