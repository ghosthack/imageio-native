package io.github.ghosthack.imageio.windows;

import io.github.ghosthack.imageio.common.NativeImageReader;

import javax.imageio.spi.ImageReaderSpi;
import java.awt.image.BufferedImage;
import java.io.IOException;

/**
 * An {@link javax.imageio.ImageReader} that delegates to the Windows Imaging
 * Component (WIC) via Project Panama.
 * <p>
 * Supports any format that WIC can decode (HEIC, AVIF, WEBP, JPEG-XR, DDS,
 * camera RAW, …).
 */
public class WicImageReader extends NativeImageReader {

    protected WicImageReader(ImageReaderSpi originatingProvider) {
        super(originatingProvider);
    }

    @Override
    protected int[] nativeGetSize(byte[] data) throws IOException {
        return WicNative.getSize(data);
    }

    @Override
    protected BufferedImage nativeDecode(byte[] data) throws IOException {
        return WicNative.decode(data);
    }

    @Override
    protected int[] nativeGetSizeFromPath(String path) throws IOException {
        return WicNative.getSizeFromPath(path);
    }

    @Override
    protected BufferedImage nativeDecodeFromPath(String path) throws IOException {
        return WicNative.decodeFromPath(path);
    }
}
