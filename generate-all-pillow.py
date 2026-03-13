#!/usr/bin/env python3
#
# Generates test fixtures for the Panama ImageIO reader tests.
#
# Usage:  python generate-all-pillow.py
#
# Requires:  pip install pillow pillow-heif pillow-avif-plugin
#
# Produces test8x8.{png,heic,avif,webp} in:
#   imageio-native-common/src/test/resources/  (shared via test-jar)
#
# Note: 8×8 is used rather than 4×4 because the Windows HEVC and AV1
# codec extensions cannot decode images smaller than 8×8 pixels.

import os
from PIL import Image

# Register HEIF/AVIF openers and savers
import pillow_heif

pillow_heif.register_heif_opener()

import pillow_avif  # noqa: F401 – import registers the AVIF plugin

# ── Configuration ───────────────────────────────────────────────────

WIDTH = 8
HEIGHT = 8
DIR = os.path.join("imageio-native-common", "src", "test", "resources")

# ── Create 8×8 RGBA test image: red | green / blue | white ─────────

img = Image.new("RGBA", (WIDTH, HEIGHT))
for y in range(HEIGHT):
    for x in range(WIDTH):
        if y < HEIGHT // 2:
            color = (255, 0, 0, 255) if x < WIDTH // 2 else (0, 255, 0, 255)
        else:
            color = (0, 0, 255, 255) if x < WIDTH // 2 else (255, 255, 255, 255)
        img.putpixel((x, y), color)

# ── Generate test fixtures ──────────────────────────────────────────

img_rgb = img.convert("RGB")

files = {
    "test8x8.png": lambda p: img.save(p, format="PNG"),
    "test8x8.heic": lambda p: img_rgb.save(p, format="HEIF", quality=90),
    "test8x8.avif": lambda p: img_rgb.save(p, format="AVIF", quality=90),
    "test8x8.webp": lambda p: img.save(p, format="WEBP", quality=90),
}

print("Generating test images:")
os.makedirs(DIR, exist_ok=True)
for name, save_fn in files.items():
    path = os.path.join(DIR, name)
    save_fn(path)
    size = os.path.getsize(path)
    print(f"  {path} ({size} bytes)")

print(f"Done – {len(files)} files in {DIR}")

# ── Generate EXIF orientation test fixture ──────────────────────────
# 4×8 stored JPEG with EXIF orientation=6 (rotate 90° CW for display).
# After orientation correction the image appears as 8×4 with quadrants:
#   Blue(TL)  Red(TR)  /  White(BL)  Green(BR)

orient_w, orient_h = 4, 8
orient_img = Image.new("RGB", (orient_w, orient_h))
for y in range(orient_h):
    for x in range(orient_w):
        if y < orient_h // 2:
            color = (255, 0, 0) if x < orient_w // 2 else (0, 255, 0)
        else:
            color = (0, 0, 255) if x < orient_w // 2 else (255, 255, 255)
        orient_img.putpixel((x, y), color)

exif = orient_img.getexif()
exif[0x0112] = 6  # EXIF Orientation tag
orient_path = os.path.join(DIR, "test-orient6.jpg")
orient_img.save(orient_path, format="JPEG", quality=95, exif=exif.tobytes())
print(f"\nOrientation fixture:")
print(f"  {orient_path} ({os.path.getsize(orient_path)} bytes)")
