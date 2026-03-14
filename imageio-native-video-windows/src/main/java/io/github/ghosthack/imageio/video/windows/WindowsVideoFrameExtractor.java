package io.github.ghosthack.imageio.video.windows;

import io.github.ghosthack.imageio.video.VideoFrameExtractorProvider;
import io.github.ghosthack.imageio.video.VideoInfo;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;

/**
 * Windows implementation of {@link VideoFrameExtractorProvider}.
 * <p>
 * Uses two native APIs:
 * <ul>
 *   <li><b>IShellItemImageFactory</b> — for quick thumbnail extraction (poster frame)</li>
 *   <li><b>IMFSourceReader</b> — for time-based frame extraction at arbitrary positions</li>
 * </ul>
 * Both are COM-based, using the same vtable dispatch pattern as the still-image WicNative backend.
 */
public class WindowsVideoFrameExtractor implements VideoFrameExtractorProvider {

    private static final boolean IS_WINDOWS = System.getProperty("os.name", "")
            .toLowerCase(Locale.ROOT).startsWith("win");

    @Override
    public boolean isAvailable() {
        return IS_WINDOWS;
    }

    @Override
    public BufferedImage extractFrame(Path videoFile, Duration time) throws IOException {
        if (!IS_WINDOWS) throw new UnsupportedOperationException("Requires Windows");
        // TODO: Implement frame extraction
        //   For t=0 (thumbnail): IShellItemImageFactory
        //     1. SHCreateItemFromParsingName(path, NULL, IID_IShellItemImageFactory, &factory)
        //     2. factory->GetImage({w,h}, SIIGBF_BIGGERSIZEOK, &hBitmap)
        //     3. HBITMAP → BufferedImage via GetDIBits
        //
        //   For arbitrary time: IMFSourceReader
        //     1. MFStartup, MFCreateSourceReaderFromURL
        //     2. SetCurrentMediaType → RGB32
        //     3. SetCurrentPosition → seek to time
        //     4. ReadSample → IMFSample → IMFMediaBuffer → Lock → pixel copy
        throw new UnsupportedOperationException("Video frame extraction not yet implemented");
    }

    @Override
    public VideoInfo getInfo(Path videoFile) throws IOException {
        if (!IS_WINDOWS) throw new UnsupportedOperationException("Requires Windows");
        // TODO: Implement video metadata extraction
        //   1. MFCreateSourceReaderFromURL
        //   2. GetNativeMediaType → MF_MT_FRAME_SIZE for dimensions
        //   3. MF_MT_FRAME_RATE for fps
        //   4. MF_PD_DURATION from presentation descriptor for duration
        //   5. MF_MT_SUBTYPE for codec identification
        throw new UnsupportedOperationException("Video metadata extraction not yet implemented");
    }
}
