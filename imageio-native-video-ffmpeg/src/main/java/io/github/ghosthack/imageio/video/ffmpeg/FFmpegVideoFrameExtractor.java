package io.github.ghosthack.imageio.video.ffmpeg;

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
import java.util.Map;

import static io.github.ghosthack.imageio.video.ffmpeg.FFmpegNative.*;

/**
 * Video frame extraction backed by FFmpeg via Panama FFM.
 * <p>
 * Implements the full decode pipeline: open → find stream → seek → decode →
 * pixel format conversion → BufferedImage.
 */
public class FFmpegVideoFrameExtractor implements VideoFrameExtractorProvider {

    /** Codec ID → human-readable name mapping for common codecs. */
    private static final Map<Integer, String> CODEC_NAMES = Map.ofEntries(
            Map.entry(1, "mpeg1"),
            Map.entry(2, "mpeg2"),
            Map.entry(12, "mpeg4"),
            Map.entry(27, "h264"),
            Map.entry(139, "hevc"),
            Map.entry(173, "vp8"),
            Map.entry(167, "vp9"),
            Map.entry(225, "av1"),
            Map.entry(86018, "wmv1"),
            Map.entry(86019, "wmv2"),
            Map.entry(86020, "wmv3")
    );

    @Override
    public boolean isAvailable() {
        return FFmpegNative.isAvailable();
    }

    @Override
    public String backendName() {
        return "ffmpeg";
    }

