package io.github.ghosthack.imageio.common;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RoutingResolverTest {

    private record Backend(String id, Kind kind) implements RoutingBackend {}

    private static final Backend APPLE =
            new Backend("apple", RoutingBackend.Kind.PLATFORM_NATIVE);
    private static final Backend WINDOWS =
            new Backend("windows", RoutingBackend.Kind.PLATFORM_NATIVE);
    private static final Backend FFMPEG =
            new Backend("ffmpeg", RoutingBackend.Kind.PORTABLE);
    private static final Backend MAGICK =
            new Backend("magick", RoutingBackend.Kind.PORTABLE);

    @Test
    void emptyCandidatesDeclineToHost() {
        assertNull(RoutingResolver.resolve(List.<Backend>of(), null));
    }

    @Test
    void singleBackendOverridesHostByDefault() {
        assertEquals(WINDOWS, RoutingResolver.resolve(List.of(WINDOWS), null));
    }

    @Test
    void hostRuleDeclinesEvenWithCandidates() {
        assertNull(RoutingResolver.resolve(List.of(FFMPEG, WINDOWS), "host"));
    }

    @Test
    void explicitCapableBackendWins() {
        assertEquals(WINDOWS,
                RoutingResolver.resolve(List.of(FFMPEG, WINDOWS), "windows"));
    }

    @Test
    void unavailablePreferenceDoesNotForceIt() {
        assertEquals(FFMPEG, RoutingResolver.resolve(List.of(FFMPEG), "windows"));
    }

    @Test
    void portableBackendWinsDefaultIntersection() {
        assertEquals(FFMPEG, RoutingResolver.resolve(List.of(APPLE, FFMPEG), null));
    }

    @Test
    void stableIdBreaksTiesWithinKind() {
        assertEquals(FFMPEG, RoutingResolver.resolve(List.of(MAGICK, FFMPEG), null));
        assertEquals(APPLE, RoutingResolver.resolve(List.of(WINDOWS, APPLE), null));
    }
}
