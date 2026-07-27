package io.github.ghosthack.imageio.video;

/**
 * Shared video format list for video {@link javax.imageio.spi.ImageReaderSpi}
 * implementations.
 * <p>
 * Java has no built-in video readers, so every declared video format is a
 * routing candidate when a capable backend is installed.
 */
public final class VideoFormatRegistry {

    private VideoFormatRegistry() {}

    static final String[] FORMAT_NAMES = {
            "mp4", "MP4", "mov", "MOV", "m4v", "M4V",
            "webm", "WEBM", "mkv", "MKV", "avi", "AVI",
            "wmv", "WMV", "3gp", "3GP"
    };

    static final String[] SUFFIXES = {
            "mp4", "mov", "m4v", "webm", "mkv", "avi", "wmv", "3gp"
    };

    static final String[] MIME_TYPES = {
            "video/mp4", "video/quicktime", "video/x-m4v",
            "video/webm", "video/x-matroska", "video/avi",
            "video/x-msvideo", "video/x-ms-wmv", "video/3gpp"
    };
}
