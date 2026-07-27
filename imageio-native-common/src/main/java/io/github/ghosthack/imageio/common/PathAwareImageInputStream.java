package io.github.ghosthack.imageio.common;

import javax.imageio.stream.FileImageInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

/**
 * A {@link FileImageInputStream} that retains the original file path.
 *
 * <p>Native image and video readers can pass the path directly to decoders
 * instead of copying the encoded file into a Java heap buffer.</p>
 */
public class PathAwareImageInputStream extends FileImageInputStream {

    private final Path path;

    public PathAwareImageInputStream(File file) throws IOException {
        super(file);
        this.path = file.toPath().toAbsolutePath();
    }

    /**
     * Returns the path to the underlying file.
     */
    public Path getPath() {
        return path;
    }
}
