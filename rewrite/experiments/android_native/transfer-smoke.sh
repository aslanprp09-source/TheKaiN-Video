#!/usr/bin/env bash
set -Eeuo pipefail
evidence="${1:?evidence}"; api="${2:?API}"; pages="${3:?pages}"
mkdir -p "$evidence/transfer/remote" "$evidence/transfer/main"
collect_transfer() {
  for name in stream security lifecycle boundary; do
    timeout 8 adb -s "$ANDROID_SERIAL" exec-out run-as com.thekain.runtime.lab cat "files/remote-download-evidence/$name.json" > "$evidence/transfer/remote/$name.json" 2>/dev/null || true
  done
  for name in verification lifecycle storage migration ui; do
    timeout 8 adb -s "$ANDROID_SERIAL" exec-out run-as com.thekain.video.rebuild cat "files/main-progressive-evidence/$name.json" > "$evidence/transfer/main/$name.json" 2>/dev/null || true
  done
  timeout 8 adb -s "$ANDROID_SERIAL" exec-out run-as com.thekain.video.rebuild cat files/main-progressive-evidence/completed.png > "$evidence/transfer/completed.png" 2>/dev/null || true
  timeout 8 adb -s "$ANDROID_SERIAL" exec-out run-as com.thekain.video.rebuild cat files/isolated-transfer-evidence.jsonl > "$evidence/transfer/transfer-journal.jsonl" 2>/dev/null || true
}
transfer_finish() {
  code=$?; collect_transfer
  if [ "$code" -ne 0 ]; then
    for name in remote main ui; do
      if [ -f "$evidence/transfer/$name-instrumentation.txt" ]; then tail -100 "$evidence/transfer/$name-instrumentation.txt"; fi
    done
  fi
  exit "$code"
}
trap transfer_finish EXIT
timeout 180 adb -s "$ANDROID_SERIAL" shell am instrument -w -r -e class com.thekain.runtime.lab.RemoteDownloadTest com.thekain.runtime.lab.test/androidx.test.runner.AndroidJUnitRunner > "$evidence/transfer/remote-instrumentation.txt"
python3 - "$evidence/transfer" <<'PYREMOTE'
from pathlib import Path
import sys
from rewrite.experiments.android_native.bridge_report import raw
raw(Path(sys.argv[1]),'remote-instrumentation.txt',4)
PYREMOTE
timeout 120 adb -s "$ANDROID_SERIAL" shell am instrument -w -r -e class com.thekain.runtime.lab.MainProgressiveTransferTest com.thekain.video.rebuild.test/androidx.test.runner.AndroidJUnitRunner > "$evidence/transfer/main-instrumentation.txt"
python3 - "$evidence/transfer" <<'PYMAIN'
from pathlib import Path
import sys
from rewrite.experiments.android_native.bridge_report import raw
raw(Path(sys.argv[1]),'main-instrumentation.txt',4)
PYMAIN
timeout 150 adb -s "$ANDROID_SERIAL" shell am instrument -w -r -e class com.thekain.video.OwnedProgressiveUiTest -e listener com.thekain.video.UiFailureListener com.thekain.video.rebuild.test/androidx.test.runner.AndroidJUnitRunner > "$evidence/transfer/ui-instrumentation.txt"
collect_transfer
python3 -m rewrite.experiments.android_native.transfer_report --evidence "$evidence/transfer" --source "$(git rev-parse HEAD)" --api "$api" --pages "$pages"
