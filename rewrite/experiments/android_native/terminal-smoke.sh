#!/usr/bin/env bash
set -Eeuo pipefail
evidence="${1:?evidence}/terminal"; api="${2:?API}"; pages="${3:?pages}"
mkdir -p "$evidence"
collect() {
  for name in race kernel; do
    timeout 8 adb -s "$ANDROID_SERIAL" exec-out run-as com.thekain.runtime.lab cat "files/terminal-evidence/$name.json" > "$evidence/$name.json" 2>/dev/null || true
  done
}
finish() {
  code=$?;collect
  if [ "$code" -ne 0 ]; then
    tail -100 "$evidence/instrumentation.txt" || true
    timeout 8 adb -s "$ANDROID_SERIAL" logcat -d -s TheKaiNHttps:I '*:S' | tail -100 > "$evidence/lifecycle.txt" || true
    tail -100 "$evidence/lifecycle.txt"
  fi
  exit "$code"
}
trap finish EXIT
timeout 180 adb -s "$ANDROID_SERIAL" shell am instrument -w -r -e class com.thekain.runtime.lab.RemoteTerminalTest com.thekain.runtime.lab.test/androidx.test.runner.AndroidJUnitRunner > "$evidence/instrumentation.txt"
collect
python3 -m rewrite.experiments.android_native.terminal_report --evidence "$evidence" --source "$(git rev-parse HEAD)" --api "$api" --pages "$pages"
