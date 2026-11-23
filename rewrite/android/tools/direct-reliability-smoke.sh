#!/usr/bin/env bash
set -Eeuo pipefail
: "${ANDROID_SERIAL:?Explicit disposable emulator serial required}"
serial="$ANDROID_SERIAL"
artifacts="${1:?Pass artifact output directory}"
mkdir -p "$artifacts"
adb -s "$serial" shell getprop ro.build.version.sdk > "$artifacts/api.txt"
test "$(timeout 15 adb -s "$serial" shell getprop ro.kernel.qemu | tr -d '\r')" = 1
api="$(tr -d '\r\n' < "$artifacts/api.txt")"
case "$api" in
  35) bash tools/emulator-ui-ready.sh "$artifacts/ui-readiness" ;;
  26|29)
    # The existing API35-only collector deliberately remains unchanged.
    timeout 15 adb -s "$serial" shell dumpsys power > "$artifacts/before-power.txt"
    timeout 15 adb -s "$serial" shell settings put global stay_on_while_plugged_in 7
    timeout 15 adb -s "$serial" shell settings put system screen_off_timeout 1800000
    timeout 15 adb -s "$serial" shell input keyevent KEYCODE_WAKEUP
    timeout 15 adb -s "$serial" shell wm dismiss-keyguard
    timeout 15 adb -s "$serial" shell dumpsys power > "$artifacts/after-power.txt"
    timeout 15 adb -s "$serial" shell dumpsys window policy > "$artifacts/window-policy.txt"
    python3 - "$artifacts/after-power.txt" <<'PY'
import pathlib, re, sys
assert re.search(r'\bmWakefulness=Awake\b', pathlib.Path(sys.argv[1]).read_text()), 'Emulator must be awake'
PY
    ;;
  *) echo 'Only disposable API26/29/35 emulators are allowed' >&2; exit 1 ;;
esac
timeout 240 adb -s "$serial" shell am instrument -w \
  -e listener com.thekain.video.UiFailureListener \
  -e class com.thekain.video.DirectDownloadReliabilityTest \
  com.thekain.video.rebuild.test/androidx.test.runner.AndroidJUnitRunner > "$artifacts/instrumentation.txt"
python3 tools/report-direct-reliability.py "$artifacts/instrumentation.txt"
