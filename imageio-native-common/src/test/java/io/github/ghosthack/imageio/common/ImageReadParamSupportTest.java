package io.github.ghosthack.imageio.common;

import org.junit.jupiter.api.Test;

import javax.imageio.IIOException;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageTypeSpecifier;
import java.awt.Dimension;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageReadParamSupportTest {

    @Test
    void boundsNativeAndJavaIntermediateAllocations() throws IIOException {
        ImageReadParamSupport.validateIntermediateDimensions(8, 8);

        assertThrows(
                IIOException.class,
                () -> ImageReadParamSupport.validateIntermediateDimensions(
                        30_000, 30_000));
        assertThrows(
                IIOException.class,
                () -> ImageReadParamSupport.validateIntermediateDimensions(
                        0, 10));
    }

    @Test
    void nullAndDefaultParamsReturnDecodedImageWithoutCopying() throws IIOException {
        BufferedImage source = sourceImage();

        assertSame(source, ImageReadParamSupport.apply(source, null));
        assertSame(source, ImageReadParamSupport.apply(source, new ImageReadParam()));
    }

    @Test
    void appliesSourceRegionAndSubsamplingOffsets() throws IIOException {
        BufferedImage source = sourceImage();
        ImageReadParam param = new ImageReadParam();
        param.setSourceRegion(new Rectangle(1, 1, 5, 4));
        param.setSourceSubsampling(2, 2, 1, 0);

        BufferedImage result = ImageReadParamSupport.apply(source, param);

        assertEquals(2, result.getWidth());
        assertEquals(2, result.getHeight());
        assertPixelEquals(source, 2, 1, result, 0, 0);
        assertPixelEquals(source, 4, 1, result, 1, 0);
        assertPixelEquals(source, 2, 3, result, 0, 1);
        assertPixelEquals(source, 4, 3, result, 1, 1);
    }

    @Test
    void writesIntoDestinationAtOffsetAndClipsToItsBounds() throws IIOException {
        BufferedImage source = sourceImage();
        BufferedImage destination =
                new BufferedImage(5, 4, BufferedImage.TYPE_INT_ARGB_PRE);
        fill(destination, 0xff7f1133);

        ImageReadParam param = new ImageReadParam();
        param.setSourceRegion(new Rectangle(1, 1, 4, 3));
        param.setDestination(destination);
        param.setDestinationOffset(new Point(2, 1));

        BufferedImage result = ImageReadParamSupport.apply(source, param);

        assertSame(destination, result);
        assertPixelEquals(source, 1, 1, result, 2, 1);
        assertPixelEquals(source, 3, 3, result, 4, 3);
        assertEquals(0xff7f1133, result.getRGB(0, 0));
        assertEquals(0xff7f1133, result.getRGB(1, 1));
    }

    @Test
    void negativeDestinationOffsetClipsTheSource() throws IIOException {
        BufferedImage source = sourceImage();
        ImageReadParam param = new ImageReadParam();
        param.setDestinationOffset(new Point(-1, -1));

        BufferedImage result = ImageReadParamSupport.apply(source, param);

        assertEquals(source.getWidth() - 1, result.getWidth());
        assertEquals(source.getHeight() - 1, result.getHeight());
        assertPixelEquals(source, 1, 1, result, 0, 0);
        assertPixelEquals(
                source,
                source.getWidth() - 1,
                source.getHeight() - 1,
                result,
                result.getWidth() - 1,
                result.getHeight() - 1);
    }

    @Test
    void positiveDestinationOffsetExpandsANewDestination() throws IIOException {
        BufferedImage source = sourceImage();
        ImageReadParam param = new ImageReadParam();
        param.setSourceRegion(new Rectangle(0, 0, 2, 2));
        param.setDestinationOffset(new Point(2, 1));

        BufferedImage result = ImageReadParamSupport.apply(source, param);

        assertEquals(4, result.getWidth());
        assertEquals(3, result.getHeight());
        assertEquals(0, result.getRGB(0, 0));
        assertPixelEquals(source, 0, 0, result, 2, 1);
        assertPixelEquals(source, 1, 1, result, 3, 2);
    }

    @Test
    void acceptsAdvertisedDestinationTypeAndRejectsOtherTypes() throws IIOException {
        BufferedImage source = sourceImage();
        ImageReadParam supported = new ImageReadParam();
        supported.setDestinationType(
                ImageTypeSpecifier.createFromBufferedImageType(
                        BufferedImage.TYPE_INT_ARGB_PRE));

        BufferedImage result = ImageReadParamSupport.apply(source, supported);

        assertNotSame(source, result);
        assertEquals(BufferedImage.TYPE_INT_ARGB_PRE, result.getType());
        assertPixelEquals(source, 3, 2, result, 3, 2);

        ImageReadParam unsupported = new ImageReadParam();
        unsupported.setDestinationType(
                ImageTypeSpecifier.createFromBufferedImageType(
                        BufferedImage.TYPE_INT_RGB));
        assertThrows(
                IIOException.class,
                () -> ImageReadParamSupport.apply(source, unsupported));
    }

    @Test
    void destinationImageTakesPrecedenceOverDestinationType() throws IIOException {
        BufferedImage source = sourceImage();
        BufferedImage destination =
                new BufferedImage(6, 5, BufferedImage.TYPE_INT_ARGB_PRE);
        ImageReadParam param = new ImageReadParam();
        param.setDestinationType(
                ImageTypeSpecifier.createFromBufferedImageType(
                        BufferedImage.TYPE_INT_RGB));
        param.setDestination(destination);

        assertSame(destination, ImageReadParamSupport.apply(source, param));
        assertPixelEquals(source, 5, 4, destination, 5, 4);
    }

    @Test
    void rendersSourceAtRequestedSizeBeforeOtherSelections() throws IIOException {
        BufferedImage source = sourceImage();
        ImageReadParam param = ImageReadParamSupport.createDefaultReadParam();
        assertTrue(param.canSetSourceRenderSize());
        param.setSourceRenderSize(new Dimension(4, 3));
        param.setSourceRegion(new Rectangle(1, 1, 2, 2));

        BufferedImage result = ImageReadParamSupport.apply(source, param);

        assertEquals(2, result.getWidth());
        assertEquals(2, result.getHeight());
    }

    @Test
    void doesNotRenderSourceSizeTwiceWhenNativeDecoderAppliedIt()
            throws IIOException {
        BufferedImage rendered =
                new BufferedImage(4, 3, BufferedImage.TYPE_INT_ARGB_PRE);
        ImageReadParam param = ImageReadParamSupport.createDefaultReadParam();
        param.setSourceRenderSize(new Dimension(4, 3));

        BufferedImage result = ImageReadParamSupport.apply(
                NativeDecodeResult.sourceRendered(rendered), param);

        assertSame(rendered, result);
    }

    @Test
    void rejectsIncorrectNativeRenderSize() {
        BufferedImage rendered =
                new BufferedImage(3, 3, BufferedImage.TYPE_INT_ARGB_PRE);
        ImageReadParam param = ImageReadParamSupport.createDefaultReadParam();
        param.setSourceRenderSize(new Dimension(4, 3));

        assertThrows(
                IIOException.class,
                () -> ImageReadParamSupport.apply(
                        NativeDecodeResult.sourceRendered(rendered), param));
    }

    @Test
    void placesNativeSpatialSelectionIntoFinalDestination()
            throws IIOException {
        BufferedImage source = sourceImage();
        ImageReadParam param = new ImageReadParam();
        param.setSourceRegion(new Rectangle(1, 1, 3, 2));
        param.setDestinationOffset(new Point(2, 1));
        NativeDecodeRequest request =
                NativeDecodeRequest.from(
                        param, source.getWidth(), source.getHeight());

        BufferedImage reference =
                ImageReadParamSupport.apply(source, param);
        Rectangle destinationRegion = request.destinationRegion();
        BufferedImage selected = new BufferedImage(
                destinationRegion.width,
                destinationRegion.height,
                BufferedImage.TYPE_INT_ARGB_PRE);
        for (int y = 0; y < selected.getHeight(); y++) {
            for (int x = 0; x < selected.getWidth(); x++) {
                selected.setRGB(
                        x,
                        y,
                        reference.getRGB(
                                destinationRegion.x + x,
                                destinationRegion.y + y));
            }
        }

        BufferedImage result = ImageReadParamSupport.apply(
                NativeDecodeResult.spatiallySelected(selected, request),
                param);

        assertImagesEqual(reference, result);
    }

    @Test
    void mapsSelectedSourceBandsToSelectedDestinationBands() throws IIOException {
        BufferedImage source = sourceImage();
        BufferedImage destination =
                new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB_PRE);
        WritableRaster destinationRaster = destination.getRaster();
        destinationRaster.setPixel(0, 0, new int[]{10, 20, 30, 40});

        ImageReadParam param = new ImageReadParam();
        param.setSourceRegion(new Rectangle(2, 2, 1, 1));
        param.setDestination(destination);
        param.setSourceBands(new int[]{2, 0});
        param.setDestinationBands(new int[]{0, 2});

        BufferedImage result = ImageReadParamSupport.apply(source, param);

        int[] sourcePixel = source.getRaster().getPixel(2, 2, (int[]) null);
        int[] resultPixel = result.getRaster().getPixel(0, 0, (int[]) null);
        assertEquals(sourcePixel[2], resultPixel[0]);
        assertEquals(20, resultPixel[1]);
        assertEquals(sourcePixel[0], resultPixel[2]);
        assertEquals(40, resultPixel[3]);
    }

    @Test
    void validatesBandCountsAndIndices() {
        BufferedImage source = sourceImage();

        ImageReadParam mismatchedCounts = new ImageReadParam();
        mismatchedCounts.setSourceBands(new int[]{0, 1});
        assertThrows(
                IllegalArgumentException.class,
                () -> ImageReadParamSupport.apply(source, mismatchedCounts));

        ImageReadParam outOfRange = new ImageReadParam();
        outOfRange.setSourceBands(new int[]{4});
        outOfRange.setDestinationBands(new int[]{0});
        assertThrows(
                IllegalArgumentException.class,
                () -> ImageReadParamSupport.apply(source, outOfRange));
    }

    @Test
    void rejectsIncompatibleDestinationImage() {
        BufferedImage source = sourceImage();
        ImageReadParam param = new ImageReadParam();
        param.setDestination(
                new BufferedImage(6, 5, BufferedImage.TYPE_INT_RGB));

        assertThrows(
                IIOException.class,
                () -> ImageReadParamSupport.apply(source, param));
    }

    @Test
    void rejectsProgressivePassSelectionInsteadOfIgnoringIt() {
        BufferedImage source = sourceImage();
        ImageReadParam param = new ImageReadParam();
        param.setSourceProgressivePasses(0, 1);

        assertThrows(
                IIOException.class,
                () -> ImageReadParamSupport.apply(source, param));
    }

    private static BufferedImage sourceImage() {
        BufferedImage image =
                new BufferedImage(6, 5, BufferedImage.TYPE_INT_ARGB_PRE);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int red = 10 + x * 20;
                int green = 20 + y * 30;
                int blue = 30 + x + y;
                image.setRGB(x, y, 0xff000000 | red << 16 | green << 8 | blue);
            }
        }
        return image;
    }

    private static void fill(BufferedImage image, int argb) {
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                image.setRGB(x, y, argb);
            }
        }
    }

    private static void assertPixelEquals(
            BufferedImage expected,
            int expectedX,
            int expectedY,
            BufferedImage actual,
            int actualX,
            int actualY) {
        assertEquals(
                expected.getRGB(expectedX, expectedY),
                actual.getRGB(actualX, actualY));
    }

    private static void assertImagesEqual(
            BufferedImage expected, BufferedImage actual) {
        assertEquals(expected.getWidth(), actual.getWidth());
        assertEquals(expected.getHeight(), actual.getHeight());
        for (int y = 0; y < expected.getHeight(); y++) {
            for (int x = 0; x < expected.getWidth(); x++) {
                assertEquals(
                        expected.getRGB(x, y),
                        actual.getRGB(x, y),
                        "pixel at " + x + "," + y);
            }
        }
    }
}
