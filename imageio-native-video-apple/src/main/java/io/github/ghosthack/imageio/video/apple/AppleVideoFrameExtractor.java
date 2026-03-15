package io.github.ghosthack.imageio.video.apple;

import io.github.ghosthack.imageio.apple.AppleCoreGraphicsHelper;
import io.github.ghosthack.imageio.video.VideoFrameExtractorProvider;
import io.github.ghosthack.imageio.video.VideoInfo;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;

/**
 * macOS implementation of {@link VideoFrameExtractorProvider}.
 * <p>
 * Uses AVAssetImageGenerator (via Objective-C runtime bridge from Panama FFM)
 * to extract frames from video files. The returned CGImage is converted to
 * BufferedImage using {@link AppleCoreGraphicsHelper}.
 */
public class AppleVideoFrameExtractor implements VideoFrameExtractorProvider {

    private static final boolean IS_MACOS = System.getProperty("os.name", "")
            .toLowerCase(Locale.ROOT).contains("mac");

    // ── CMTime struct layout (24 bytes) ─────────────────────────────────
    //   int64_t  value;     // 8 bytes
    //   int32_t  timescale; // 4 bytes
    //   uint32_t flags;     // 4 bytes
    //   int64_t  epoch;     // 8 bytes

    private static final StructLayout CMTIME = MemoryLayout.structLayout(
            ValueLayout.JAVA_LONG.withName("value"),
            ValueLayout.JAVA_INT.withName("timescale"),
            ValueLayout.JAVA_INT.withName("flags"),
            ValueLayout.JAVA_LONG.withName("epoch")
    );

    // ── CGSize struct layout (16 bytes) ─────────────────────────────────

    private static final StructLayout CGSIZE = MemoryLayout.structLayout(
            ValueLayout.JAVA_DOUBLE.withName("width"),
            ValueLayout.JAVA_DOUBLE.withName("height")
    );

    // ── Native handles (lazy-initialised on first use) ──────────────────

    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup LOOKUP;

    // ObjC runtime
    private static final MethodHandle objc_getClass;
    private static final MethodHandle sel_registerName;
    private static final MemorySegment objc_msgSend_addr;

    // CoreFoundation
    private static final MethodHandle CFStringCreateWithCString;
    private static final MethodHandle CFRelease;

    // CoreMedia
    private static final MethodHandle CMTimeMake;

    // Pre-built objc_msgSend variants for different signatures
    /** (id, SEL) → id */
    private static final MethodHandle msgSend;
    /** (id, SEL, id) → id */
    private static final MethodHandle msgSend_ptr;
    /** (id, SEL, id, id) → id */
    private static final MethodHandle msgSend_ptr_ptr;
    /** (id, SEL, byte) → void */
    private static final MethodHandle msgSend_bool;
    /** (id, SEL, CMTime) → void — set tolerance */
    private static final MethodHandle msgSend_cmtime_void;
    /** (id, SEL, CMTime, ptr, ptr) → id — copyCGImageAtTime:actualTime:error: */
    private static final MethodHandle msgSend_cmtime_ptr_ptr;
    /** (id, SEL) → CMTime — duration property */
    private static final MethodHandle msgSend_ret_cmtime;
    /** (id, SEL) → CGSize — naturalSize property */
    private static final MethodHandle msgSend_ret_cgsize;
    /** (id, SEL) → float — nominalFrameRate property */
    private static final MethodHandle msgSend_ret_float;
    /** (id, SEL, id) → id — for tracksWithMediaType: */
    private static final MethodHandle msgSend_ptr_ret_ptr;

