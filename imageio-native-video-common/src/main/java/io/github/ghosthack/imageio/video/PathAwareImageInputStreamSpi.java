package io.github.ghosthack.imageio.video;

import javax.imageio.spi.ImageInputStreamSpi;
import javax.imageio.stream.ImageInputStream;
import java.io.File;
import java.io.IOException;
import java.util.Locale;

/**
 * Replaces the JDK's default {@code File → ImageInputStream} SPI so that
 * {@link javax.imageio.ImageIO#read(File)} produces a {@link PathAwareImageInputStream}
 * instead of a plain {@link javax.imageio.stream.FileImageInputStream}.
 * <p>
 * This is transparent to all existing readers (since {@link PathAwareImageInputStream}
 * extends {@link javax.imageio.stream.FileImageInputStream}) but allows video-aware
 * readers to extract the file path via {@link PathAwareImageInputStream#getPath()}.
 * <p>
 * Registered via {@code META-INF/services/javax.imageio.spi.ImageInputStreamSpi}.
 */
public class PathAwareImageInputStreamSpi extends ImageInputStreamSpi {

    public PathAwareImageInputStreamSpi() {
        super("ghosthack", "1.0", File.class);
    }

    @Override
    public String getDescription(Locale locale) {
        return "Path-aware FileImageInputStream for video frame extraction";
    }

    @Override
    public ImageInputStream createInputStreamInstance(Object input, boolean useCache,
                                                      File cacheDir) throws IOException {
        if (input instanceof File file) {
            return new PathAwareImageInputStream(file);
        }
        throw new IllegalArgumentException("Expected File input, got: " +
                (input == null ? "null" : input.getClass().getName()));
    }
}
