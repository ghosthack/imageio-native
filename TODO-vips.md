# TODO: imageio-native-vips + Backend Priority System

## Overview

Add `imageio-native-vips` as an optional backend module that delegates to
libvips via Panama FFM. Also add a `BackendPriority` system so consumers
can control which backend handles which formats.

`imageio-native-vips` is NOT added to the `imageio-native` aggregator --
users opt in explicitly. It requires libvips installed on the system.

---

## Step 1: BackendPriority class in imageio-native-common

New class: `io.github.ghosthack.imageio.common.BackendPriority`

System properties:
```
-Dimageio.native.backend.priority=native,vips,magick     # global ordering
-Dimageio.native.backend.priority.jpeg=vips,native        # per-format override
```

API:
- `priority(String backend)` -> int (lower = higher priority, from position in list)
- `isAllowed(String backend, String format)` -> boolean

Defaults (no properties set): `native=0, vips=1, magick=2`. All backends allowed.
Parsing happens once (static init), cached.

---

## Step 2: Retrofit existing SPIs with priority

`AppleImageReaderSpi` and `WicImageReaderSpi`:
- `onRegistration()`: use `BackendPriority.priority("native")` + `setOrdering()`
- `canDecodeInput()`: add `BackendPriority.isAllowed("native", format)` check

Backward compatible -- no properties set = identical to current behavior.

---

## Step 3: New module imageio-native-vips

### 3a. pom.xml

- Parent: imageio-native-parent
- ArtifactId: imageio-native-vips
- Dependencies: imageio-native-common, test-jar, junit
- Added to parent POM modules list (NOT to imageio-native aggregator)

### 3b. VipsNative.java

Panama downcalls to libvips + GLib.

Library discovery order:
1. `System.getProperty("imageio.native.vips.lib")`
2. `/opt/local/lib/libvips.dylib` (MacPorts)
3. `/usr/local/lib/libvips.dylib` (Homebrew)
4. `/usr/lib/*/libvips.so` (Linux)
5. `SymbolLookup.libraryLookup("vips", ...)` fallback

Also loads libgobject-2.0 / libglib-2.0 for g_object_unref / g_free.

Downcall handles:

| Function | Purpose |
|----------|---------|
| `vips_init` | One-time init |
| `vips_image_new_from_file` | Load from path (variadic) |
| `vips_image_new_from_buffer` | Load from bytes (variadic) |
| `vips_image_get_width/height/bands` | Dimensions |
| `vips_image_hasalpha` | Alpha detection |
| `vips_colourspace` | Convert to sRGB |
| `vips_premultiply` | Premultiply alpha |
| `vips_cast_uchar` | Cast to 8-bit |
| `vips_image_write_to_memory` | Get pixel buffer |
| `vips_foreign_find_load_buffer` | Probe format support |
| `vips_error_buffer` / `vips_error_clear` | Error handling |
| `g_object_unref` / `g_free` | Cleanup |

Public methods:
- `isAvailable()` -- load lib + vips_init, cache result
- `canDecode(byte[], int)` -- vips_foreign_find_load_buffer
- `getSize(byte[])` / `getSizeFromPath(String)` -- header only
- `decode(byte[])` / `decodeFromPath(String)` -- full pipeline

Decode pipeline:
```
vips_image_new_from_file(path, "access", VIPS_ACCESS_SEQUENTIAL, NULL)
  -> vips_colourspace(VIPS_INTERPRETATION_sRGB)
  -> vips_premultiply() (if hasalpha)
  -> vips_cast_uchar()
  -> vips_image_write_to_memory()
  -> repack RGBA -> 0xAARRGGBB int[]
  -> BufferedImage(TYPE_INT_ARGB_PRE)
cleanup: g_free(buf), g_object_unref(each image)
```

RGBA -> ARGB repacking:
```java
dest[i] = (a << 24) | (r << 16) | (g << 8) | b;    // 4-band
dest[i] = 0xFF000000 | (r << 16) | (g << 8) | b;    // 3-band
```

### 3c. VipsImageReader.java

Extends NativeImageReader. Overrides all 4 hooks:
- nativeGetSize(byte[]) -> VipsNative.getSize()
- nativeDecode(byte[]) -> VipsNative.decode()
- nativeGetSizeFromPath(String) -> VipsNative.getSizeFromPath()
- nativeDecodeFromPath(String) -> VipsNative.decodeFromPath()

### 3d. VipsImageReaderSpi.java

Constructor: hardcoded format names and suffixes:
- Formats: HEIC, HEIF, AVIF, WebP, JPEG2000, JP2, TIFF, OpenEXR, EXR,
  PDF, SVG, GIF, FITS, PBM, PGM, PPM, PFM
- Suffixes: heic, heif, avif, webp, jp2, j2k, tif, tiff, exr, pdf, svg,
  gif, fits, fit, pbm, pgm, ppm, pfm
- MIME types: corresponding standard types

canDecodeInput():
1. BackendPriority.isAllowed("vips", format) -- bail if excluded
2. VipsNative.isAvailable() -- bail if lib not found
3. FormatDetector.isJavaNativeFormat() -- bail if supplemental mode excludes it
4. VipsNative.canDecode(header, len) -- probe

onRegistration():
- BackendPriority.priority("vips") for SPI ordering

### 3e. Service file

META-INF/services/javax.imageio.spi.ImageReaderSpi:
  io.github.ghosthack.imageio.vips.VipsImageReaderSpi

### 3f. Tests

VipsImageReaderTest:
- Decode test8x8.heic, .avif, .webp, .png via VipsNative
- Verify 8x8 dimensions + quadrant colors (tolerance for lossy)
- Gated by assumeTrue(VipsNative.isAvailable())

VipsImageioTest:
- ImageIO.read() via SPI
- Supplemental mode respected

### 3g. README

New "Optional backends" section:
- What imageio-native-vips adds
- Requires libvips installed (MacPorts, Homebrew, apt)
- Hardcoded format list decision documented
- Backend priority configuration + examples

---

## Step 4: Build and verify

1. Compile all modules including imageio-native-vips
2. Existing 126 tests pass unchanged
3. New vips tests pass (on machines with libvips)
4. example-consumer still works

---

## Files

| File | Change |
|------|--------|
| pom.xml (parent) | Add imageio-native-vips to modules |
| common/.../BackendPriority.java | NEW |
| apple/.../AppleImageReaderSpi.java | Add BackendPriority checks |
| windows/.../WicImageReaderSpi.java | Add BackendPriority checks |
| imageio-native-vips/pom.xml | NEW module POM |
| vips/.../VipsNative.java | NEW -- Panama downcalls |
| vips/.../VipsImageReader.java | NEW -- extends NativeImageReader |
| vips/.../VipsImageReaderSpi.java | NEW -- SPI with priority |
| vips/...services/...ImageReaderSpi | NEW -- service file |
| vips/.../VipsImageReaderTest.java | NEW |
| vips/.../VipsImageioTest.java | NEW |
| README.md | Optional backends section |
