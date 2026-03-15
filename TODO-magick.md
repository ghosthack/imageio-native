# TODO: imageio-native-magick

Optional ImageIO backend backed by ImageMagick 7's MagickWand C API via Panama FFM.
Same architecture as imageio-native-vips -- not in aggregator, opt-in only.

## MagickWand downcalls (~12 functions)

| Function | Purpose |
|----------|---------|
| `MagickWandGenesis` | One-time init |
| `NewMagickWand` | Create wand |
| `DestroyMagickWand` | Destroy wand |
| `MagickReadImage` | Load from path |
| `MagickReadImageBlob` | Load from buffer |
| `MagickPingImageBlob` | Probe (header only) |
| `MagickGetImageWidth/Height` | Dimensions |
| `MagickSetImageAlphaChannel` | Premultiply alpha |
| `MagickExportImagePixels` | Export pixels as ARGB bytes |
| `MagickGetException` | Error message |
| `MagickRelinquishMemory` | Free error string |

## Library discovery

Try Q16HDRI first (Homebrew), then Q16 (MacPorts):
1. System property `imageio.native.magick.lib`
2. `/opt/local/lib/ImageMagick7/lib/libMagickWand-7.Q16HDRI.dylib`
3. `/opt/local/lib/ImageMagick7/lib/libMagickWand-7.Q16.dylib`
4. `/usr/local/lib/libMagickWand-7.Q16HDRI.dylib`
5. `/usr/local/lib/libMagickWand-7.Q16.dylib`
6. `/usr/lib/*/libMagickWand-7.Q16HDRI.so` or `Q16.so`
7. `System.loadLibrary` fallback

## Decode pipeline

```
MagickWandGenesis()
wand = NewMagickWand()
MagickReadImage(wand, path)
w = MagickGetImageWidth(wand)
h = MagickGetImageHeight(wand)
MagickSetImageAlphaChannel(wand, AssociateAlphaChannel)
MagickExportImagePixels(wand, 0, 0, w, h, "ARGB", CharPixel, buf)
repack ARGB bytes -> 0xAARRGGBB int[]
-> BufferedImage(TYPE_INT_ARGB_PRE)
DestroyMagickWand(wand)
```

## Files

| File | Change |
|------|--------|
| pom.xml (parent) | Add module |
| imageio-native-magick/pom.xml | New |
| magick/MagickNative.java | Panama downcalls |
| magick/MagickImageReader.java | Extends NativeImageReader |
| magick/MagickImageReaderSpi.java | SPI, backendName()="magick" |
| magick/FormatRegistry.java | Hardcoded format catalog |
| magick/services file | SPI registration |
| magick/MagickImageReaderTest.java | Tests |
| README.md | Add magick to Optional backends |
