package io.github.ghosthack.imageio.magick;

import io.github.ghosthack.imageio.common.NativeImageReader;

import javax.imageio.spi.ImageReaderSpi;
import java.awt.image.BufferedImage;
import java.io.IOException;

/**
 * {@link javax.imageio.ImageReader} backed by ImageMagick 7 (MagickWand API).
 * <p>
 * Supports 200+ formats depending on the ImageMagick build configuration.
 * Delegates to {@link MagickNative} for all native operations.
 */
class MagickImageReader extends NativeImageReader {

    MagickImageReader(ImageReaderSpi originatingProvider) {
        super(originatingProvider);
    }

    @Override
    protected int[] nativeGetSize(byte[] data) throws IOException {
        return MagickNative.getSize(data);
    }

    @Override
    protected BufferedImage nativeDecode(byte[] data) throws IOException {
        return MagickNative.decode(data);
    }

    @Override
    protected int[] nativeGetSizeFromPath(String path) throws IOException {
        return MagickNative.getSizeFromPath(path);
    }

    @Override
    protected BufferedImage nativeDecodeFromPath(String path) throws IOException {
        return MagickNative.decodeFromPath(path);
    }
}
