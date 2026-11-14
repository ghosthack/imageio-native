#!/usr/bin/env bash
#
# Generates the long-GOP video test fixture used to verify that a seek
# returns the frame at the requested time rather than the preceding keyframe.
#
# Requires: ffmpeg with libx264
#
# Output: 16x16, 3 seconds @ 10fps, H.264 baseline, ONE keyframe (at t=0).
#   0s-1s: Solid Red, 1s-2s: Solid Green, 2s-3s: Solid Blue
#
# A backend that stops at the keyframe returns Red for every requested time.
#
set -euo pipefail

OUT="imageio-native-video-common/src/test/resources/test-video-long-gop.mp4"

ffmpeg -y -loglevel error \
  -f lavfi -i "color=c=red:size=16x16:rate=10:d=1" \
  -f lavfi -i "color=c=lime:size=16x16:rate=10:d=1" \
  -f lavfi -i "color=c=blue:size=16x16:rate=10:d=1" \
  -filter_complex "[0:v][1:v][2:v]concat=n=3:v=1:a=0" \
  -c:v libx264 -profile:v baseline -g 300 -keyint_min 300 -sc_threshold 0 \
  -pix_fmt yuv420p -movflags +faststart \
  "$OUT"

echo "Generated: $OUT ($(wc -c < "$OUT" | tr -d ' ') bytes)"
echo "Verify: ffprobe -v quiet -select_streams v -show_entries frame=pict_type,pts_time -of compact $OUT"
