#!/usr/bin/env bash
set -euo pipefail
serial="${ANDROID_SERIAL:-emulator-5554}"
artifacts="${1:?Pass artifact output directory}"
mkdir -p "$artifacts"
package=com.thekain.video.rebuild
runner="$package.test/androidx.test.runner.AndroidJUnitRunner"
run_phase() {
    local phase="$1" method="$2" klass="${3:-ProcessRestartTest}" output="$artifacts/process-$1.txt"
    adb -s "$serial" shell am instrument -w -e class "com.thekain.video.$klass#$method" -e process_phase "$phase" "$runner" > "$output"
    python3 - "$output" <<'PY'
import pathlib,sys
s=pathlib.Path(sys.argv[1]).read_text()
print(s)
if 'FAILURES!!!' in s or 'shortMsg=' in s:
    print('::error title=Android host test::' + s[-6500:].replace('%','%25').replace('\r','%0D').replace('\n','%0A'))
print('::notice title=Android host test::' + pathlib.Path(sys.argv[1]).stem + ': ' + ('1 passed' if 'OK (1 test' in s else 'failed'))
assert 'OK (1 test)' in s or 'OK (1 tests)' in s, 'Selected phase did not execute successfully'
assert 'FAILURES!!!' not in s and 'shortMsg=' not in s
PY
}
# Revoke outside instrumentation: Android may kill the app when permission changes.
adb -s "$serial" shell pm revoke "$package" android.permission.POST_NOTIFICATIONS
adb -s "$serial" shell pm set-permission-flags "$package" android.permission.POST_NOTIFICATIONS user-set user-fixed
run_phase notification-denied verifiedTransferWithoutNotificationPermission NotificationDeniedTest
adb -s "$serial" shell pm clear-permission-flags "$package" android.permission.POST_NOTIFICATIONS user-set user-fixed
adb -s "$serial" shell pm grant "$package" android.permission.POST_NOTIFICATIONS
run_phase seed seedFixture
adb -s "$serial" shell am force-stop "$package"
component="$(adb -s "$serial" shell cmd package resolve-activity --brief "$package" | tail -1 | tr -d '\r')"
adb -s "$serial" shell am start -n "$component"
# Wait for the Room-backed task UI after a new process, not a screen-only reset.
for attempt in $(seq 1 30); do
    adb -s "$serial" exec-out uiautomator dump /dev/tty > "$artifacts/ui-restart-top.xml"
    if python3 - "$artifacts/ui-restart-top.xml" <<'PY'
import pathlib,sys
s=pathlib.Path(sys.argv[1]).read_text()
raise SystemExit(0 if 'process-partial.mp4' in s else 1)
PY
    then break; fi
    sleep 1
done
adb -s "$serial" exec-out screencap -p > "$artifacts/android-restart-top.png"
# Derive any swipe from the UI tree bounds, as required by emulator QA.
coordinates="$(python3 - "$artifacts/ui-restart-top.xml" <<'PY'
import pathlib,sys,xml.etree.ElementTree as ET,re
s=pathlib.Path(sys.argv[1]).read_text(); a=s.find('<?xml'); b=s.find('</hierarchy>')+len('</hierarchy>')
r=ET.fromstring(s[a:b])
node=next(n for n in r.iter('node') if n.get('scrollable')=='true')
x1,y1,x2,y2=map(int,re.findall(r'\d+',node.get('bounds')))
print((x1+x2)//2, y1+int((y2-y1)*.8), (x1+x2)//2, y1+int((y2-y1)*.25))
PY
)"
read -r x1 y1 x2 y2 <<< "$coordinates"
adb -s "$serial" shell input swipe "$x1" "$y1" "$x2" "$y2" 400
adb -s "$serial" exec-out uiautomator dump /dev/tty > "$artifacts/ui-restart-jobs.xml"
adb -s "$serial" exec-out screencap -p > "$artifacts/android-restart-jobs.png"
run_phase verify verifyColdProcess
adb -s "$serial" logcat -b crash -d > "$artifacts/crash-buffer.txt"
