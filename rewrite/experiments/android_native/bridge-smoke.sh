#!/usr/bin/env bash
set -Eeuo pipefail
api="${1:?API}"; pages="${2:?pages}"; delivery="${3:?same-run delivery}"; evidence="${4:?evidence}"
mkdir -p "$evidence/remote" "$evidence/main"
collect() {
  for name in ipc dns resources public schema; do
    timeout 8 adb -s "$ANDROID_SERIAL" exec-out run-as com.thekain.runtime.lab cat "files/remote-https-evidence/$name.json" > "$evidence/remote/$name.json" 2>/dev/null || true
  done
  for name in public cancel security early ui; do
    timeout 8 adb -s "$ANDROID_SERIAL" exec-out run-as com.thekain.video.rebuild cat "files/main-isolated-evidence/$name.json" > "$evidence/main/$name.json" 2>/dev/null || true
  done
  timeout 8 adb -s "$ANDROID_SERIAL" exec-out run-as com.thekain.runtime.lab cat files/network-owner-death-marker.json > "$evidence/network-owner-death-marker.json" 2>/dev/null || true
  timeout 8 adb -s "$ANDROID_SERIAL" exec-out run-as com.thekain.runtime.lab cat files/network-owner-recovery.json > "$evidence/network-owner-recovery.json" 2>/dev/null || true
  timeout 8 adb -s "$ANDROID_SERIAL" exec-out run-as com.thekain.video.rebuild cat files/isolated-source-evidence.jsonl > "$evidence/main-source-journal.jsonl" 2>/dev/null || true
  timeout 8 adb -s "$ANDROID_SERIAL" exec-out run-as com.thekain.video.rebuild cat files/isolated-evidence.jsonl > "$evidence/main-runtime-journal.jsonl" 2>/dev/null || true
  timeout 8 adb -s "$ANDROID_SERIAL" exec-out run-as com.thekain.video.rebuild cat files/main-isolated-evidence/preview.png > "$evidence/main-isolated-preview.png" 2>/dev/null || true
  timeout 10 adb -s "$ANDROID_SERIAL" exec-out run-as com.thekain.video.rebuild tar -C files -cf - ui-failure-evidence > "$evidence/main-window-evidence.tar" 2>/dev/null || true
}
finish() {
  code=$?;collect
  if [ "$code" -ne 0 ]; then
    python3 -m rewrite.experiments.android_native.source_failure_diagnostics --source "$evidence/main-source-journal.jsonl" --runtime "$evidence/main-runtime-journal.jsonl" --commit "$(git rev-parse HEAD)" || true
    python3 - "$evidence/main-source-journal.jsonl" <<'PYSOURCEFAILURE'
from pathlib import Path
import json,sys
path=Path(sys.argv[1])
if path.exists() and path.stat().st_size<=256*1024:
    for line in path.read_text(errors='replace').splitlines()[-6:]:
        try:
            row=json.loads(line);network=row.get('network',{})
            print('MAIN_SOURCE_TERMINAL_PROOF '+json.dumps({k:row.get(k) for k in ('id','error','elapsed_ms','python_calls','worker_pid','death_confirmed','child_protection')}
                | {'network_failure':network.get('failure'),'network_terminated':network.get('terminated'),'network_requests':network.get('requests')},sort_keys=True))
        except (ValueError,TypeError):print('MAIN_SOURCE_PARTIAL_JOURNAL_RETAINED')
PYSOURCEFAILURE
    timeout 8 adb -s "$ANDROID_SERIAL" logcat -d -s TheKaiNHttps:I '*:S' | tail -120 > "$evidence/remote-lifecycle.txt" || true
    tail -60 "$evidence/remote-lifecycle.txt"
    timeout 8 adb -s "$ANDROID_SERIAL" logcat -d -s TheKaiNIsolated:I '*:S' | tail -100 > "$evidence/source-lifecycle.txt" || true
    tail -100 "$evidence/source-lifecycle.txt"
    for name in remote-instrumentation remote-owner-preparation remote-owner-recovery-instrumentation main-analysis-instrumentation main-ui-instrumentation main-boundary; do
      if [ -f "$evidence/$name.txt" ]; then tail -100 "$evidence/$name.txt"; fi
    done
    timeout 8 adb -s "$ANDROID_SERIAL" shell dumpsys window > "$evidence/main-failure-window.txt" 2>/dev/null || true
    timeout 8 adb -s "$ANDROID_SERIAL" shell dumpsys activity activities > "$evidence/main-failure-activities.txt" 2>/dev/null || true
    timeout 10 adb -s "$ANDROID_SERIAL" shell uiautomator dump /sdcard/thekain-main-bridge.xml > "$evidence/main-hierarchy-command.txt" 2>&1 || true
    timeout 8 adb -s "$ANDROID_SERIAL" exec-out cat /sdcard/thekain-main-bridge.xml > "$evidence/main-failure-ui.xml" 2>/dev/null || true
    timeout 8 adb -s "$ANDROID_SERIAL" exec-out screencap -p > "$evidence/main-failure-screen.png" 2>/dev/null || true
  fi
  timeout 8 adb -s "$ANDROID_SERIAL" shell am force-stop com.thekain.video.rebuild >/dev/null 2>&1 || true
  exit "$code"
}
trap finish EXIT
python3 rewrite/experiments/android_native/main_delivery.py verify --folder "$delivery"
timeout 180 adb -s "$ANDROID_SERIAL" shell am instrument -w -r -e class com.thekain.runtime.lab.RemoteHttpsTest com.thekain.runtime.lab.test/androidx.test.runner.AndroidJUnitRunner > "$evidence/remote-instrumentation.txt"
python3 - "$evidence" <<'PYREMOTE'
from pathlib import Path
import sys
from rewrite.experiments.android_native.bridge_report import raw
raw(Path(sys.argv[1]),'remote-instrumentation.txt',5)
PYREMOTE
bash rewrite/experiments/android_native/readiness-smoke.sh "$evidence" "$api" "$pages"

