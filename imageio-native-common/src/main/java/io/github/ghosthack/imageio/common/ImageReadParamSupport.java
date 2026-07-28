package io.github.ghosthack.imageio.common;

import javax.imageio.IIOException;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageTypeSpecifier;
import java.awt.AlphaComposite;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;
import java.util.List;
import java.util.Objects;

/**
 * Applies the standard {@link ImageReadParam} operations not already handled
 * by a native backend.
 * <p>
 * Native decoders in this project produce {@link BufferedImage#TYPE_INT_ARGB_PRE}
 * images. The normalized request/result contract allows safe render sizing,
 * spatial selection, and subsampling to be pushed down while this class
 * consistently applies the remaining band and destination operations.
 */
public final class ImageReadParamSupport {

    private static final long DEFAULT_MAX_INTERMEDIATE_BYTES =
            512L * 1024 * 1024;

    private static final ImageTypeSpecifier IMAGE_TYPE =
            ImageTypeSpecifier.createFromBufferedImageType(BufferedImage.TYPE_INT_ARGB_PRE);

    private ImageReadParamSupport() {}

    /**
     * Creates the default read parameter used by native readers.
     * Source rendering size is supported by scaling the decoded native image
     * before applying regions and subsampling.
     */
    public static ImageReadParam createDefaultReadParam() {
        return new NativeReadParam();
    }

    /**
     * Rejects a four-byte-per-pixel native or Java intermediate that exceeds
     * the configured allocation limit.
     *
     * @param width intermediate width
     * @param height intermediate height
     * @throws IIOException if either dimension is invalid or the allocation
     *         would exceed {@code imageio.native.maxIntermediateBytes}
     */
    public static void validateIntermediateDimensions(int width, int height)
            throws IIOException {
        if (width <= 0 || height <= 0) {
            throw new IIOException(
                    "Invalid intermediate dimensions: "
                            + width + "x" + height);
        }
        long maxBytes = Long.getLong(
                "imageio.native.maxIntermediateBytes",
                DEFAULT_MAX_INTERMEDIATE_BYTES);
        long pixels = (long) width * height;
        if (maxBytes < 4L || pixels > maxBytes / 4L) {
            throw new IIOException(
                    "Intermediate "
                            + width + "x" + height
                            + " exceeds imageio.native."
                            + "maxIntermediateBytes="
                            + maxBytes);
        }
    }

    /**
     * Validates parameter settings that do not depend on image dimensions.
     *
     * @param param read parameters, or {@code null}
     * @throws IIOException if a destination type is unsupported or
     *         progressive-pass selection was requested
     */
    public static void validate(ImageReadParam param) throws IIOException {
        if (param == null) {
            return;
        }
        requireDefaultProgressivePasses(param);

        BufferedImage requestedDestination = param.getDestination();
        if (requestedDestination != null) {
            requireSupportedType(requestedDestination, "Destination image");
        } else {
            ImageTypeSpecifier requestedType = param.getDestinationType();
            if (requestedType != null && !IMAGE_TYPE.equals(requestedType)) {
                throw new IIOException(
                        "Destination type must be TYPE_INT_ARGB_PRE");
            }
        }

        ImageReaderAccess.checkBandSettings(param, 4, 4);
    }

    /**
     * Applies {@code param} to a fully decoded native image.
     *
     * @param source decoded {@code TYPE_INT_ARGB_PRE} image
     * @param param read parameters, or {@code null}
     * @return {@code source} when no transformations were requested; otherwise
     *         the selected or newly-created destination image
     * @throws IIOException if the source or requested destination type is not
     *         supported, or progressive-pass selection was requested
     */
    public static BufferedImage apply(BufferedImage source, ImageReadParam param)
            throws IIOException {
        return apply(NativeDecodeResult.fullSize(source), param);
    }

