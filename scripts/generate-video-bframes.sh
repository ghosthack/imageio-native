#!/usr/bin/env bash
#
# Generates the B-frame video test fixture that reproduces the
# "copyCGImageAtTime returns NULL at t=0" bug.
#
# Requires: ffmpeg with libx264
#
# Output: 16x16, 3 seconds @ 30fps, H.264 High profile with B-frames.
#   - has_b_frames=2 (consecutive B-frames)
#   - start_pts=1014 (~66ms, non-zero)
#   - Audio track (AAC stereo, matches real-world MP4 structure)
#
# These properties cause AVFoundation's copyCGImageAtTime to return NULL
# when requesting t=0 with zero tolerance -- the same behaviour seen in
# many real-world videos from web sources, screen recorders, and
# GIF-to-MP4 converters.
#
set -euo pipefail

OUT="imageio-native-video-common/src/test/resources/test-video-3s-bframes.mp4"

ffmpeg -y \
  -f lavfi -i "color=c=red:size=16x16:rate=30:d=3" \
  -f lavfi -i "anullsrc=r=44100:cl=stereo" \
  -c:v libx264 -profile:v high -bf 2 -g 30 -pix_fmt yuv420p \
  -c:a aac -b:a 128k \
  -shortest \
  -movflags +faststart \
  -output_ts_offset 0.066 \
  "$OUT"

echo ""
echo "Generated: $OUT ($(wc -c < "$OUT" | tr -d ' ') bytes)"
echo "Verify: ffprobe -v quiet -show_entries stream=start_pts,has_b_frames,codec_name -of compact $OUT"
