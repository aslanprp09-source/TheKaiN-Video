#!/usr/bin/env bash
set -Eeuo pipefail
: "${ANDROID_SERIAL:?Explicit emulator serial required}"
destination="${1:?Evidence directory required}"
mkdir -p "$destination"
if timeout 15 adb -s "$ANDROID_SERIAL" shell run-as com.thekain.video.rebuild ls files/ui-failure-evidence >/dev/null 2>&1; then
  timeout 30 adb -s "$ANDROID_SERIAL" exec-out run-as com.thekain.video.rebuild tar -C files -cf - ui-failure-evidence | tar -xf - -C "$destination"
fi
# Preserve system focus/ANR evidence even when Gradle has already removed the app.
timeout 5 adb -s "$ANDROID_SERIAL" shell dumpsys window windows > "$destination/post-window.txt" 2>&1 || true
timeout 5 adb -s "$ANDROID_SERIAL" shell dumpsys activity lastanr > "$destination/post-last-anr.txt" 2>&1 || true
timeout 5 adb -s "$ANDROID_SERIAL" logcat -b events -d -t 300 > "$destination/post-events.txt" 2>&1 || true
timeout 5 adb -s "$ANDROID_SERIAL" exec-out screencap -p > "$destination/post-screen.png" 2>/dev/null || true
python3 - "$destination" <<'PY'
from pathlib import Path
import re
import sys
for path in sorted(Path(sys.argv[1]).rglob('*.txt')):
    if path.name not in ('activity-state.txt', 'window.txt', 'activities.txt', 'capture-errors.txt',
                         'last-anr.txt', 'post-window.txt', 'post-last-anr.txt', 'post-events.txt'):
        continue
    lines = path.read_text(errors='replace').splitlines()
    selected = [line[:500] for line in lines if re.search(
        r'mCurrentFocus|mFocusedApp|mTopResumedActivity|mResumedActivity|hasWindowFocus|stage=|component=|ready=|activeWindowPackage|keyguardLocked|ANR in|am_anr|Reason:|not responding|capture-errors', line)]
    if selected:
        print(f'UI evidence {path.name}:\n' + '\n'.join(selected[:20]))
PY
