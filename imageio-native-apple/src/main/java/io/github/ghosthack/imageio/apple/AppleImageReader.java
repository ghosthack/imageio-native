package io.github.ghosthack.imageio.apple;

import io.github.ghosthack.imageio.common.NativeImageReader;

import javax.imageio.spi.ImageReaderSpi;
import java.awt.image.BufferedImage;
import java.io.IOException;

/**
 * An {@link javax.imageio.ImageReader} that delegates to Apple's CGImageSource
 * via Project Panama.
 * <p>
 * Supports any format that macOS ImageIO can decode (HEIC, AVIF, WEBP, …).
 */
public class AppleImageReader extends NativeImageReader {

    protected AppleImageReader(ImageReaderSpi originatingProvider) {
        super(originatingProvider);
    }

    @Override
    protected int[] nativeGetSize(byte[] data) throws IOException {
        return AppleNative.getSize(data);
    }

    @Override
    protected BufferedImage nativeDecode(byte[] data) throws IOException {
        return AppleNative.decode(data);
    }
}
