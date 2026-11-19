#!/usr/bin/env bash
set -Eeuo pipefail
api="${1:?API required}"; image="${2:?Image required}"; pages="${3:?Page size required}"
delivery="${4:?Same-run delivery directory required}"; evidence="${5:?Evidence required}"
case "$api/$image/$pages" in
  26/google_apis/4096|29/google_apis/4096|35/google_apis/4096|36/google_apis_ps16k/16384) ;;
  *) echo 'Unreviewed emulator matrix'; exit 1 ;;
esac
mkdir -p "$evidence"
export ANDROID_SERIAL=emulator-5554
export ANDROID_AVD_HOME="$RUNNER_TEMP/native-lab-avd"
export PATH="$ANDROID_HOME/platform-tools:$PATH"
cleanup() {
  result=$?
  adb -s "$ANDROID_SERIAL" logcat -d > "$evidence/logcat.txt" 2>/dev/null || true
  adb -s "$ANDROID_SERIAL" logcat -b crash -d > "$evidence/crash.txt" 2>/dev/null || true
  adb -s "$ANDROID_SERIAL" shell dumpsys activity exit-info com.thekain.runtime.lab > "$evidence/exit-info.txt" 2>/dev/null || true
  if [ "$result" -ne 0 ]; then
    # Full window dump includes both mCurrentFocus and mFocusedApp on all matrix APIs.
    timeout 8 adb -s "$ANDROID_SERIAL" shell dumpsys window > "$evidence/failure-window.txt" 2>/dev/null || true
    timeout 8 adb -s "$ANDROID_SERIAL" shell dumpsys activity activities > "$evidence/failure-activities.txt" 2>/dev/null || true
    timeout 10 adb -s "$ANDROID_SERIAL" shell uiautomator dump /sdcard/thekain-uid-failure.xml > "$evidence/failure-hierarchy-command.txt" 2>&1 || true
    timeout 8 adb -s "$ANDROID_SERIAL" exec-out cat /sdcard/thekain-uid-failure.xml > "$evidence/failure-ui.xml" 2>/dev/null || true
    timeout 8 adb -s "$ANDROID_SERIAL" exec-out screencap -p > "$evidence/failure-screen.png" 2>/dev/null || true
    if [ -f "$evidence/instrumentation.txt" ]; then tail -120 "$evidence/instrumentation.txt"; fi
    if [ -f "$evidence/isolated-instrumentation.txt" ]; then tail -160 "$evidence/isolated-instrumentation.txt"; fi
    if [ -f "$evidence/isolated-bootstrap.txt" ]; then tail -100 "$evidence/isolated-bootstrap.txt"; fi
    tail -120 "$evidence/crash.txt"
    tail -100 "$evidence/exit-info.txt"
    python3 - "$evidence/logcat.txt" <<'PY'
from pathlib import Path
import sys
lines=Path(sys.argv[1]).read_text(errors='replace').splitlines()
terms=('thekain.runtime.lab','lmkd','lowmemorykiller','python.stderr','AndroidRuntime','Fatal signal','TheKaiNRuntime','TheKaiNIsolated','avc: denied','transaction failed','Failed reply')
for line in [s for s in lines if any(term in s for term in terms)][-120:]: print(line)
PY
  fi
  adb -s "$ANDROID_SERIAL" shell am force-stop com.thekain.runtime.lab >/dev/null 2>&1 || true
  exit "$result"
}
trap cleanup EXIT
test -e /dev/kvm
sudo chmod a+rw /dev/kvm
sudo apt-get update
sudo apt-get install --yes --no-install-recommends libpulse0 libglu1-mesa libxkbcommon-x11-0 libxcb-cursor0 libnss3
sdk="$ANDROID_HOME/cmdline-tools/latest/bin"
"$sdk/sdkmanager" 'platform-tools' 'build-tools;35.0.0' 'emulator' "system-images;android-$api;$image;x86_64"
properties="$ANDROID_HOME/system-images/android-$api/$image/x86_64/source.properties"
cp "$properties" "$evidence/image-source.properties"
if [ "$api" = 36 ]; then
  # Official API35 ps16k rev5 crashes system_server (DisplayFoldController).
  # The additional 16KiB target is the official API36 rev7 image, never a 4KiB fallback.
  python3 - "$properties" <<'PYREV'
