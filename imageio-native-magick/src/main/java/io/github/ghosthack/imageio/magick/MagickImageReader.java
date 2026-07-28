package io.github.ghosthack.imageio.magick;

import io.github.ghosthack.imageio.common.NativeImageReader;
import io.github.ghosthack.imageio.common.NativeDecodeRequest;
import io.github.ghosthack.imageio.common.NativeDecodeResult;

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
    protected boolean supportsNativeSpatialSelection() {
        return true;
    }

    @Override
    protected boolean supportsNativeSourceRenderSize() {
        return true;
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
    protected NativeDecodeResult nativeDecode(
            byte[] data, NativeDecodeRequest request) throws IOException {
        if (request.hasSpatialSelection()) {
            return NativeDecodeResult.spatiallySelected(
                    MagickNative.decode(data, request), request);
        }
        if (!request.hasSourceRenderSize()) {
            return super.nativeDecode(data, request);
        }
        return NativeDecodeResult.sourceRendered(
                MagickNative.decode(data, request.sourceRenderSize()));
    }

    @Override
    protected int[] nativeGetSizeFromPath(String path) throws IOException {
        return MagickNative.getSizeFromPath(path);
    }

    @Override
    protected BufferedImage nativeDecodeFromPath(String path) throws IOException {
        return MagickNative.decodeFromPath(path);
    }

    @Override
    protected NativeDecodeResult nativeDecodeFromPath(
            String path, NativeDecodeRequest request) throws IOException {
        if (request.hasSpatialSelection()) {
            return NativeDecodeResult.spatiallySelected(
                    MagickNative.decodeFromPath(path, request), request);
        }
        if (!request.hasSourceRenderSize()) {
            return super.nativeDecodeFromPath(path, request);
        }
        return NativeDecodeResult.sourceRendered(
                MagickNative.decodeFromPath(path, request.sourceRenderSize()));
    }
}
