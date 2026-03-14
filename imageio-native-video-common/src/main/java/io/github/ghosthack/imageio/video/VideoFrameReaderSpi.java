package io.github.ghosthack.imageio.video;

import javax.imageio.ImageReader;
import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.stream.ImageInputStream;
import java.io.IOException;
import java.util.Locale;

/**
 * ImageIO SPI that registers video formats (MP4, MOV, WebM, AVI, etc.)
 * so that {@code ImageIO.read(new File("video.mp4"))} returns the poster frame.
 * <p>
 * Relies on {@link PathAwareImageInputStreamSpi} to preserve the file path
 * through the ImageIO pipeline, and delegates actual frame extraction to
 * {@link VideoFrameExtractor}.
 * <p>
 * Registered via {@code META-INF/services/javax.imageio.spi.ImageReaderSpi}.
 */
public class VideoFrameReaderSpi extends ImageReaderSpi {

    private static final String[] FORMAT_NAMES = {
            "mp4", "MP4", "mov", "MOV", "m4v", "M4V",
            "webm", "WEBM", "mkv", "MKV", "avi", "AVI", "wmv", "WMV"
    };

    private static final String[] SUFFIXES = {
            "mp4", "mov", "m4v", "webm", "mkv", "avi", "wmv", "3gp"
    };

    private static final String[] MIME_TYPES = {
            "video/mp4", "video/quicktime", "video/x-m4v",
            "video/webm", "video/x-matroska", "video/avi",
            "video/x-msvideo", "video/x-ms-wmv", "video/3gpp"
    };

    public VideoFrameReaderSpi() {
        super(
                "ghosthack",                    // vendorName
                "1.0",                          // version
                FORMAT_NAMES,
                SUFFIXES,
                MIME_TYPES,
                VideoFrameReader.class.getName(),
                new Class<?>[]{ImageInputStream.class},
                null,                           // writerSpiNames
                false,                          // supportsStandardStreamMetadataFormat
                null, null, null, null,         // stream metadata
                false,                          // supportsStandardImageMetadataFormat
                null, null, null, null          // image metadata
        );
    }

    @Override
    public boolean canDecodeInput(Object source) throws IOException {
        if (!(source instanceof ImageInputStream stream)) return false;
        if (!VideoFrameExtractor.isAvailable()) return false;
        return VideoFormatDetector.isVideoFormat(stream);
    }

    @Override
    public ImageReader createReaderInstance(Object extension) {
        return new VideoFrameReader(this);
    }

    @Override
    public String getDescription(Locale locale) {
        return "Video poster frame reader (delegates to platform-native media APIs)";
    }
}
