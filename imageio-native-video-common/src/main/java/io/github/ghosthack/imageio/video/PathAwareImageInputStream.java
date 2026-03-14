package io.github.ghosthack.imageio.video;

import javax.imageio.stream.FileImageInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

/**
 * A {@link FileImageInputStream} that retains the original file path.
 * <p>
 * This enables video-aware {@link javax.imageio.spi.ImageReaderSpi} implementations
 * to extract the file path and pass it to native video APIs (AVAssetImageGenerator,
 * IMFSourceReader) that require file-path input rather than byte streams.
 * <p>
 * Registered via {@link PathAwareImageInputStreamSpi} which replaces the JDK's
 * default {@link FileImageInputStream} creation for {@link File} inputs.
 * Since this class extends {@link FileImageInputStream}, all existing ImageIO
 * readers see identical byte-stream behaviour.
 */
public class PathAwareImageInputStream extends FileImageInputStream {

    private final Path path;

    public PathAwareImageInputStream(File file) throws IOException {
        super(file);
        this.path = file.toPath();
    }

    /**
     * Returns the path to the underlying file.
     */
    public Path getPath() {
        return path;
    }
}
