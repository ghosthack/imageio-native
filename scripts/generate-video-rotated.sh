#!/usr/bin/env bash
#
# Generates the rotated (portrait) video test fixture.
#
# Requires: ffmpeg with libx264
#
# Output: stored 32x16, 1 second @ 30fps, H.264, with a display matrix that
# rotates the picture 90 degrees clockwise for display (ffprobe reports
# rotation=-90, the same metadata phones write for portrait recordings).
#
#   Stored frame (32x16):   Red  | Blue
#   Displayed frame (16x32): Red on top, Blue on the bottom
#
# Backends must report 16x32 and return the displayed frame.
#
set -euo pipefail

OUT="imageio-native-video-common/src/test/resources/test-video-rotated.mp4"
TMP="$(mktemp -t rotated-src).mp4"
trap 'rm -f "$TMP"' EXIT

ffmpeg -y -loglevel error \
  -f lavfi -i "color=c=red:size=16x16:rate=30:d=1" \
  -f lavfi -i "color=c=blue:size=16x16:rate=30:d=1" \
  -filter_complex "[0:v][1:v]hstack=inputs=2" \
  -c:v libx264 -profile:v baseline -g 1 -pix_fmt yuv420p \
  -movflags +faststart \
  "$TMP"

ffmpeg -y -loglevel error \
  -display_rotation -90 -i "$TMP" \
  -c copy -movflags +faststart \
  "$OUT"

echo "Generated: $OUT ($(wc -c < "$OUT" | tr -d ' ') bytes)"
echo "Verify: ffprobe -v quiet -show_entries stream=width,height:stream_side_data=rotation -of compact $OUT"