    @Override
    public BufferedImage extractFrame(Path videoFile, Duration time) throws IOException {
        if (!AVAILABLE) throw new IOException("FFmpeg is not available");
        FFmpegStructs s = S;

        try (Arena arena = Arena.ofConfined()) {
            // 1. Open file
            MemorySegment ppFmtCtx = arena.allocate(ValueLayout.ADDRESS);
            ppFmtCtx.set(ValueLayout.ADDRESS, 0, MemorySegment.NULL);
            MemorySegment cpath = arena.allocateFrom(videoFile.toAbsolutePath().toString(),
                    StandardCharsets.UTF_8);

            int rc = (int) avformat_open_input.invokeExact(ppFmtCtx, cpath,
                    MemorySegment.NULL, MemorySegment.NULL);
            if (rc < 0) throw new IOException("avformat_open_input failed: " + rc);
            MemorySegment fmtCtx = ppFmtCtx.get(ValueLayout.ADDRESS, 0);

            MemorySegment ppCodecCtx = arena.allocate(ValueLayout.ADDRESS);
            MemorySegment ppFrame = arena.allocate(ValueLayout.ADDRESS);
            MemorySegment ppPacket = arena.allocate(ValueLayout.ADDRESS);
            MemorySegment swsCtx = MemorySegment.NULL;

            try {
                // 2. Find stream info
                rc = (int) avformat_find_stream_info.invokeExact(fmtCtx, MemorySegment.NULL);
                if (rc < 0) throw new IOException("avformat_find_stream_info failed: " + rc);

                // 3. Find best video stream
                int streamIdx = (int) av_find_best_stream.invokeExact(
                        fmtCtx, AVMEDIA_TYPE_VIDEO, -1, -1, MemorySegment.NULL, 0);
                if (streamIdx < 0) throw new IOException("No video stream found");

                // 4. Get stream + codec parameters
                MemorySegment streams = fmtCtx.reinterpret(Long.MAX_VALUE)
                        .get(ValueLayout.ADDRESS, s.avfmt_streams);
                MemorySegment stream = streams.reinterpret(Long.MAX_VALUE)
                        .get(ValueLayout.ADDRESS, (long) streamIdx * ValueLayout.ADDRESS.byteSize());
                MemorySegment codecpar = stream.reinterpret(Long.MAX_VALUE)
                        .get(ValueLayout.ADDRESS, s.avstream_codecpar);

                int codecId = codecpar.reinterpret(Long.MAX_VALUE)
                        .get(ValueLayout.JAVA_INT, s.avpar_codec_id);
                int pixFmt = codecpar.reinterpret(Long.MAX_VALUE)
                        .get(ValueLayout.JAVA_INT, s.avpar_format);

                // 5. Set up decoder
                MemorySegment codec = (MemorySegment) avcodec_find_decoder.invokeExact(codecId);
                if (MemorySegment.NULL.equals(codec))
                    throw new IOException("No decoder found for codec ID " + codecId);

                MemorySegment codecCtx = (MemorySegment) avcodec_alloc_context3.invokeExact(codec);
                if (MemorySegment.NULL.equals(codecCtx))
                    throw new IOException("avcodec_alloc_context3 failed");
                ppCodecCtx.set(ValueLayout.ADDRESS, 0, codecCtx);

                rc = (int) avcodec_parameters_to_context.invokeExact(codecCtx, codecpar);
                if (rc < 0) throw new IOException("avcodec_parameters_to_context failed: " + rc);

                rc = (int) avcodec_open2.invokeExact(codecCtx, codec, MemorySegment.NULL);
                if (rc < 0) throw new IOException("avcodec_open2 failed: " + rc);

                // 6. Allocate frame + packet
                MemorySegment frame = (MemorySegment) av_frame_alloc.invokeExact();
                if (MemorySegment.NULL.equals(frame))
                    throw new IOException("av_frame_alloc failed");
                ppFrame.set(ValueLayout.ADDRESS, 0, frame);

                MemorySegment packet = (MemorySegment) av_packet_alloc.invokeExact();
                if (MemorySegment.NULL.equals(packet))
                    throw new IOException("av_packet_alloc failed");
                ppPacket.set(ValueLayout.ADDRESS, 0, packet);

                // 7. Seek if time > 0
                if (!time.isZero()) {
                    // Convert to stream time_base
                    int tbNum = stream.reinterpret(Long.MAX_VALUE)
                            .get(ValueLayout.JAVA_INT, s.avstream_time_base);
                    int tbDen = stream.reinterpret(Long.MAX_VALUE)
                            .get(ValueLayout.JAVA_INT, s.avstream_time_base + 4);
                    long targetTs = (time.toNanos() / 1000) * tbDen / (tbNum * 1_000_000L);
                    int seekRc = (int) av_seek_frame.invokeExact(fmtCtx, streamIdx, targetTs, AVSEEK_FLAG_BACKWARD);
                }

                // 8. Decode loop: read packets, send to decoder, receive frames
                boolean gotFrame = false;
                while (!gotFrame) {
                    rc = (int) av_read_frame.invokeExact(fmtCtx, packet);
                    if (rc < 0) {
                        // EOF or error — flush decoder
                        avcodec_send_packet.invokeExact(codecCtx, MemorySegment.NULL);
                        rc = (int) avcodec_receive_frame.invokeExact(codecCtx, frame);
                        if (rc == 0) gotFrame = true;
                        break;
                    }

                    int pktStreamIdx = packet.reinterpret(Long.MAX_VALUE)
                            .get(ValueLayout.JAVA_INT, s.avpkt_stream_index);
                    if (pktStreamIdx != streamIdx) {
                        av_packet_unref.invokeExact(packet);
                        continue;
                    }

                    rc = (int) avcodec_send_packet.invokeExact(codecCtx, packet);
                    av_packet_unref.invokeExact(packet);
                    if (rc < 0) throw new IOException("avcodec_send_packet failed: " + rc);

                    rc = (int) avcodec_receive_frame.invokeExact(codecCtx, frame);
                    if (rc == 0) {
                        gotFrame = true;
                    } else if (rc == AVERROR_EAGAIN) {
                        continue; // need more packets
                    } else {
                        throw new IOException("avcodec_receive_frame failed: " + rc);
                    }
                }

                if (!gotFrame)
                    throw new IOException("Failed to decode any frame from " + videoFile);

                // 9. Get frame dimensions and pixel format
                int w = frame.reinterpret(Long.MAX_VALUE).get(ValueLayout.JAVA_INT, s.avframe_width);
                int h = frame.reinterpret(Long.MAX_VALUE).get(ValueLayout.JAVA_INT, s.avframe_height);
                int frameFmt = frame.reinterpret(Long.MAX_VALUE).get(ValueLayout.JAVA_INT, s.avframe_format);

                if (w <= 0 || h <= 0)
                    throw new IOException("Invalid frame dimensions: " + w + "x" + h);

                // 10. Set up pixel format conversion (source format → RGBA)
                swsCtx = (MemorySegment) sws_getContext.invokeExact(
                        w, h, frameFmt,
                        w, h, AV_PIX_FMT_RGBA,
                        SWS_BILINEAR,
                        MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL);
                if (MemorySegment.NULL.equals(swsCtx))
                    throw new IOException("sws_getContext failed");

                // 11. Allocate RGBA destination buffer
                int dstBufSize = (int) av_image_get_buffer_size.invokeExact(
                        AV_PIX_FMT_RGBA, w, h, 1);
                if (dstBufSize <= 0)
                    throw new IOException("av_image_get_buffer_size failed: " + dstBufSize);

                MemorySegment dstBuf = arena.allocate(dstBufSize, 16);
                // dst_data[4] and dst_linesize[4]
                MemorySegment dstData = arena.allocate(ValueLayout.ADDRESS, 4);
                MemorySegment dstLinesize = arena.allocate(ValueLayout.JAVA_INT, 4);

                int fillRc = (int) av_image_fill_arrays.invokeExact(dstData, dstLinesize, dstBuf,
                        AV_PIX_FMT_RGBA, w, h, 1);
                if (fillRc < 0) throw new IOException("av_image_fill_arrays failed: " + fillRc);

                // 12. Convert pixels
                // Source data/linesize pointers are at frame offsets
                MemorySegment srcData = frame.reinterpret(Long.MAX_VALUE)
                        .asSlice(s.avframe_data, (long) ValueLayout.ADDRESS.byteSize() * 8);
                MemorySegment srcLinesize = frame.reinterpret(Long.MAX_VALUE)
                        .asSlice(s.avframe_linesize, (long) ValueLayout.JAVA_INT.byteSize() * 8);

                int scaleRc = (int) sws_scale.invokeExact(swsCtx, srcData, srcLinesize, 0, h, dstData, dstLinesize);

                // 13. Repack RGBA → ARGB int[] → BufferedImage
                BufferedImage result = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB_PRE);
                int[] dest = ((DataBufferInt) result.getRaster().getDataBuffer()).getData();

                for (int i = 0, off = 0; i < dest.length; i++, off += 4) {
                    int r = dstBuf.get(ValueLayout.JAVA_BYTE, off) & 0xFF;
                    int g = dstBuf.get(ValueLayout.JAVA_BYTE, off + 1) & 0xFF;
                    int b = dstBuf.get(ValueLayout.JAVA_BYTE, off + 2) & 0xFF;
                    int a = dstBuf.get(ValueLayout.JAVA_BYTE, off + 3) & 0xFF;
                    dest[i] = (a << 24) | (r << 16) | (g << 8) | b;
                }

                return result;

            } finally {
                if (!MemorySegment.NULL.equals(swsCtx))
                    sws_freeContext.invokeExact(swsCtx);
                if (ppPacket.get(ValueLayout.ADDRESS, 0).address() != 0)
                    av_packet_free.invokeExact(ppPacket);
                if (ppFrame.get(ValueLayout.ADDRESS, 0).address() != 0)
                    av_frame_free.invokeExact(ppFrame);
                if (ppCodecCtx.get(ValueLayout.ADDRESS, 0).address() != 0)
                    avcodec_free_context.invokeExact(ppCodecCtx);
                avformat_close_input.invokeExact(ppFmtCtx);
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
        FFmpegStructs s = S;

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment ppFmtCtx = arena.allocate(ValueLayout.ADDRESS);
            ppFmtCtx.set(ValueLayout.ADDRESS, 0, MemorySegment.NULL);
            MemorySegment cpath = arena.allocateFrom(videoFile.toAbsolutePath().toString(),
                    StandardCharsets.UTF_8);

            int rc = (int) avformat_open_input.invokeExact(ppFmtCtx, cpath,
                    MemorySegment.NULL, MemorySegment.NULL);
            if (rc < 0) throw new IOException("avformat_open_input failed: " + rc);

            try {
                MemorySegment fmtCtx = ppFmtCtx.get(ValueLayout.ADDRESS, 0);

                rc = (int) avformat_find_stream_info.invokeExact(fmtCtx, MemorySegment.NULL);
                if (rc < 0) throw new IOException("avformat_find_stream_info failed: " + rc);

                // Duration (in AV_TIME_BASE units → milliseconds)
                long duration = fmtCtx.reinterpret(Long.MAX_VALUE)
                        .get(ValueLayout.JAVA_LONG, s.avfmt_duration);
                Duration dur = (duration > 0)
                        ? Duration.ofMillis(duration * 1000 / AV_TIME_BASE)
                        : Duration.ZERO;

                // Find video stream
                int streamIdx = (int) av_find_best_stream.invokeExact(
                        fmtCtx, AVMEDIA_TYPE_VIDEO, -1, -1, MemorySegment.NULL, 0);
                if (streamIdx < 0) throw new IOException("No video stream found");

                MemorySegment streams = fmtCtx.reinterpret(Long.MAX_VALUE)
                        .get(ValueLayout.ADDRESS, s.avfmt_streams);
                MemorySegment stream = streams.reinterpret(Long.MAX_VALUE)
                        .get(ValueLayout.ADDRESS, (long) streamIdx * ValueLayout.ADDRESS.byteSize());
                MemorySegment codecpar = stream.reinterpret(Long.MAX_VALUE)
                        .get(ValueLayout.ADDRESS, s.avstream_codecpar);

                int w = codecpar.reinterpret(Long.MAX_VALUE)
                        .get(ValueLayout.JAVA_INT, s.avpar_width);
                int h = codecpar.reinterpret(Long.MAX_VALUE)
                        .get(ValueLayout.JAVA_INT, s.avpar_height);
                int codecId = codecpar.reinterpret(Long.MAX_VALUE)
                        .get(ValueLayout.JAVA_INT, s.avpar_codec_id);

                // Frame rate from AVStream.r_frame_rate (AVRational: num/den)
                int fpsNum = stream.reinterpret(Long.MAX_VALUE)
                        .get(ValueLayout.JAVA_INT, s.avstream_r_frame_rate);
                int fpsDen = stream.reinterpret(Long.MAX_VALUE)
                        .get(ValueLayout.JAVA_INT, s.avstream_r_frame_rate + 4);
                double fps = (fpsDen > 0) ? (double) fpsNum / fpsDen : 0.0;

                String codec = CODEC_NAMES.getOrDefault(codecId, null);

                return new VideoInfo(w, h, dur, codec, fps);

            } finally {
                avformat_close_input.invokeExact(ppFmtCtx);
            }
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw new IOException("FFmpeg getInfo failed for: " + videoFile, t);
        }
    }
}