    /**
     * Applies the operations not already handled by a native decoder.
     *
     * @param decoded native image and its pushed-down operations
     * @param param read parameters, or {@code null}
     * @return the final destination image
     */
    public static BufferedImage apply(
            NativeDecodeResult decoded, ImageReadParam param) throws IIOException {
        Objects.requireNonNull(decoded, "decoded");
        BufferedImage source = decoded.image();
        Objects.requireNonNull(source, "source");
        requireSupportedType(source, "Native decoder output");
        validate(param);
        validateAppliedOperations(decoded, param);

        if (decoded.applied(
                NativeDecodeResult.Operation.SPATIAL_SELECTION)) {
            return applySpatialSelection(decoded, param);
        }

        if (param == null || isDefault(decoded, param)) {
            return source;
        }

        BufferedImage renderedSource =
                decoded.applied(NativeDecodeResult.Operation.SOURCE_RENDER_SIZE)
                        ? source
                        : renderToSize(source, param.getSourceRenderSize());
        BufferedImage destination = ImageReaderAccess.getDestination(
                param,
                List.of(IMAGE_TYPE),
                renderedSource.getWidth(),
                renderedSource.getHeight());

        Rectangle sourceRegion = new Rectangle();
        Rectangle destinationRegion = new Rectangle();
        ImageReaderAccess.calculateRegions(
                param,
                renderedSource.getWidth(),
                renderedSource.getHeight(),
                destination,
                sourceRegion,
                destinationRegion);

        Raster sourceRaster = renderedSource.getRaster();
        WritableRaster destinationRaster = destination.getRaster();
        ImageReaderAccess.checkBandSettings(
                param,
                sourceRaster.getNumBands(),
                destinationRaster.getNumBands());

        int sourceXSubsampling = param.getSourceXSubsampling();
        int sourceYSubsampling = param.getSourceYSubsampling();
        int[] sourceBands = param.getSourceBands();
        int[] destinationBands = param.getDestinationBands();

        if (sourceBands == null && destinationBands == null) {
            copyPixels(
                    sourceRaster,
                    destinationRaster,
                    sourceRegion,
                    destinationRegion,
                    sourceXSubsampling,
                    sourceYSubsampling);
        } else {
            copyBands(
                    sourceRaster,
                    destinationRaster,
                    sourceRegion,
                    destinationRegion,
                    sourceXSubsampling,
                    sourceYSubsampling,
                    sourceBands,
                    destinationBands);
        }
        return destination;
    }

    private static boolean isDefault(
            NativeDecodeResult decoded, ImageReadParam param) {
        return param.getDestination() == null
                && param.getDestinationType() == null
                && param.getDestinationOffset().x == 0
                && param.getDestinationOffset().y == 0
                && param.getSourceRegion() == null
                && param.getSourceXSubsampling() == 1
                && param.getSourceYSubsampling() == 1
                && param.getSubsamplingXOffset() == 0
                && param.getSubsamplingYOffset() == 0
                && param.getSourceBands() == null
                && param.getDestinationBands() == null
                && (param.getSourceRenderSize() == null
                || decoded.applied(NativeDecodeResult.Operation.SOURCE_RENDER_SIZE))
                && hasDefaultProgressivePasses(param);
    }

    private static void validateAppliedOperations(
            NativeDecodeResult decoded, ImageReadParam param) throws IIOException {
        if (decoded.applied(
                NativeDecodeResult.Operation.SPATIAL_SELECTION)) {
            NativeDecodeRequest request = decoded.request();
            if (request == null || !request.hasSpatialSelection()) {
                throw new IIOException(
                        "Native spatial selection has no normalized request");
            }
            Rectangle destinationRegion = request.destinationRegion();
            BufferedImage image = decoded.image();
            if (image.getWidth() != destinationRegion.width
                    || image.getHeight() != destinationRegion.height) {
                throw new IIOException(
                        "Native spatial selection returned "
                                + image.getWidth() + "x" + image.getHeight()
                                + ", expected "
                                + destinationRegion.width + "x"
                                + destinationRegion.height);
            }
        }
        if (!decoded.applied(NativeDecodeResult.Operation.SOURCE_RENDER_SIZE)) {
            return;
        }
        Dimension requestedSize =
                param != null ? param.getSourceRenderSize() : null;
        if (requestedSize == null) {
            throw new IIOException(
                    "Native decoder applied an unrequested source render size");
        }
        if (decoded.applied(
                NativeDecodeResult.Operation.SPATIAL_SELECTION)) {
            NativeDecodeRequest request = decoded.request();
            if (request.renderedSourceWidth() != requestedSize.width
                    || request.renderedSourceHeight() != requestedSize.height) {
                throw new IIOException(
                        "Native spatial plan used the wrong source render size");
            }
            return;
        }
        BufferedImage image = decoded.image();
        if (image.getWidth() != requestedSize.width
                || image.getHeight() != requestedSize.height) {
            throw new IIOException(
                    "Native decoder returned "
                            + image.getWidth() + "x" + image.getHeight()
                            + " after applying source render size "
                            + requestedSize.width + "x" + requestedSize.height);
        }
    }

