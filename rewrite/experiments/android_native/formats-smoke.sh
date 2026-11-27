#!/usr/bin/env bash
set -Eeuo pipefail
evidence="${1:?evidence}/formats"; api="${2:?API}"; pages="${3:?pages}"
mkdir -p "$evidence"
collect() {
  for name in ui.json grant.json choices-180.png choices-90.png history.png; do
    timeout 8 adb -s "$ANDROID_SERIAL" exec-out run-as com.thekain.video.rebuild cat "files/main-formats-evidence/$name" > "$evidence/$name" 2>/dev/null || true
  done
  timeout 8 adb -s "$ANDROID_SERIAL" exec-out run-as com.thekain.video.rebuild cat files/isolated-source-evidence.jsonl > "$evidence/source-journal.jsonl" 2>/dev/null || true
  timeout 8 adb -s "$ANDROID_SERIAL" exec-out run-as com.thekain.video.rebuild cat files/isolated-transfer-evidence.jsonl > "$evidence/transfer-journal.jsonl" 2>/dev/null || true
}
finish() {
  code=$?; collect
  if [ "$code" -ne 0 ]; then
    tail -100 "$evidence/instrumentation.txt" || true
    timeout 10 adb -s "$ANDROID_SERIAL" exec-out run-as com.thekain.video.rebuild tar -C files -cf - ui-failure-evidence > "$evidence/window-evidence.tar" 2>/dev/null || true
  fi
  exit "$code"
}
trap finish EXIT
# New group only. Original C2, D1 and 73 older cases retain every existing timeout/assertion.
timeout 240 adb -s "$ANDROID_SERIAL" shell am instrument -w -r -e class com.thekain.video.OwnedFormatsUiTest -e listener com.thekain.video.UiFailureListener com.thekain.video.rebuild.test/androidx.test.runner.AndroidJUnitRunner > "$evidence/instrumentation.txt"
collect
python3 -m rewrite.experiments.android_native.formats_report --evidence "$evidence" --source "$(git rev-parse HEAD)" --api "$api" --pages "$pages"
