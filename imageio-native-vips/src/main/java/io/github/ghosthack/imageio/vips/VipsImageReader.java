package io.github.ghosthack.imageio.vips;

import io.github.ghosthack.imageio.common.NativeImageReader;

import javax.imageio.spi.ImageReaderSpi;
import java.awt.image.BufferedImage;
import java.io.IOException;

/**
 * {@link javax.imageio.ImageReader} backed by libvips.
 * <p>
 * Supports all formats that the installed libvips can decode (HEIC, AVIF,
 * WebP, JPEG 2000, PDF, SVG, EXR, TIFF, and many more depending on build
 * options).  Delegates to {@link VipsNative} for all native operations.
 */
class VipsImageReader extends NativeImageReader {

    VipsImageReader(ImageReaderSpi originatingProvider) {
        super(originatingProvider);
    }

    @Override
    protected int[] nativeGetSize(byte[] data) throws IOException {
        return VipsNative.getSize(data);
    }

    @Override
    protected BufferedImage nativeDecode(byte[] data) throws IOException {
        return VipsNative.decode(data);
    }

    @Override
    protected int[] nativeGetSizeFromPath(String path) throws IOException {
        return VipsNative.getSizeFromPath(path);
    }

    @Override
    protected BufferedImage nativeDecodeFromPath(String path) throws IOException {
        return VipsNative.decodeFromPath(path);
    }
}
