package io.github.ghosthack.imageio.video.apple;

import io.github.ghosthack.imageio.common.ImageReadParamSupport;
import io.github.ghosthack.imageio.common.RoutingBackend;
import io.github.ghosthack.imageio.video.VideoFrameExtractorProvider;
import io.github.ghosthack.imageio.video.VideoInfo;
import io.github.ghosthack.panama.media.avfoundation.AVFoundation;
import io.github.ghosthack.panama.media.core.DecodedImage;
import io.github.ghosthack.panama.media.core.PixelFormat;
import io.github.ghosthack.panama.media.corefoundation.CoreFoundation;

import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.IOException;
import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;

/**
 * macOS implementation of {@link VideoFrameExtractorProvider}.
 * <p>
 * Uses AVAssetImageGenerator through the {@code panama-media-avfoundation}
 * binding to extract frames from video files.
 */
public class AppleVideoFrameExtractor implements VideoFrameExtractorProvider {

    @Override
    public String id() {
        return "apple";
    }

    @Override
    public Kind kind() {
        return RoutingBackend.Kind.PLATFORM_NATIVE;
    }

    @Override
    public Set<String> formats() {
        return Set.of("mp4", "mov", "m4v", "3gp");
    }

    private static final boolean AVAILABLE = AVFoundation.isAvailable();

    private static final boolean ON_MACOS = System.getProperty("os.name", "")
            .toLowerCase(java.util.Locale.ROOT).contains("mac");

    private static UnsupportedOperationException unavailable() {
        return new UnsupportedOperationException(ON_MACOS
                ? "AVFoundation bindings failed to load; grant native access to "
                        + "io.github.ghosthack.panama.media.corefoundation and "
                        + "io.github.ghosthack.panama.media.avfoundation"
                : "Requires macOS");
    }

    // ── canDecode probe handles ─────────────────────────────────────────
    // AVFoundation has no playability probe, so canDecode asks the asset
    // directly through the Objective-C runtime calls of the corefoundation
    // binding.

    /** (id, SEL) → id */
    private static final MethodHandle msgSend;
    /** (id, SEL, id) → id */
    private static final MethodHandle msgSend_ptr;
    /** (id, SEL, id, id) → id */
    private static final MethodHandle msgSend_ptr_ptr;
    /** (id, SEL) → BOOL */
    private static final MethodHandle msgSend_ret_bool;

    static {
        if (AVAILABLE) {
            msgSend = CoreFoundation.msgSend(FunctionDescriptor.of(ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            msgSend_ptr = CoreFoundation.msgSend(FunctionDescriptor.of(ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            msgSend_ptr_ptr = CoreFoundation.msgSend(FunctionDescriptor.of(ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            msgSend_ret_bool = CoreFoundation.msgSend(FunctionDescriptor.of(ValueLayout.JAVA_BYTE,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        } else {
            msgSend = null;
            msgSend_ptr = null;
            msgSend_ptr_ptr = null;
            msgSend_ret_bool = null;
        }
    }

    // ── VideoFrameExtractorProvider implementation ──────────────────────

    @Override
    public boolean isAvailable() {
        return AVAILABLE;
    }

    @Override
    public boolean canDecode(Path videoFile) {
        if (!AVAILABLE || videoFile == null) return false;

        try (Arena arena = Arena.ofConfined()) {
            String absPath = videoFile.toAbsolutePath().toString();
            MemorySegment cfPath = CoreFoundation.cfStringCreate(arena, absPath);
            if (MemorySegment.NULL.equals(cfPath)) return false;

            try {
                MemorySegment url =
                        (MemorySegment) msgSend_ptr.invokeExact(
                                CoreFoundation.objcGetClass(arena, "NSURL"),
                                CoreFoundation.selRegisterName(arena, "fileURLWithPath:"),
                                cfPath);
                if (MemorySegment.NULL.equals(url)) return false;

                MemorySegment asset =
                        (MemorySegment) msgSend_ptr_ptr.invokeExact(
                                CoreFoundation.objcGetClass(arena, "AVURLAsset"),
                                CoreFoundation.selRegisterName(arena, "URLAssetWithURL:options:"),
                                url,
                                MemorySegment.NULL);
                if (MemorySegment.NULL.equals(asset)) return false;

                byte playable =
                        (byte) msgSend_ret_bool.invokeExact(
                                asset, CoreFoundation.selRegisterName(arena, "isPlayable"));
                if (playable == 0) return false;

                MemorySegment tracks =
                        (MemorySegment) msgSend_ptr.invokeExact(
                                asset,
                                CoreFoundation.selRegisterName(arena, "tracksWithMediaType:"),
                                CoreFoundation.loadConstPtr("AVMediaTypeVideo"));
                if (MemorySegment.NULL.equals(tracks)) return false;

                MemorySegment firstTrack =
                        (MemorySegment) msgSend.invokeExact(
                                tracks, CoreFoundation.selRegisterName(arena, "firstObject"));
                return !MemorySegment.NULL.equals(firstTrack);
            } finally {
                CoreFoundation.cfRelease(cfPath);
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
        if (!AVAILABLE) throw unavailable();

        // AVAssetImageGenerator caps the frame to an aspect-preserving box;
        // a square box on the longer requested edge never undershoots the
        // render size, and renderToSize makes the result exact.
        int maxEdge = renderSize != null ? Math.max(renderSize.width, renderSize.height) : 0;
        try (Arena arena = Arena.ofConfined()) {
            DecodedImage<PixelFormat> frame = AVFoundation.extractFrame(
                    arena, videoFile.toAbsolutePath().toString(), time.toMillis(), maxEdge);
            return ImageReadParamSupport.renderToSize(toBufferedImage(frame), renderSize);
        } catch (RuntimeException e) {
            throw new IOException("Video frame extraction failed for time " + time, e);
        }
    }

    @Override
    public boolean supportsRenderSizeReduction() {
        return true;
    }

    @Override
    public VideoInfo getInfo(Path videoFile) throws IOException {
        if (!AVAILABLE) throw unavailable();

        try (Arena arena = Arena.ofConfined()) {
            var info = AVFoundation.getVideoInfo(arena, videoFile.toAbsolutePath().toString());
            return new VideoInfo(info.width(), info.height(),
                    Duration.ofMillis(info.durationMillis()), null, info.frameRate());
        } catch (RuntimeException e) {
            throw new IOException("Video info extraction failed", e);
        }
    }

    /**
     * Copies BGRA premultiplied pixels into a {@code TYPE_INT_ARGB_PRE}
     * image (the same int layout when read little-endian).
     */
    private static BufferedImage toBufferedImage(DecodedImage<PixelFormat> frame) {
        int w = frame.width();
        int h = frame.height();
        BufferedImage result = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB_PRE);
        int[] dest = ((DataBufferInt) result.getRaster().getDataBuffer()).getData();
        for (int y = 0; y < h; y++) {
            MemorySegment.copy(frame.pixels(), ValueLayout.JAVA_INT_UNALIGNED,
                    (long) y * frame.stride(), dest, y * w, w);
        }
        return result;
    }
}
