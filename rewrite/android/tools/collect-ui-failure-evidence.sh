#!/usr/bin/env bash
set -Eeuo pipefail
: "${ANDROID_SERIAL:?Explicit emulator serial required}"
destination="${1:?Evidence directory required}"
mkdir -p "$destination"
if timeout 15 adb -s "$ANDROID_SERIAL" shell run-as com.thekain.video.rebuild ls files/ui-failure-evidence >/dev/null 2>&1; then
  timeout 30 adb -s "$ANDROID_SERIAL" exec-out run-as com.thekain.video.rebuild tar -C files -cf - ui-failure-evidence | tar -xf - -C "$destination"
fi
python3 - "$destination" <<'PY'
from pathlib import Path
import re
import sys
for path in sorted(Path(sys.argv[1]).rglob('*.txt')):
    if path.name not in ('activity-state.txt', 'window.txt', 'activities.txt', 'capture-errors.txt'):
        continue
    lines = path.read_text(errors='replace').splitlines()
    selected = [line[:500] for line in lines if re.search(
        r'mCurrentFocus|mFocusedApp|mTopResumedActivity|mResumedActivity|hasWindowFocus|stage=|component=|ready=|activeWindowPackage|keyguardLocked|capture-errors', line)]
    if selected:
        print(f'UI evidence {path.name}:\n' + '\n'.join(selected[:20]))
PY
