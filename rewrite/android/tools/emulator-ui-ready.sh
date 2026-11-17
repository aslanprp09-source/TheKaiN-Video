#!/usr/bin/env bash
# Hosted API35 test emulator only; never run against an owner's physical device.
set -Eeuo pipefail
: "${ANDROID_SERIAL:?Explicit emulator serial required}"
evidence="${1:?Evidence directory required}"
target=(adb -s "$ANDROID_SERIAL")

test "$(timeout 15 "${target[@]}" shell getprop ro.kernel.qemu | tr -d '\r')" = 1
test "$(timeout 15 "${target[@]}" shell getprop ro.build.version.sdk | tr -d '\r')" = 35
mkdir -p "$evidence"
timeout 15 "${target[@]}" shell dumpsys power > "$evidence/before-power.txt"
timeout 15 "${target[@]}" shell dumpsys window policy > "$evidence/before-window-policy.txt"

# Compilation can outlast the default screen timeout after the startup unlock.
# Keep this disposable emulator awake for the bounded 25-minute job, and unlock
# immediately before instrumentation rather than relying on an earlier MENU key.
timeout 15 "${target[@]}" shell settings put global stay_on_while_plugged_in 7
timeout 15 "${target[@]}" shell settings put system screen_off_timeout 1800000
timeout 15 "${target[@]}" shell input keyevent KEYCODE_WAKEUP
timeout 15 "${target[@]}" shell wm dismiss-keyguard

timeout 15 "${target[@]}" shell dumpsys power > "$evidence/after-power.txt"
timeout 15 "${target[@]}" shell dumpsys window policy > "$evidence/after-window-policy.txt"
python3 - "$evidence" <<'PY'
from pathlib import Path
import re
import sys

root = Path(sys.argv[1])
for stage in ('before', 'after'):
    text = (root / f'{stage}-power.txt').read_text()
    state = re.search(r'\bmWakefulness=(\w+)', text)
    print(f'Emulator UI {stage}: wakefulness={state[1] if state else "UNREADABLE"}')
    if stage == 'after' and (not state or state[1] != 'Awake'):
        raise SystemExit('Emulator must be awake before UI instrumentation')
PY
