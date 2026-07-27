package io.github.ghosthack.imageio.common;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ImageIoRoutingTest {

    @Test
    void validatesNormalizesAndFreezesConfiguration() throws Exception {
        resetState();
        try {
            assertThrows(IllegalArgumentException.class,
                    () -> ImageIoRouting.configure(routes ->
                            routes.prefer("jpeg", "typo")));

            ImageIoRouting.configure(routes -> routes.preferHost("JPG"));
            assertEquals("host", ImageIoRouting.preference("jpeg"));

            assertThrows(IllegalStateException.class,
                    () -> ImageIoRouting.configure(routes ->
                            routes.preferHost("png")));
        } finally {
            resetState();
        }
    }

    private static void resetState() throws Exception {
        set("configuredRoutes", Map.of());
        set("frozenRoutes", null);
        set("configured", false);
    }

    private static void set(String name, Object value) throws Exception {
        Field field = ImageIoRouting.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }
}