    private static BufferedImage applySpatialSelection(
            NativeDecodeResult decoded, ImageReadParam param)
            throws IIOException {
        NativeDecodeRequest request = decoded.request();
        BufferedImage selected = decoded.image();
        Rectangle destinationRegion = request.destinationRegion();

        if (param.getDestination() == null
                && param.getDestinationType() == null
                && destinationRegion.x == 0
                && destinationRegion.y == 0
                && param.getSourceBands() == null
                && param.getDestinationBands() == null) {
            return selected;
        }

        BufferedImage destination = ImageReaderAccess.getDestination(
                param,
                List.of(IMAGE_TYPE),
                request.renderedSourceWidth(),
                request.renderedSourceHeight());
        Raster sourceRaster = selected.getRaster();
        WritableRaster destinationRaster = destination.getRaster();
        ImageReaderAccess.checkBandSettings(
                param,
                sourceRaster.getNumBands(),
                destinationRaster.getNumBands());

        Rectangle selectedRegion =
                new Rectangle(0, 0, selected.getWidth(), selected.getHeight());
        int[] sourceBands = param.getSourceBands();
        int[] destinationBands = param.getDestinationBands();
        if (sourceBands == null && destinationBands == null) {
            copyPixels(
                    sourceRaster,
                    destinationRaster,
                    selectedRegion,
                    destinationRegion,
                    1,
                    1);
        } else {
            copyBands(
                    sourceRaster,
                    destinationRaster,
                    selectedRegion,
                    destinationRegion,
                    1,
                    1,
                    sourceBands,
                    destinationBands);
        }
        return destination;
    }

    private static boolean hasDefaultProgressivePasses(ImageReadParam param) {
        return param.getSourceMinProgressivePass() == 0
                && param.getSourceNumProgressivePasses() == Integer.MAX_VALUE;
    }

    private static void requireDefaultProgressivePasses(ImageReadParam param)
            throws IIOException {
        if (!hasDefaultProgressivePasses(param)) {
            throw new IIOException(
                    "Progressive-pass selection is not supported by native decoders");
        }
    }

    private static void requireSupportedType(BufferedImage image, String description)
            throws IIOException {
        ImageTypeSpecifier actualType =
                ImageTypeSpecifier.createFromRenderedImage(image);
        if (!IMAGE_TYPE.equals(actualType)) {
            throw new IIOException(
                    description + " must be TYPE_INT_ARGB_PRE");
        }
    }

