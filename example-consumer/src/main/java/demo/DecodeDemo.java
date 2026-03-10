package demo;

import io.github.ghosthack.imageio.ImageioNative;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/**
 * Minimal demo: decodes HEIC / AVIF / WEBP files passed as command-line
 * arguments using both standard {@code ImageIO.read()} and the direct
 * {@link ImageioNative} API.
 * <p>
 * The imageio-native SPI is discovered automatically from the classpath.
 * <p>
 * Usage:  java --enable-native-access=ALL-UNNAMED -jar example-consumer.jar photo.heic photo.avif photo.webp
 */
public class DecodeDemo {

    public static void main(String[] args) throws IOException {
        if (args.length == 0) {
            System.out.println("Usage: java --enable-native-access=ALL-UNNAMED -jar example-consumer.jar <image>...");
            System.out.println("Supported formats: HEIC, AVIF, WEBP (plus all built-in Java ImageIO formats)");
            System.out.println();
            System.out.println("Platform: " + (ImageioNative.isAvailable() ? "native backend available" : "no native backend"));
            System.out.println("Active formats: " + ImageioNative.activeFormats());
            System.out.println("Active suffixes: " + ImageioNative.activeSuffixes());
            System.exit(1);
        }

        for (String path : args) {
            File file = new File(path);
            System.out.printf("%-40s", file.getName());

            // Standard ImageIO path (zero-config, SPI auto-discovered)
            BufferedImage img = ImageIO.read(file);
            if (img == null) {
                System.out.println("FAILED (no reader found)");
                continue;
            }
            System.out.printf("%dx%d  type=%d", img.getWidth(), img.getHeight(), img.getType());

            // Direct API path (richer error info, dimension-only queries)
            if (ImageioNative.isAvailable()) {
                byte[] bytes = Files.readAllBytes(file.toPath());
                Dimension size = ImageioNative.getSize(bytes);
                System.out.printf("  [direct: %dx%d]", size.width, size.height);
            }

            System.out.println();
        }
    }
}
