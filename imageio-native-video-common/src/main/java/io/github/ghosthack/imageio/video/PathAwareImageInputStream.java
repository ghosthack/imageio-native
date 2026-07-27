package io.github.ghosthack.imageio.video;

import java.io.File;
import java.io.IOException;

/**
 * Compatibility alias for the path-aware stream now provided by
 * {@code imageio-native-common}.
 * <p>
 * New code should use
 * {@link io.github.ghosthack.imageio.common.PathAwareImageInputStream}.
 *
 * @deprecated use
 * {@link io.github.ghosthack.imageio.common.PathAwareImageInputStream}
 */
@Deprecated
public class PathAwareImageInputStream
        extends io.github.ghosthack.imageio.common.PathAwareImageInputStream {

    public PathAwareImageInputStream(File file) throws IOException {
        super(file);
    }
}
