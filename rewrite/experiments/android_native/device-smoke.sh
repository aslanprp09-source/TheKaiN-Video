#!/usr/bin/env bash
set -Eeuo pipefail
api="${1:?API required}"; image="${2:?Image required}"; pages="${3:?Page size required}"
delivery="${4:?Same-run delivery directory required}"; evidence="${5:?Evidence required}"
case "$api/$image/$pages" in
  26/google_apis/4096|29/google_apis/4096|35/google_apis/4096|35/google_apis_ps16k/16384) ;;
  *) echo 'Unreviewed emulator matrix'; exit 1 ;;
esac
mkdir -p "$evidence"
export ANDROID_SERIAL=emulator-5554
export ANDROID_AVD_HOME="$RUNNER_TEMP/native-lab-avd"
export PATH="$ANDROID_HOME/platform-tools:$PATH"
cleanup() {
  result=$?
  adb -s "$ANDROID_SERIAL" logcat -d > "$evidence/logcat.txt" 2>/dev/null || true
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
python3 rewrite/experiments/android_native/delivery.py verify --folder "$delivery"
mkdir -p "$ANDROID_AVD_HOME"
echo no | "$sdk/avdmanager" create avd -n NativeLab -k "system-images;android-$api;$image;x86_64" --device pixel_2
"$ANDROID_HOME/emulator/emulator" -avd NativeLab -no-snapshot -no-window -no-audio -no-boot-anim -gpu swiftshader -memory 2048 -cores 2 -port 5554 > "$evidence/emulator.txt" 2>&1 &
timeout 180 adb -s "$ANDROID_SERIAL" wait-for-device
for attempt in $(seq 1 180); do
  if [ "$(adb -s "$ANDROID_SERIAL" shell getprop sys.boot_completed | tr -d '\r')" = 1 ]; then break; fi
  sleep 2
done
test "$(adb -s "$ANDROID_SERIAL" shell getprop sys.boot_completed | tr -d '\r')" = 1
adb -s "$ANDROID_SERIAL" shell getprop ro.build.version.sdk | tr -d '\r' > "$evidence/api.txt"
adb -s "$ANDROID_SERIAL" shell getconf PAGE_SIZE | tr -d '\r' > "$evidence/page-size.txt"
adb -s "$ANDROID_SERIAL" shell getprop ro.product.cpu.abi | tr -d '\r' > "$evidence/abi.txt"
test "$(cat "$evidence/api.txt")" = "$api"
test "$(cat "$evidence/page-size.txt")" = "$pages"
test "$(cat "$evidence/abi.txt")" = x86_64
adb -s "$ANDROID_SERIAL" install -r -t "$delivery/lab.apk"
adb -s "$ANDROID_SERIAL" install -r -t "$delivery/tests.apk"
timeout 180 adb -s "$ANDROID_SERIAL" shell am instrument -w -e class com.thekain.runtime.lab.NativeLabTest com.thekain.runtime.lab.test/androidx.test.runner.AndroidJUnitRunner > "$evidence/instrumentation.txt"
python3 - "$evidence/instrumentation.txt" "$api" "$pages" <<'PY'
from pathlib import Path
import re,sys,json
text=Path(sys.argv[1]).read_text();print(text)
assert re.search(r'^OK \(4 tests?\)\s*$',text,re.M), 'All four native tests must execute successfully'
assert not any(s in text for s in ('FAILURES!!!','shortMsg=','INSTRUMENTATION_FAILED')), 'Native instrumentation failed'
print('::notice::'+json.dumps({'native_JNI_smoke':'PASS','api':int(sys.argv[2]),'actual_page_size':int(sys.argv[3]),'tests':4,'production_ready':False}))
PY
