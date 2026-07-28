package io.github.ghosthack.imageio.windows;

import io.github.ghosthack.imageio.common.NativeImageReader;
import io.github.ghosthack.imageio.common.ImageReadParamSupport;
import io.github.ghosthack.imageio.common.NativeDecodeRequest;
import io.github.ghosthack.imageio.common.NativeDecodeResult;
import io.github.ghosthack.panama.media.core.Dimensions;
import io.github.ghosthack.panama.media.wic.WIC;

import javax.imageio.spi.ImageReaderSpi;
import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.foreign.Arena;

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
    protected boolean supportsNativeSourceRenderSize() {
        return true;
    }

    @Override
    protected int[] nativeGetSize(byte[] data) throws IOException {
        try {
            Dimensions size = WIC.getSize(data);
            return new int[]{size.width(), size.height()};
        } catch (RuntimeException e) {
            throw PanamaMediaImages.decodeFailure("WIC size query failed", e);
        }
    }

    @Override
    protected BufferedImage nativeDecode(byte[] data) throws IOException {
        try (Arena arena = Arena.ofConfined()) {
            return PanamaMediaImages.toBufferedImage(WIC.decode(arena, data));
        } catch (RuntimeException e) {
            throw PanamaMediaImages.decodeFailure("WIC image decode failed", e);
        }
    }

    @Override
    protected int[] nativeGetSizeFromPath(String path) throws IOException {
        try {
            Dimensions size = WIC.getSize(path);
            return new int[]{size.width(), size.height()};
        } catch (RuntimeException e) {
            throw PanamaMediaImages.decodeFailure("WIC size query failed for: " + path, e);
        }
    }

    @Override
    protected BufferedImage nativeDecodeFromPath(String path) throws IOException {
        try (Arena arena = Arena.ofConfined()) {
            return PanamaMediaImages.toBufferedImage(WIC.decodeFromPath(arena, path));
        } catch (RuntimeException e) {
            throw PanamaMediaImages.decodeFailure("WIC image decode failed for: " + path, e);
        }
    }

    @Override
    protected NativeDecodeResult nativeDecodeFromPath(
            String path, NativeDecodeRequest request) throws IOException {
        if (!request.hasSourceRenderSize()) {
            return super.nativeDecodeFromPath(path, request);
        }
        try (Arena arena = Arena.ofConfined()) {
            Dimension renderSize = request.sourceRenderSize();
            int maxDimension = Math.max(renderSize.width, renderSize.height);
            // WIC's thumbnail API accepts one maximum edge rather than an
            // exact shape, so conservatively bound its possible square output.
            ImageReadParamSupport.validateIntermediateDimensions(
                    maxDimension, maxDimension);
            BufferedImage thumbnail = PanamaMediaImages.toBufferedImage(
                    WIC.decodeThumbnailFromPath(arena, path, maxDimension));
            BufferedImage rendered = ImageReadParamSupport.renderToSize(
                    thumbnail, renderSize);
            return NativeDecodeResult.sourceRendered(rendered);
        } catch (RuntimeException e) {
            throw PanamaMediaImages.decodeFailure(
                    "WIC reduced image decode failed for: " + path, e);
        }
    }
}
