package io.github.ghosthack.imageio.common;

import javax.imageio.ImageReadParam;
import java.awt.Dimension;
import java.awt.Point;
import java.awt.Rectangle;

/**
 * Immutable subset of an {@link ImageReadParam} that a native decoder may
 * safely apply before producing a Java image.
 * <p>
 * Operations are added here only when their semantics can be verified
 * independently of destination placement and band mapping.
 */
public final class NativeDecodeRequest {

    private static final NativeDecodeRequest NONE =
            new NativeDecodeRequest(null, -1, -1, null, null, 1, 1);

    private final Dimension sourceRenderSize;
    private final int renderedSourceWidth;
    private final int renderedSourceHeight;
    private final Rectangle sourceRegion;
    private final Rectangle destinationRegion;
    private final int sourceXSubsampling;
    private final int sourceYSubsampling;

    private NativeDecodeRequest(
            Dimension sourceRenderSize,
            int renderedSourceWidth,
            int renderedSourceHeight,
            Rectangle sourceRegion,
            Rectangle destinationRegion,
            int sourceXSubsampling,
            int sourceYSubsampling) {
        this.sourceRenderSize = copy(sourceRenderSize);
        this.renderedSourceWidth = renderedSourceWidth;
        this.renderedSourceHeight = renderedSourceHeight;
        this.sourceRegion = copy(sourceRegion);
        this.destinationRegion = copy(destinationRegion);
        this.sourceXSubsampling = sourceXSubsampling;
        this.sourceYSubsampling = sourceYSubsampling;
    }

    /**
     * Creates a request from read parameters.
     */
    public static NativeDecodeRequest from(ImageReadParam param) {
        if (param == null || param.getSourceRenderSize() == null) {
            return NONE;
        }
        return new NativeDecodeRequest(
                param.getSourceRenderSize(), -1, -1, null, null, 1, 1);
    }

    /**
     * Creates a request with JDK-normalized spatial regions.
     */
    public static NativeDecodeRequest from(
            ImageReadParam param, int sourceWidth, int sourceHeight) {
        Dimension renderSize =
                param != null ? param.getSourceRenderSize() : null;
        int renderedWidth =
                renderSize != null ? renderSize.width : sourceWidth;
        int renderedHeight =
                renderSize != null ? renderSize.height : sourceHeight;
        Rectangle sourceRegion = new Rectangle();
        Rectangle destinationRegion = new Rectangle();
        ImageReadParamSupport.calculateRegions(
                param,
                renderedWidth,
                renderedHeight,
                param != null ? param.getDestination() : null,
                sourceRegion,
                destinationRegion);
        return new NativeDecodeRequest(
                renderSize,
                renderedWidth,
                renderedHeight,
                sourceRegion,
                destinationRegion,
                param != null ? param.getSourceXSubsampling() : 1,
                param != null ? param.getSourceYSubsampling() : 1);
    }

    /**
     * Returns whether parameters can reduce the spatial raster a backend
     * materializes.
     */
    public static boolean requestsSpatialSelection(ImageReadParam param) {
        if (param == null) {
            return false;
        }
        Point destinationOffset = param.getDestinationOffset();
        return param.getSourceRegion() != null
                || param.getSourceXSubsampling() != 1
                || param.getSourceYSubsampling() != 1
                || param.getSubsamplingXOffset() != 0
                || param.getSubsamplingYOffset() != 0
                || param.getDestination() != null
                || destinationOffset.x != 0
                || destinationOffset.y != 0;
    }

    /**
     * Returns the requested source render size, or {@code null}.
     */
    public Dimension sourceRenderSize() {
        return copy(sourceRenderSize);
    }

    /**
     * Returns whether source render sizing was requested.
     */
    public boolean hasSourceRenderSize() {
        return sourceRenderSize != null;
    }

    public boolean hasSpatialSelection() {
        return sourceRegion != null;
    }

    public int renderedSourceWidth() {
        return renderedSourceWidth;
    }

    public int renderedSourceHeight() {
        return renderedSourceHeight;
    }

    public Rectangle sourceRegion() {
        return copy(sourceRegion);
    }

    public Rectangle destinationRegion() {
        return copy(destinationRegion);
    }

    public int sourceXSubsampling() {
        return sourceXSubsampling;
    }

    public int sourceYSubsampling() {
        return sourceYSubsampling;
    }

    private static Dimension copy(Dimension dimension) {
        return dimension != null ? new Dimension(dimension) : null;
    }

    private static Rectangle copy(Rectangle rectangle) {
        return rectangle != null ? new Rectangle(rectangle) : null;
    }
}