# A deliberate owner SIGKILL while actual DNS is stuck. This invocation is NOT counted as a passed test.
owner_attempt=$(python3 -c 'import uuid;print(uuid.uuid4())')
set +e
timeout 45 adb -s "$ANDROID_SERIAL" shell am instrument -w -r -e attempt "$owner_attempt" -e class 'com.thekain.runtime.lab.RemoteOwnerDeathTest#prepareAndKillOwner' com.thekain.runtime.lab.test/androidx.test.runner.AndroidJUnitRunner > "$evidence/remote-owner-preparation.txt" 2>&1
preparation_status=$?
set -e
test "$preparation_status" -ne 124
collect
python3 - "$evidence/network-owner-death-marker.json" "$owner_attempt" "$evidence/remote-owner-preparation.txt" <<'PYEXIT'
import json,os,subprocess,sys,time
from pathlib import Path
marker=json.loads(Path(sys.argv[1]).read_text());assert marker['actual_dns_entered'] is True and marker['source_commit']==os.environ['GITHUB_SHA']
assert marker['attempt']==sys.argv[2]
assert 'Process crashed' in Path(sys.argv[3]).read_text(), 'The deliberate process crash must be visible, not replaced by a missing result'
assert marker['pid']>0 and marker['owner_pid']>0 and marker['kernel_process_gone'] is False
for role,key in (('owner','owner_pid'),('broker','pid')):
    for attempt in range(50):
        result=subprocess.run(['adb','-s',os.environ['ANDROID_SERIAL'],'shell','cat',f'/proc/{marker[key]}/stat'],capture_output=True,text=True,timeout=5)
        if result.returncode and 'No such file or directory' in result.stdout+result.stderr:break
        time.sleep(.1)
    else:raise AssertionError('Independent kernel absence not confirmed for '+role)
print('Independent shell confirmed owner and stuck-DNS broker absent before recovery; intentional SIGKILL is not a timeout PASS')
PYEXIT
timeout 80 adb -s "$ANDROID_SERIAL" shell am instrument -w -r -e class 'com.thekain.runtime.lab.RemoteOwnerDeathTest#recoverOnlyAfterIndependentDriverConfirmsActualBrokerExit' com.thekain.runtime.lab.test/androidx.test.runner.AndroidJUnitRunner > "$evidence/remote-owner-recovery-instrumentation.txt"
python3 - "$evidence" <<'PYRECOVERED'
from pathlib import Path
import sys
from rewrite.experiments.android_native.bridge_report import raw
raw(Path(sys.argv[1]),'remote-owner-recovery-instrumentation.txt',1)
PYRECOVERED

# Disposable emulator only. This never uninstalls or changes the user's physical downloader.
adb -s "$ANDROID_SERIAL" install --no-streaming "$delivery/main-internal.apk"
adb -s "$ANDROID_SERIAL" install --no-streaming "$delivery/main-tests.apk"
if [ "$api" -ge 33 ]; then adb -s "$ANDROID_SERIAL" shell pm grant com.thekain.video.rebuild android.permission.POST_NOTIFICATIONS; fi
adb -s "$ANDROID_SERIAL" shell am instrument -w com.thekain.runtime.probe/.ProductBoundaryProbe > "$evidence/main-boundary.txt"
timeout 180 adb -s "$ANDROID_SERIAL" shell am instrument -w -r -e class com.thekain.runtime.lab.MainIsolatedAnalyzerTest com.thekain.video.rebuild.test/androidx.test.runner.AndroidJUnitRunner > "$evidence/main-analysis-instrumentation.txt"
python3 - "$evidence" <<'PYMAIN'
from pathlib import Path
import sys
from rewrite.experiments.android_native.bridge_report import raw
raw(Path(sys.argv[1]),'main-analysis-instrumentation.txt',4)
PYMAIN
timeout 100 adb -s "$ANDROID_SERIAL" shell am instrument -w -r -e class com.thekain.video.OwnedIsolatedUiTest -e listener com.thekain.video.UiFailureListener com.thekain.video.rebuild.test/androidx.test.runner.AndroidJUnitRunner > "$evidence/main-ui-instrumentation.txt"
adb -s "$ANDROID_SERIAL" exec-out screencap -p > "$evidence/main-isolated-ui.png"
collect
python3 rewrite/experiments/android_native/bridge_report.py --evidence "$evidence" --source "$(git rev-parse HEAD)" --api "$api" --pages "$pages"
bash rewrite/experiments/android_native/transfer-smoke.sh "$evidence" "$api" "$pages"
