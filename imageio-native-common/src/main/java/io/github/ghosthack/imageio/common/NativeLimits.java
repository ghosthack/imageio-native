package io.github.ghosthack.imageio.common;

import javax.imageio.IIOException;

/**
 * Decode limits shared by every backend, so one setting bounds a decode
 * whichever backend owns the input.
 */
public final class NativeLimits {

    /** System property holding the maximum decoded pixel count. */
    public static final String MAX_PIXELS_PROPERTY = "imageio.native.maxPixels";

    /** Default limit: 256 megapixels, for example 16384 x 16384. */
    public static final long DEFAULT_MAX_PIXELS = 256L * 1024 * 1024;

    private NativeLimits() {}

    /**
     * Returns the largest pixel count (width times height) a backend may
     * decode, from {@value #MAX_PIXELS_PROPERTY}.
     */
    public static long maxPixels() {
        return Long.getLong(MAX_PIXELS_PROPERTY, DEFAULT_MAX_PIXELS);
    }

    /**
     * Rejects an image whose decoded size exceeds {@link #maxPixels()},
     * protecting against corrupt or hostile inputs that declare huge
     * dimensions.
     *
     * @throws IIOException if the dimensions are not positive or exceed the limit
     */
    public static void requireDecodable(long width, long height)
            throws IIOException {
        if (width <= 0 || height <= 0) {
            throw new IIOException(
                    "Invalid image dimensions: " + width + "x" + height);
        }
        long limit = maxPixels();
        if (width > limit / height) {
            throw new IIOException(
                    "Image too large: " + width + "x" + height
                            + " exceeds " + MAX_PIXELS_PROPERTY + "=" + limit);
        }
    }
}