from pathlib import Path
import sys
values={k.strip():v.strip() for line in Path(sys.argv[1]).read_text().splitlines() if '=' in line for k,v in [line.split('=',1)]}
assert values.get('Pkg.Revision','').strip()=='7', 'Unreviewed 16KiB image revision'
PYREV
fi
python3 rewrite/experiments/android_native/delivery.py verify --folder "$delivery"
mkdir -p "$ANDROID_AVD_HOME"
echo no | "$sdk/avdmanager" create avd -n NativeLab -k "system-images;android-$api;$image;x86_64" --device pixel_2
memory=2048
if [ "$pages" = 16384 ]; then memory=4096; fi
"$ANDROID_HOME/emulator/emulator" -avd NativeLab -no-snapshot -no-window -no-audio -no-boot-anim -gpu swiftshader -memory "$memory" -cores 2 -port 5554 > "$evidence/emulator.txt" 2>&1 &
timeout 180 adb -s "$ANDROID_SERIAL" wait-for-device
for attempt in $(seq 1 180); do
  if [ "$(adb -s "$ANDROID_SERIAL" shell getprop sys.boot_completed | tr -d '\r')" = 1 ]; then break; fi
  sleep 2
done
test "$(adb -s "$ANDROID_SERIAL" shell getprop sys.boot_completed | tr -d '\r')" = 1
adb -s "$ANDROID_SERIAL" shell getprop ro.build.version.sdk | tr -d '\r' > "$evidence/api.txt"
if [ "$api" = 26 ]; then
  # API26 has no shell getconf. Read actual AT_PAGESZ from the 64-bit process auxv.
  adb -s "$ANDROID_SERIAL" exec-out cat /proc/self/auxv > "$evidence/auxv.bin"
  python3 - "$evidence/auxv.bin" > "$evidence/page-size.txt" <<'PY'
import struct,sys
from pathlib import Path
raw=Path(sys.argv[1]).read_bytes()
assert len(raw)%16==0 and len(raw)<16384, 'Invalid 64-bit auxv'
values=[v for key,v in struct.iter_unpack('<QQ',raw) if key==6]
assert len(values)==1, 'AT_PAGESZ missing or duplicated'
print(values[0])
PY
else
  adb -s "$ANDROID_SERIAL" shell getconf PAGE_SIZE | tr -d '\r' > "$evidence/page-size.txt"
