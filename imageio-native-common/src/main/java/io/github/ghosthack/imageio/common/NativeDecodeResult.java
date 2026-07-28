package io.github.ghosthack.imageio.common;

import java.awt.image.BufferedImage;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Image returned by a native decoder together with the read operations that
 * were already applied to it.
 */
public final class NativeDecodeResult {

    /**
     * Read operations that native backends may currently push down.
     */
    public enum Operation {
        /** The image already has the requested source render size. */
        SOURCE_RENDER_SIZE,
        /** Effective source region and subsampling were already applied. */
        SPATIAL_SELECTION
    }

    private final BufferedImage image;
    private final EnumSet<Operation> appliedOperations;
    private final NativeDecodeRequest request;

    private NativeDecodeResult(
            BufferedImage image,
            EnumSet<Operation> appliedOperations,
            NativeDecodeRequest request) {
        this.image = Objects.requireNonNull(image, "image");
        this.appliedOperations = appliedOperations.clone();
        this.request = request;
    }

    /**
     * Wraps a full-size decode with no pushed-down operations.
     */
    public static NativeDecodeResult fullSize(BufferedImage image) {
        return new NativeDecodeResult(
                image, EnumSet.noneOf(Operation.class), null);
    }

    /**
     * Wraps an image rendered natively at the requested source size.
     */
    public static NativeDecodeResult sourceRendered(BufferedImage image) {
        return new NativeDecodeResult(
                image, EnumSet.of(Operation.SOURCE_RENDER_SIZE), null);
    }

    /**
     * Wraps an image containing the exact normalized spatial selection.
     */
    public static NativeDecodeResult spatiallySelected(
            BufferedImage image, NativeDecodeRequest request) {
        Objects.requireNonNull(request, "request");
        if (!request.hasSpatialSelection()) {
            throw new IllegalArgumentException(
                    "request has no spatial selection");
        }
        EnumSet<Operation> operations =
                EnumSet.of(Operation.SPATIAL_SELECTION);
        if (request.hasSourceRenderSize()) {
            operations.add(Operation.SOURCE_RENDER_SIZE);
        }
        return new NativeDecodeResult(image, operations, request);
    }

    public BufferedImage image() {
        return image;
    }

    public boolean applied(Operation operation) {
        return appliedOperations.contains(operation);
    }

    public Set<Operation> appliedOperations() {
        return Set.copyOf(appliedOperations);
    }

    public NativeDecodeRequest request() {
        return request;
    }
}
