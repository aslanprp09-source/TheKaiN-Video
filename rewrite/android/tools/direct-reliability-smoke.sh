#!/usr/bin/env bash
set -Eeuo pipefail
serial="${ANDROID_SERIAL:-emulator-5554}"
artifacts="${1:?Pass artifact output directory}"
mkdir -p "$artifacts"
adb -s "$serial" shell getprop ro.build.version.sdk > "$artifacts/api.txt"
bash tools/emulator-ui-ready.sh "$artifacts/ui-readiness"
timeout 240 adb -s "$serial" shell am instrument -w \
  -e listener com.thekain.video.UiFailureListener \
  -e class com.thekain.video.DirectDownloadReliabilityTest \
  com.thekain.video.rebuild.test/androidx.test.runner.AndroidJUnitRunner > "$artifacts/instrumentation.txt"
python3 tools/report-direct-reliability.py "$artifacts/instrumentation.txt"