fi
adb -s "$ANDROID_SERIAL" shell getprop ro.product.cpu.abi | tr -d '\r' > "$evidence/abi.txt"
test "$(cat "$evidence/api.txt")" = "$api"
test "$(cat "$evidence/page-size.txt")" = "$pages"
test "$(cat "$evidence/abi.txt")" = x86_64
adb -s "$ANDROID_SERIAL" shell getprop ro.build.fingerprint > "$evidence/fingerprint.txt"
adb -s "$ANDROID_SERIAL" shell pidof system_server | tr -d '\r' > "$evidence/system-server-before.txt"
test -s "$evidence/system-server-before.txt"
adb -s "$ANDROID_SERIAL" install -r -t "$delivery/lab.apk"
adb -s "$ANDROID_SERIAL" install -r -t "$delivery/tests.apk"
# A passing run still executes all original 27 and all additional 24 below.
timeout 80 adb -s "$ANDROID_SERIAL" shell am instrument -w -e class 'com.thekain.runtime.lab.IsolatedRuntimeTest#separateUidInterpreterSealedResourcesAndOpenSsl' com.thekain.runtime.lab.test/androidx.test.runner.AndroidJUnitRunner > "$evidence/isolated-bootstrap.txt"
python3 - "$evidence/isolated-bootstrap.txt" <<'PYBOOT'
from pathlib import Path
import re,sys
text=Path(sys.argv[1]).read_text();print(text)
assert re.search(r'^OK \(1 test\)\s*$',text,re.M), 'Isolated UID real JNI/IPC bootstrap failed; full matrix is NOT passed'
assert not any(s in text for s in ('FAILURES!!!','shortMsg=','INSTRUMENTATION_FAILED'))
PYBOOT
timeout 600 adb -s "$ANDROID_SERIAL" shell am instrument -w -e class com.thekain.runtime.lab.NativeLabTest,com.thekain.runtime.lab.RuntimeProcessTest,com.thekain.runtime.lab.LabLifecycleTest com.thekain.runtime.lab.test/androidx.test.runner.AndroidJUnitRunner > "$evidence/instrumentation.txt" &
driver=$!
bash rewrite/experiments/android_native/sample-meminfo.sh "$driver" "$evidence" &
sampler=$!
result=0
wait "$driver" || result=$?
kill "$sampler" 2>/dev/null || true
wait "$sampler" 2>/dev/null || true
adb -s "$ANDROID_SERIAL" exec-out run-as com.thekain.runtime.lab cat files/process-evidence.jsonl > "$evidence/process-evidence.jsonl"
python3 rewrite/experiments/android_native/process_report.py --evidence "$evidence" --api "$api" --pages "$pages"
test "$result" = 0
adb -s "$ANDROID_SERIAL" shell pidof system_server | tr -d '\r' > "$evidence/system-server-after.txt"
cmp "$evidence/system-server-before.txt" "$evidence/system-server-after.txt"
adb -s "$ANDROID_SERIAL" logcat -b crash -d > "$evidence/crash.txt"
python3 - "$evidence/crash.txt" <<'PYSYSTEM'
from pathlib import Path
import sys
assert 'FATAL EXCEPTION IN SYSTEM PROCESS' not in Path(sys.argv[1]).read_text(errors='replace'), 'Android system_server crashed'
PYSYSTEM
python3 - "$evidence/instrumentation.txt" "$api" "$pages" <<'PY'
from pathlib import Path
import re,sys,json
text=Path(sys.argv[1]).read_text();print(text)
assert re.search(r'^OK \(27 tests?\)\s*$',text,re.M), 'All original 23 plus 4 lifecycle tests must execute successfully'
assert not any(s in text for s in ('FAILURES!!!','shortMsg=','INSTRUMENTATION_FAILED')), 'Native instrumentation failed'
print('::notice::'+json.dumps({'native_JNI_smoke':'PASS','api':int(sys.argv[2]),'actual_page_size':int(sys.argv[3]),'tests':27,'production_ready':False}))
PY
python3 rewrite/experiments/android_native/lifecycle-smoke.py --evidence "$evidence" --delivery "$delivery"
timeout 600 adb -s "$ANDROID_SERIAL" shell am instrument -w -e class com.thekain.runtime.lab.IsolatedRuntimeTest,com.thekain.runtime.lab.IsolatedQaTest com.thekain.runtime.lab.test/androidx.test.runner.AndroidJUnitRunner > "$evidence/isolated-instrumentation.txt" &
driver=$!
bash rewrite/experiments/android_native/sample-meminfo.sh "$driver" "$evidence" isolated &
sampler=$!
result=0
wait "$driver" || result=$?
kill "$sampler" 2>/dev/null || true
wait "$sampler" 2>/dev/null || true
adb -s "$ANDROID_SERIAL" exec-out run-as com.thekain.runtime.lab cat files/isolated-evidence.jsonl > "$evidence/isolated-evidence.jsonl"
test "$result" = 0
adb -s "$ANDROID_SERIAL" exec-out run-as com.thekain.runtime.lab cat files/isolated-qa-report.json > "$evidence/isolated-qa-report.json" 2>/dev/null || true
for label in entry suite-result recreated-cancelled; do
  adb -s "$ANDROID_SERIAL" exec-out run-as com.thekain.runtime.lab cat "files/isolated-qa-evidence/$label.png" > "$evidence/isolated-qa-$label.png" 2>/dev/null || true
done
python3 rewrite/experiments/android_native/isolated_report.py --evidence "$evidence" --api "$api" --pages "$pages"
adb -s "$ANDROID_SERIAL" shell pidof system_server | tr -d '\r' > "$evidence/system-server-after-isolated.txt"
cmp "$evidence/system-server-before.txt" "$evidence/system-server-after-isolated.txt"
