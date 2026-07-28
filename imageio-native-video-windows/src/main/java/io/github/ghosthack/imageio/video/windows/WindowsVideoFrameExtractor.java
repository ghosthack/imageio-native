package io.github.ghosthack.imageio.video.windows;

import io.github.ghosthack.imageio.common.RoutingBackend;
import io.github.ghosthack.imageio.video.VideoFrameExtractorProvider;
import io.github.ghosthack.imageio.video.VideoInfo;
import io.github.ghosthack.panama.media.core.DecodedImage;
import io.github.ghosthack.panama.media.core.PixelFormat;
import io.github.ghosthack.panama.media.mediafoundation.MediaFoundation;

import javax.imageio.IIOException;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;

/**
 * Windows video frame extraction backed by panama-media's Media Foundation
 * implementation.
 */
public class WindowsVideoFrameExtractor implements VideoFrameExtractorProvider {

    @Override
    public String id() {
        return "windows";
    }

    @Override
    public Kind kind() {
        return RoutingBackend.Kind.PLATFORM_NATIVE;
    }

    @Override
    public boolean isAvailable() {
        return MediaFoundation.isAvailable();
    }

    @Override
    public boolean canDecode(Path videoFile) {
        if (!isAvailable() || videoFile == null) return false;

        try (MediaFoundation.FrameStream ignored =
                     MediaFoundation.openVideo(
                             videoFile.toAbsolutePath().toString())) {
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public BufferedImage extractFrame(Path videoFile, Duration time) throws IOException {
        Objects.requireNonNull(videoFile, "videoFile");
        Objects.requireNonNull(time, "time");
        if (time.isNegative()) {
            throw new IllegalArgumentException("time must not be negative");
        }
        if (!isAvailable()) {
            throw new UnsupportedOperationException("Requires Windows Media Foundation");
        }

        try (Arena arena = Arena.ofConfined()) {
            String path = videoFile.toAbsolutePath().toString();
            DecodedImage<PixelFormat> frame = MediaFoundation.extractFrame(
                    arena, path, time.toMillis());
            return toBufferedImage(frame);
        } catch (IOException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IIOException(
                    "Media Foundation frame extraction failed for: " + videoFile, e);
        }
    }

    @Override
    public VideoInfo getInfo(Path videoFile) throws IOException {
        Objects.requireNonNull(videoFile, "videoFile");
        if (!isAvailable()) {
            throw new UnsupportedOperationException("Requires Windows Media Foundation");
        }

        try (Arena arena = Arena.ofConfined()) {
            io.github.ghosthack.panama.media.mediafoundation.VideoInfo info =
                    MediaFoundation.getVideoInfo(
                            arena, videoFile.toAbsolutePath().toString());
            return new VideoInfo(
                    info.width(),
                    info.height(),
                    Duration.ofMillis(Math.max(0L, info.durationMillis())),
                    normalizeCodec(info.codec()),
                    info.frameRate());
        } catch (RuntimeException e) {
            throw new IIOException(
                    "Media Foundation video metadata query failed for: " + videoFile, e);
        }
    }

    private static BufferedImage toBufferedImage(
            DecodedImage<PixelFormat> image)
            throws IIOException {
        if (image.format() != PixelFormat.BGRA) {
            throw new IIOException("Expected BGRA video pixels, got " + image.format());
        }
        if ((image.stride() & 3) != 0 || image.stride() < image.width() * 4) {
            throw new IIOException("Invalid BGRA video stride: " + image.stride());
        }

        BufferedImage result = new BufferedImage(
                image.width(), image.height(),
                BufferedImage.TYPE_INT_ARGB_PRE);
        int[] dest = ((DataBufferInt) result.getRaster().getDataBuffer()).getData();
        MemorySegment pixels = image.pixels();

        if (image.stride() == image.width() * 4) {
            MemorySegment.copy(pixels, ValueLayout.JAVA_INT, 0, dest, 0, dest.length);
        } else {
            for (int y = 0; y < image.height(); y++) {
                MemorySegment row = pixels.asSlice((long) y * image.stride());
                MemorySegment.copy(
                        row, ValueLayout.JAVA_INT, 0,
                        dest, y * image.width(), image.width());
            }
        }
        return result;
    }

    private static String normalizeCodec(String codec) {
        if (codec == null) return null;
        return switch (codec.toUpperCase(Locale.ROOT)) {
            case "H.264" -> "h264";
            case "HEVC" -> "hevc";
            case "VP8" -> "vp8";
            case "VP9" -> "vp9";
            case "AV1" -> "av1";
            case "MPEG-4" -> "mpeg4";
            case "MPEG-2" -> "mpeg2";
            case "MPEG-1" -> "mpeg1";
            case "WMV" -> "wmv";
            case "MOTION JPEG" -> "mjpeg";
            default -> codec.toLowerCase(Locale.ROOT);
        };
    }
}
