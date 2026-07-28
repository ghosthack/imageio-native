package io.github.ghosthack.imageio.apple;

import io.github.ghosthack.imageio.common.NativeImageReader;
import io.github.ghosthack.imageio.common.NativeDecodeRequest;
import io.github.ghosthack.imageio.common.NativeDecodeResult;

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
    protected boolean supportsNativeSourceRenderSize() {
        return true;
    }

    @Override
    protected int[] nativeGetSize(byte[] data) throws IOException {
        return AppleNative.getSize(data);
    }

    @Override
    protected BufferedImage nativeDecode(byte[] data) throws IOException {
        return AppleNative.decode(data);
    }

    @Override
    protected NativeDecodeResult nativeDecode(
            byte[] data, NativeDecodeRequest request) throws IOException {
        if (!request.hasSourceRenderSize()) {
            return super.nativeDecode(data, request);
        }
        return NativeDecodeResult.sourceRendered(
                AppleNative.decode(data, request.sourceRenderSize()));
    }

    @Override
    protected int[] nativeGetSizeFromPath(String path) throws IOException {
        return AppleNative.getSizeFromPath(path);
    }

    @Override
    protected BufferedImage nativeDecodeFromPath(String path) throws IOException {
        return AppleNative.decodeFromPath(path);
    }

    @Override
    protected NativeDecodeResult nativeDecodeFromPath(
            String path, NativeDecodeRequest request) throws IOException {
        if (!request.hasSourceRenderSize()) {
            return super.nativeDecodeFromPath(path, request);
        }
        return NativeDecodeResult.sourceRendered(
                AppleNative.decodeFromPath(path, request.sourceRenderSize()));
    }
}
