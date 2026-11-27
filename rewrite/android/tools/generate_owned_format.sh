#!/usr/bin/env bash
# Developer-only fixture generation. FFmpeg is NOT shipped with the Android app.
set -Eeuo pipefail
cd "$(dirname "$0")/.."
output="${1:?Write to a NEW output path; do not replace an existing fixture}"
test ! -e "$output"
ffmpeg -nostdin -hide_banner -loglevel error \
  -f lavfi -i color=c=0x325080:s=320x180:r=10:d=1 \
  -f lavfi -i anullsrc=r=8000:cl=mono -t 1 -map 0:v:0 -map 1:a:0 \
  -c:v libx264 -profile:v baseline -pix_fmt yuv420p -threads 1 \
  -c:a aac -b:a 16k -map_metadata -1 -fflags +bitexact \
  -flags:v +bitexact -flags:a +bitexact -movflags +faststart "$output"
ffprobe -v error -show_streams -show_format -of json "$output"
sha256sum "$output"