    static {
        if (IS_MACOS) {
            // Load frameworks
            System.load("/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation");
            System.load("/System/Library/Frameworks/CoreGraphics.framework/CoreGraphics");
            System.load("/System/Library/Frameworks/CoreMedia.framework/CoreMedia");
            System.load("/System/Library/Frameworks/AVFoundation.framework/AVFoundation");
            // libobjc is already loaded by the JVM on macOS, but load explicitly
            System.load("/usr/lib/libobjc.A.dylib");

            LOOKUP = SymbolLookup.loaderLookup();

            // ObjC runtime
            objc_getClass = downcall("objc_getClass",
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            sel_registerName = downcall("sel_registerName",
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));

            objc_msgSend_addr = LOOKUP.find("objc_msgSend")
                    .orElseThrow(() -> new UnsatisfiedLinkError("objc_msgSend not found"));

            // CoreFoundation
            CFStringCreateWithCString = downcall("CFStringCreateWithCString",
                    FunctionDescriptor.of(ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
            CFRelease = downcall("CFRelease",
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));

            // CoreMedia
            CMTimeMake = downcall("CMTimeMake",
                    FunctionDescriptor.of(CMTIME, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT));

            // ── objc_msgSend variants ───────────────────────────────────
            // (id, SEL) → id
            msgSend = LINKER.downcallHandle(objc_msgSend_addr,
                    FunctionDescriptor.of(ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS));

            // (id, SEL, id) → id
            msgSend_ptr = LINKER.downcallHandle(objc_msgSend_addr,
                    FunctionDescriptor.of(ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));

            // (id, SEL, id, id) → id
            msgSend_ptr_ptr = LINKER.downcallHandle(objc_msgSend_addr,
                    FunctionDescriptor.of(ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS));

            // (id, SEL, byte) → void (for BOOL args like setAppliesPreferredTrackTransform:)
            msgSend_bool = LINKER.downcallHandle(objc_msgSend_addr,
                    FunctionDescriptor.ofVoid(
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_BYTE));

            // (id, SEL, CMTime) → void (setRequestedTimeToleranceBefore:/After:)
            msgSend_cmtime_void = LINKER.downcallHandle(objc_msgSend_addr,
                    FunctionDescriptor.ofVoid(
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS, CMTIME));

            // (id, SEL, CMTime, ptr, ptr) → id (copyCGImageAtTime:actualTime:error:)
            msgSend_cmtime_ptr_ptr = LINKER.downcallHandle(objc_msgSend_addr,
                    FunctionDescriptor.of(ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            CMTIME, ValueLayout.ADDRESS, ValueLayout.ADDRESS));

            // (id, SEL) → CMTime (duration property)
            msgSend_ret_cmtime = LINKER.downcallHandle(objc_msgSend_addr,
                    FunctionDescriptor.of(CMTIME,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS));

            // (id, SEL) → CGSize (naturalSize property)
            msgSend_ret_cgsize = LINKER.downcallHandle(objc_msgSend_addr,
                    FunctionDescriptor.of(CGSIZE,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS));

            // (id, SEL) → float (nominalFrameRate property)
            msgSend_ret_float = LINKER.downcallHandle(objc_msgSend_addr,
                    FunctionDescriptor.of(ValueLayout.JAVA_FLOAT,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS));

            // (id, SEL, id) → id (same as msgSend_ptr, alias for clarity)
            msgSend_ptr_ret_ptr = msgSend_ptr;

        } else {
            LOOKUP = null;
            objc_getClass = null;
            sel_registerName = null;
            objc_msgSend_addr = null;
            CFStringCreateWithCString = null;
            CFRelease = null;
            CMTimeMake = null;
            msgSend = null;
            msgSend_ptr = null;
            msgSend_ptr_ptr = null;
            msgSend_bool = null;
            msgSend_cmtime_void = null;
            msgSend_cmtime_ptr_ptr = null;
            msgSend_ret_cmtime = null;
            msgSend_ret_cgsize = null;
            msgSend_ret_float = null;
            msgSend_ptr_ret_ptr = null;
        }
    }

    private static MethodHandle downcall(String name, FunctionDescriptor fd) {
        MemorySegment addr = LOOKUP.find(name)
                .orElseThrow(() -> new UnsatisfiedLinkError("Symbol not found: " + name));
        return LINKER.downcallHandle(addr, fd);
    }

    // ── ObjC helpers ────────────────────────────────────────────────────

    private static MemorySegment cls(Arena arena, String name) {
        try {
            return (MemorySegment) objc_getClass.invokeExact(
                    arena.allocateFrom(name));
        } catch (Throwable t) {
            throw new RuntimeException("objc_getClass failed for " + name, t);
        }
    }

    private static MemorySegment sel(Arena arena, String name) {
        try {
            return (MemorySegment) sel_registerName.invokeExact(
                    arena.allocateFrom(name));
        } catch (Throwable t) {
            throw new RuntimeException("sel_registerName failed for " + name, t);
        }
    }

    private static void release(MemorySegment ref) {
        if (ref != null && !MemorySegment.NULL.equals(ref)) {
            try {
                CFRelease.invokeExact(ref);
            } catch (Throwable ignored) {}
        }
    }

    /** kCFStringEncodingUTF8 = 0x08000100 */
    private static final int kCFStringEncodingUTF8 = 0x08000100;

    // ── VideoFrameExtractorProvider implementation ──────────────────────

    @Override
    public boolean isAvailable() {
        return IS_MACOS;
    }

    @Override
    public BufferedImage extractFrame(Path videoFile, Duration time) throws IOException {
        if (!IS_MACOS) throw new UnsupportedOperationException("Requires macOS");

        try (Arena arena = Arena.ofConfined()) {
            String absPath = videoFile.toAbsolutePath().toString();

            // 1. Create CFString from file path
            MemorySegment cfPath = (MemorySegment) CFStringCreateWithCString.invokeExact(
                    MemorySegment.NULL,
                    arena.allocateFrom(absPath),
                    kCFStringEncodingUTF8);
            if (MemorySegment.NULL.equals(cfPath)) {
                throw new IOException("CFStringCreateWithCString failed for: " + absPath);
            }

            MemorySegment url = MemorySegment.NULL;
            MemorySegment asset = MemorySegment.NULL;
            MemorySegment generator = MemorySegment.NULL;
            MemorySegment cgImage = MemorySegment.NULL;
            try {
                // 2. NSURL fileURLWithPath:
                MemorySegment nsurlClass = cls(arena, "NSURL");
                MemorySegment selFileURL = sel(arena, "fileURLWithPath:");
                url = (MemorySegment) msgSend_ptr.invokeExact(nsurlClass, selFileURL, cfPath);
                if (MemorySegment.NULL.equals(url)) {
                    throw new IOException("NSURL fileURLWithPath: returned nil");
                }

                // 3. AVURLAsset URLAssetWithURL:options:
                MemorySegment avAssetClass = cls(arena, "AVURLAsset");
                MemorySegment selURLAsset = sel(arena, "URLAssetWithURL:options:");
                asset = (MemorySegment) msgSend_ptr_ptr.invokeExact(
                        avAssetClass, selURLAsset, url, MemorySegment.NULL);
                if (MemorySegment.NULL.equals(asset)) {
                    throw new IOException("AVURLAsset creation failed for: " + absPath);
                }

                // 4. AVAssetImageGenerator assetImageGeneratorWithAsset:
                MemorySegment genClass = cls(arena, "AVAssetImageGenerator");
                MemorySegment selGenWithAsset = sel(arena, "assetImageGeneratorWithAsset:");
                generator = (MemorySegment) msgSend_ptr.invokeExact(
                        genClass, selGenWithAsset, asset);
                if (MemorySegment.NULL.equals(generator)) {
                    throw new IOException("AVAssetImageGenerator creation failed");
                }

                // 5. setAppliesPreferredTrackTransform: YES
                MemorySegment selSetTransform = sel(arena, "setAppliesPreferredTrackTransform:");
                msgSend_bool.invokeExact(generator, selSetTransform, (byte) 1);

                // 6. Set tolerances for frame seek.
                //    Using exact tolerance (zero) can fail for videos that don't have
                //    a decodable frame at exactly the requested time (common at t=0
                //    with B-frame / long-GOP content).
                //    Start with a 1-second tolerance window so AVFoundation can pick
                //    the nearest keyframe.
                MemorySegment selSetBefore = sel(arena, "setRequestedTimeToleranceBefore:");
                MemorySegment selSetAfter = sel(arena, "setRequestedTimeToleranceAfter:");

                MemorySegment toleranceBefore = (MemorySegment) CMTimeMake.invokeExact(
                        (SegmentAllocator) arena, 1L, 1); // 1 second
                MemorySegment toleranceAfter = (MemorySegment) CMTimeMake.invokeExact(
                        (SegmentAllocator) arena, 1L, 1);  // 1 second
                msgSend_cmtime_void.invokeExact(generator, selSetBefore, toleranceBefore);
                msgSend_cmtime_void.invokeExact(generator, selSetAfter, toleranceAfter);

                // 7. Create CMTime from Duration
                long millis = time.toMillis();
                MemorySegment cmTime = (MemorySegment) CMTimeMake.invokeExact(
                        (SegmentAllocator) arena, millis, 1000);

                // 8. copyCGImageAtTime:actualTime:error:
                MemorySegment selCopy = sel(arena, "copyCGImageAtTime:actualTime:error:");
                MemorySegment errorPtr = arena.allocate(ValueLayout.ADDRESS);
                errorPtr.set(ValueLayout.ADDRESS, 0, MemorySegment.NULL);

                cgImage = (MemorySegment) msgSend_cmtime_ptr_ptr.invokeExact(
                        generator, selCopy,
                        cmTime,
                        MemorySegment.NULL,  // actualTime (don't need it)
                        errorPtr);

                // 8b. If NULL, retry with default (unbounded) tolerances.
                //     This handles edge cases where no keyframe exists within
                //     the initial 1-second window (e.g. very long GOPs).
                if (MemorySegment.NULL.equals(cgImage)) {
                    MemorySegment largeTolerance = (MemorySegment) CMTimeMake.invokeExact(
                            (SegmentAllocator) arena, 3600L, 1); // 1 hour
                    msgSend_cmtime_void.invokeExact(generator, selSetBefore, largeTolerance);
                    msgSend_cmtime_void.invokeExact(generator, selSetAfter, largeTolerance);

                    errorPtr.set(ValueLayout.ADDRESS, 0, MemorySegment.NULL);
                    cgImage = (MemorySegment) msgSend_cmtime_ptr_ptr.invokeExact(
                            generator, selCopy,
                            cmTime,
                            MemorySegment.NULL,
                            errorPtr);
                }

                if (MemorySegment.NULL.equals(cgImage)) {
                    throw new IOException("copyCGImageAtTime returned NULL for time " + time);
                }

                // 9. Convert CGImage to BufferedImage
                return AppleCoreGraphicsHelper.cgImageToBufferedImage(cgImage, arena);

            } finally {
                // cgImage is a CF object returned by "copy" — we own it
                release(cgImage);
                // url, asset, generator are autoreleased ObjC objects — don't CFRelease them
                release(cfPath);
            }
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw new IOException("Video frame extraction failed", t);
        }
    }

    @Override
    public VideoInfo getInfo(Path videoFile) throws IOException {
        if (!IS_MACOS) throw new UnsupportedOperationException("Requires macOS");

        try (Arena arena = Arena.ofConfined()) {
            String absPath = videoFile.toAbsolutePath().toString();

            // 1. Create CFString from file path
            MemorySegment cfPath = (MemorySegment) CFStringCreateWithCString.invokeExact(
                    MemorySegment.NULL,
                    arena.allocateFrom(absPath),
                    kCFStringEncodingUTF8);
            if (MemorySegment.NULL.equals(cfPath)) {
                throw new IOException("CFStringCreateWithCString failed for: " + absPath);
            }

            try {
                // 2. NSURL fileURLWithPath:
                MemorySegment nsurlClass = cls(arena, "NSURL");
                MemorySegment selFileURL = sel(arena, "fileURLWithPath:");
                MemorySegment url = (MemorySegment) msgSend_ptr.invokeExact(
                        nsurlClass, selFileURL, cfPath);
                if (MemorySegment.NULL.equals(url)) {
                    throw new IOException("NSURL fileURLWithPath: returned nil");
                }

                // 3. AVURLAsset URLAssetWithURL:options:
                MemorySegment avAssetClass = cls(arena, "AVURLAsset");
                MemorySegment selURLAsset = sel(arena, "URLAssetWithURL:options:");
                MemorySegment asset = (MemorySegment) msgSend_ptr_ptr.invokeExact(
                        avAssetClass, selURLAsset, url, MemorySegment.NULL);
                if (MemorySegment.NULL.equals(asset)) {
                    throw new IOException("AVURLAsset creation failed for: " + absPath);
                }

                // 4. Get duration: [asset duration] → CMTime struct
                MemorySegment selDuration = sel(arena, "duration");
                MemorySegment durationTime = (MemorySegment) msgSend_ret_cmtime.invokeExact(
                        (SegmentAllocator) arena, asset, selDuration);
                long durationValue = durationTime.get(ValueLayout.JAVA_LONG, 0);
                int durationTimescale = durationTime.get(ValueLayout.JAVA_INT, 8);
                Duration duration;
                if (durationTimescale <= 0) {
                    duration = Duration.ZERO;
                } else {
                    // Convert CMTime to milliseconds without overflowing:
                    // split into whole-seconds and remainder to keep intermediate
                    // values within long range.
                    long seconds = durationValue / durationTimescale;
                    long remainder = durationValue % durationTimescale;
                    long totalMillis = seconds * 1000L + (remainder * 1000L) / durationTimescale;
                    duration = Duration.ofMillis(totalMillis);
                }

                // 5. Get video tracks: [asset tracksWithMediaType:AVMediaTypeVideo]
                //    AVMediaTypeVideo is an NSString constant; we need to look it up
                MemorySegment avMediaTypeVideo = LOOKUP.find("AVMediaTypeVideo")
                        .orElseThrow(() -> new UnsatisfiedLinkError("AVMediaTypeVideo not found"));
                MemorySegment mediaTypeStr = avMediaTypeVideo.reinterpret(ValueLayout.ADDRESS.byteSize())
                        .get(ValueLayout.ADDRESS, 0);

                MemorySegment selTracks = sel(arena, "tracksWithMediaType:");
                MemorySegment tracks = (MemorySegment) msgSend_ptr_ret_ptr.invokeExact(
                        asset, selTracks, mediaTypeStr);

                int width = 0;
                int height = 0;
                double frameRate = 0.0;

                if (!MemorySegment.NULL.equals(tracks)) {
                    // 6. Get first track: [tracks firstObject]
                    MemorySegment selFirstObject = sel(arena, "firstObject");
                    MemorySegment track = (MemorySegment) msgSend.invokeExact(tracks, selFirstObject);

                    if (!MemorySegment.NULL.equals(track)) {
                        // 7. Get naturalSize: [track naturalSize] → CGSize struct
                        MemorySegment selNaturalSize = sel(arena, "naturalSize");
                        MemorySegment size = (MemorySegment) msgSend_ret_cgsize.invokeExact(
                                (SegmentAllocator) arena, track, selNaturalSize);
                        double w = size.get(ValueLayout.JAVA_DOUBLE, 0);
                        double h = size.get(ValueLayout.JAVA_DOUBLE, 8);
                        width = (int) Math.round(w);
                        height = (int) Math.round(h);

                        // 8. Get nominalFrameRate: [track nominalFrameRate] → float
                        MemorySegment selFrameRate = sel(arena, "nominalFrameRate");
                        frameRate = (float) msgSend_ret_float.invokeExact(track, selFrameRate);
                    }
                }

                return new VideoInfo(width, height, duration, null, frameRate);

            } finally {
                release(cfPath);
            }
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw new IOException("Video info extraction failed", t);
        }
    }
}