    /**
     * Renders a decoded image at an exact size using the interpolation used by
     * the shared fallback.
     */
    public static BufferedImage renderToSize(
            BufferedImage source, Dimension size) {
        Objects.requireNonNull(source, "source");
        if (size == null
                || (size.width == source.getWidth()
                && size.height == source.getHeight())) {
            return source;
        }

        BufferedImage rendered = new BufferedImage(
                size.width, size.height, BufferedImage.TYPE_INT_ARGB_PRE);
        Graphics2D graphics = rendered.createGraphics();
        try {
            graphics.setComposite(AlphaComposite.Src);
            graphics.setRenderingHint(
                    RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.drawImage(source, 0, 0, size.width, size.height, null);
        } finally {
            graphics.dispose();
        }
        return rendered;
    }

    static void calculateRegions(
            ImageReadParam param,
            int sourceWidth,
            int sourceHeight,
            BufferedImage destination,
            Rectangle sourceRegion,
            Rectangle destinationRegion) {
        ImageReaderAccess.calculateRegions(
                param,
                sourceWidth,
                sourceHeight,
                destination,
                sourceRegion,
                destinationRegion);
    }

    private static void copyPixels(
            Raster source,
            WritableRaster destination,
            Rectangle sourceRegion,
            Rectangle destinationRegion,
            int sourceXSubsampling,
            int sourceYSubsampling) {
        if (sourceXSubsampling == 1) {
            Object row = null;
            for (int y = 0; y < destinationRegion.height; y++) {
                int sourceY = sourceRegion.y + y * sourceYSubsampling;
                row = source.getDataElements(
                        sourceRegion.x, sourceY, destinationRegion.width, 1, row);
                destination.setDataElements(
                        destinationRegion.x, destinationRegion.y + y,
                        destinationRegion.width, 1, row);
            }
            return;
        }

        Object pixel = null;
        for (int y = 0; y < destinationRegion.height; y++) {
            int sourceY = sourceRegion.y + y * sourceYSubsampling;
            for (int x = 0; x < destinationRegion.width; x++) {
                int sourceX = sourceRegion.x + x * sourceXSubsampling;
                pixel = source.getDataElements(sourceX, sourceY, pixel);
                destination.setDataElements(
                        destinationRegion.x + x, destinationRegion.y + y, pixel);
            }
        }
    }

    private static void copyBands(
            Raster source,
            WritableRaster destination,
            Rectangle sourceRegion,
            Rectangle destinationRegion,
            int sourceXSubsampling,
            int sourceYSubsampling,
            int[] requestedSourceBands,
            int[] requestedDestinationBands) {
        int[] sourceBands = requestedSourceBands != null
                ? requestedSourceBands
                : sequentialBands(source.getNumBands());
        int[] destinationBands = requestedDestinationBands != null
                ? requestedDestinationBands
                : sequentialBands(destination.getNumBands());

        for (int y = 0; y < destinationRegion.height; y++) {
            int sourceY = sourceRegion.y + y * sourceYSubsampling;
            int destinationY = destinationRegion.y + y;
            for (int x = 0; x < destinationRegion.width; x++) {
                int sourceX = sourceRegion.x + x * sourceXSubsampling;
                int destinationX = destinationRegion.x + x;
                for (int band = 0; band < sourceBands.length; band++) {
                    int sample = source.getSample(
                            sourceX, sourceY, sourceBands[band]);
                    destination.setSample(
                            destinationX, destinationY, destinationBands[band], sample);
                }
            }
        }
    }

    private static int[] sequentialBands(int count) {
        int[] bands = new int[count];
        for (int i = 0; i < count; i++) {
            bands[i] = i;
        }
        return bands;
    }

    private static final class NativeReadParam extends ImageReadParam {

        private NativeReadParam() {
            canSetSourceRenderSize = true;
        }
    }

    /**
     * Narrow access to the JDK's canonical ImageReader parameter helpers.
     */
    private abstract static class ImageReaderAccess extends ImageReader {

        private ImageReaderAccess() {
            super(null);
        }

        private static BufferedImage getDestination(
                ImageReadParam param,
                List<ImageTypeSpecifier> imageTypes,
                int width,
                int height) throws IIOException {
            return ImageReader.getDestination(
                    param, imageTypes.iterator(), width, height);
        }

        private static void calculateRegions(
                ImageReadParam param,
                int sourceWidth,
                int sourceHeight,
                BufferedImage destination,
                Rectangle sourceRegion,
                Rectangle destinationRegion) {
            ImageReader.computeRegions(
                    param,
                    sourceWidth,
                    sourceHeight,
                    destination,
                    sourceRegion,
                    destinationRegion);
        }

        private static void checkBandSettings(
                ImageReadParam param, int sourceBandCount, int destinationBandCount) {
            ImageReader.checkReadParamBandSettings(
                    param, sourceBandCount, destinationBandCount);
        }
    }
}
