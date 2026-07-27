# Remaining work

Current after the Java 26 / imageio-native 2.0.0 migration.

## Correctness

- Make each video SPI probe backend/container capability, not just container
  magic. The formats advertised by a platform backend are not necessarily all
  decodable on every machine.

## Memory and architecture

- Move the Media Foundation small-frame canvas normalization into
  `panama-media`. Version 0.1.0 can return a padded 192x96 RGB32 canvas for a
  16x16 source, so `WindowsVideoFrameExtractor` currently queries metadata and
  crops it. Fixing this upstream would avoid opening the source twice per frame.

The completed WIC, Media Foundation, libvips, ImageMagick, and FFmpeg
implementation plans were removed when this list was refreshed.
