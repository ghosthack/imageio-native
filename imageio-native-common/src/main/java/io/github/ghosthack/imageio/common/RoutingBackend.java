package io.github.ghosthack.imageio.common;

/**
 * Common identity and classification for a discoverable decoding backend.
 */
public interface RoutingBackend {

    enum Kind {
        /** Cross-platform or bundled-software codec implementation. */
        PORTABLE,
        /** Operating-system media API. */
        PLATFORM_NATIVE
    }

    /** Stable application-facing ID, such as {@code windows} or {@code ffmpeg}. */
    String id();

    Kind kind();
}
