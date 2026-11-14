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
    /** (id, SEL) → CGAffineTransform */
    private static final MethodHandle msgSend_ret_transform;

    /** CGAffineTransform: six CGFloat members {@code a, b, c, d, tx, ty}. */
    private static final StructLayout CGAFFINETRANSFORM = MemoryLayout.structLayout(
            ValueLayout.JAVA_DOUBLE.withName("a"),
            ValueLayout.JAVA_DOUBLE.withName("b"),
            ValueLayout.JAVA_DOUBLE.withName("c"),
            ValueLayout.JAVA_DOUBLE.withName("d"),
            ValueLayout.JAVA_DOUBLE.withName("tx"),
            ValueLayout.JAVA_DOUBLE.withName("ty"));

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
            msgSend_ret_transform = CoreFoundation.msgSend(FunctionDescriptor.of(CGAFFINETRANSFORM,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        } else {
            msgSend = null;
            msgSend_ptr = null;
            msgSend_ptr_ptr = null;
            msgSend_ret_bool = null;
            msgSend_ret_transform = null;
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
        MemorySegment pool = CoreFoundation.autoreleasePoolPush();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment track = playableVideoTrack(arena, videoFile);
            return !MemorySegment.NULL.equals(track);
        } catch (Throwable ignored) {
            return false;
        } finally {
            CoreFoundation.autoreleasePoolPop(pool);
        }
    }

    /**
     * Returns the first video track of a playable asset, or {@code NULL}.
     * <p>
     * The asset, track array, and track are autoreleased Objective-C objects:
     * callers must hold an autorelease pool and must not use the track after
     * draining it.
     */
    private static MemorySegment playableVideoTrack(Arena arena, Path videoFile)
            throws Throwable {
        String absPath = videoFile.toAbsolutePath().toString();
        MemorySegment cfPath = CoreFoundation.cfStringCreate(arena, absPath);
        if (MemorySegment.NULL.equals(cfPath)) return MemorySegment.NULL;
        try {
            MemorySegment url =
                    (MemorySegment) msgSend_ptr.invokeExact(
                            CoreFoundation.objcGetClass(arena, "NSURL"),
                            CoreFoundation.selRegisterName(arena, "fileURLWithPath:"),
                            cfPath);
            if (MemorySegment.NULL.equals(url)) return MemorySegment.NULL;
            MemorySegment asset =
                    (MemorySegment) msgSend_ptr_ptr.invokeExact(
                            CoreFoundation.objcGetClass(arena, "AVURLAsset"),
                            CoreFoundation.selRegisterName(arena, "URLAssetWithURL:options:"),
                            url,
                            MemorySegment.NULL);
            if (MemorySegment.NULL.equals(asset)) return MemorySegment.NULL;
            byte playable =
                    (byte) msgSend_ret_bool.invokeExact(
                            asset, CoreFoundation.selRegisterName(arena, "isPlayable"));
            if (playable == 0) return MemorySegment.NULL;
            MemorySegment tracks =
                    (MemorySegment) msgSend_ptr.invokeExact(
                            asset,
                            CoreFoundation.selRegisterName(arena, "tracksWithMediaType:"),
                            CoreFoundation.loadConstPtr("AVMediaTypeVideo"));
            if (MemorySegment.NULL.equals(tracks)) return MemorySegment.NULL;
            return (MemorySegment) msgSend.invokeExact(
                    tracks, CoreFoundation.selRegisterName(arena, "firstObject"));
        } finally {
            CoreFoundation.cfRelease(cfPath);
        }
    }

    /**
     * Returns whether the track's preferred transform turns the picture by an
     * odd number of quarter turns, so that the displayed frame has the stored
     * width and height swapped. The frame generator applies that transform,
     * while the track's natural size does not include it.
     */
    private static boolean displayTransposed(Path videoFile) {
        MemorySegment pool = CoreFoundation.autoreleasePoolPush();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment track = playableVideoTrack(arena, videoFile);
            if (MemorySegment.NULL.equals(track)) return false;
            MemorySegment transform =
                    (MemorySegment) msgSend_ret_transform.invokeExact(
                            (SegmentAllocator) arena, track,
                            CoreFoundation.selRegisterName(arena, "preferredTransform"));
            double a = transform.get(ValueLayout.JAVA_DOUBLE, 0);
            double b = transform.get(ValueLayout.JAVA_DOUBLE, 8);
            if (a == 0 && b == 0) return false;
            long quarterTurns = Math.round(Math.toDegrees(Math.atan2(b, a)) / 90.0);
            return (quarterTurns & 1) != 0;
        } catch (Throwable ignored) {
            return false;
        } finally {
            CoreFoundation.autoreleasePoolPop(pool);
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
        // The binding creates autoreleased AVFoundation objects. Java threads
        // have no autorelease pool, so drain one here; the decoded pixels
        // are copied into the arena and do not depend on it.
        MemorySegment pool = CoreFoundation.autoreleasePoolPush();
        try (Arena arena = Arena.ofConfined()) {
            DecodedImage<PixelFormat> frame = AVFoundation.extractFrame(
                    arena, videoFile.toAbsolutePath().toString(), time.toMillis(), maxEdge);
            return ImageReadParamSupport.renderToSize(toBufferedImage(frame), renderSize);
        } catch (RuntimeException e) {
            throw new IOException("Video frame extraction failed for time " + time, e);
        } finally {
            CoreFoundation.autoreleasePoolPop(pool);
        }
    }

    @Override
    public boolean supportsRenderSizeReduction() {
        return true;
    }

    @Override
    public VideoInfo getInfo(Path videoFile) throws IOException {
        if (!AVAILABLE) throw unavailable();

        MemorySegment pool = CoreFoundation.autoreleasePoolPush();
        try (Arena arena = Arena.ofConfined()) {
            var info = AVFoundation.getVideoInfo(arena, videoFile.toAbsolutePath().toString());
            // Report the displayed size, which is what extractFrame returns.
            boolean transposed = displayTransposed(videoFile);
            return new VideoInfo(
                    transposed ? info.height() : info.width(),
                    transposed ? info.width() : info.height(),
                    Duration.ofMillis(info.durationMillis()), null, info.frameRate());
        } catch (RuntimeException e) {
            throw new IOException("Video info extraction failed", e);
        } finally {
            CoreFoundation.autoreleasePoolPop(pool);
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
