package demo;

import io.github.ghosthack.imageio.ImageioNative;
import io.github.ghosthack.imageio.video.VideoFrameExtractor;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

/**
 * Minimal demo: decodes any file passed as a command-line argument using
 * {@code ImageIO.read()}.  Works for images (HEIC, AVIF, WEBP, ...) and
 * video poster frames (MP4, MOV, ...) — same API, same call.
 * <p>
 * Usage:
 * <pre>
 * java --enable-native-access=ALL-UNNAMED -jar example-consumer.jar photo.heic clip.mp4
 * </pre>
 */
public class DecodeDemo {

    public static void main(String[] args) throws IOException {
        if (args.length == 0) {
            System.out.println("Usage: java --enable-native-access=ALL-UNNAMED -jar example-consumer.jar <file>...");
            System.out.println();
            System.out.println("Supports images (HEIC, AVIF, WEBP, JP2, RAW, ...) and video poster frames (MP4, MOV, ...)");
            System.out.println();
            System.out.println("Image backend: " + (ImageioNative.isAvailable() ? "available" : "not available"));
            System.out.println("Video backend: " + (VideoFrameExtractor.isAvailable() ? "available" : "not available"));
            System.out.println("Active image formats: " + ImageioNative.activeFormats());
            System.exit(1);
        }

        for (String path : args) {
            File file = new File(path);
            System.out.printf("%-40s", file.getName());

            if (!file.exists()) {
                System.out.println("NOT FOUND");
                continue;
            }

            // One call — images and video poster frames are handled identically
            BufferedImage img = ImageIO.read(file);

            if (img == null) {
                System.out.println("FAILED (no reader found)");
            } else {
                System.out.printf("%dx%d  type=%d%n", img.getWidth(), img.getHeight(), img.getType());
            }
        }
    }
}
