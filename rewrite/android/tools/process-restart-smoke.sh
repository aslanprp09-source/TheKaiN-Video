#!/usr/bin/env bash
set -Eeuo pipefail
trap 'echo "::error title=M4 adb smoke::Failed command on line $LINENO: $BASH_COMMAND"' ERR
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
dump_ui() {
    adb -s "$serial" shell uiautomator dump /data/local/tmp/thekain-m4-ui.xml > "$artifacts/uiautomator-status.txt"
    adb -s "$serial" exec-out cat /data/local/tmp/thekain-m4-ui.xml > "$1"
}
# Compose starts on Home. Navigate using the actual accessibility tree bounds.
for attempt in $(seq 1 30); do
    dump_ui "$artifacts/ui-restart-home.xml"
    navigation="$(python3 - "$artifacts/ui-restart-home.xml" <<'PYNAV'
import pathlib,sys,xml.etree.ElementTree as ET,re
s=pathlib.Path(sys.argv[1]).read_text(); a=s.find('<?xml'); b=s.find('</hierarchy>')+len('</hierarchy>')
if a < 0: sys.exit(0)
r=ET.fromstring(s[a:b])
n=next((n for n in r.iter('node') if n.get('resource-id')=='nav_downloads'), None)
if n is not None:
    x1,y1,x2,y2=map(int,re.findall(r'\d+',n.get('bounds')))
    print((x1+x2)//2,(y1+y2)//2)
PYNAV
)"
    if [ -n "$navigation" ]; then break; fi
    sleep 1
done
test -n "$navigation"
read -r nav_x nav_y <<< "$navigation"
adb -s "$serial" shell input tap "$nav_x" "$nav_y"
# Wait for the Room-backed task UI after a new process, not a screen-only reset.
for attempt in $(seq 1 30); do
    dump_ui "$artifacts/ui-restart-top.xml"
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
assert 'process-partial.mp4' in s, 'Recovered task is missing from the actual Activity UI'
node=next((n for n in r.iter('node') if n.get('scrollable')=='true'), None)
if node is None: sys.exit(0) # Both rows can already fit; no gesture is needed.
x1,y1,x2,y2=map(int,re.findall(r'\d+',node.get('bounds')))
print((x1+x2)//2, y1+int((y2-y1)*.8), (x1+x2)//2, y1+int((y2-y1)*.25))
PY
)"
if [ -n "$coordinates" ]; then
    read -r x1 y1 x2 y2 <<< "$coordinates"
    adb -s "$serial" shell input swipe "$x1" "$y1" "$x2" "$y2" 400
fi
dump_ui "$artifacts/ui-restart-jobs.xml"
adb -s "$serial" exec-out screencap -p > "$artifacts/android-restart-jobs.png"
run_phase verify verifyColdProcess
adb -s "$serial" shell rm -f /data/local/tmp/thekain-m4-ui.xml
adb -s "$serial" logcat -b crash -d > "$artifacts/crash-buffer.txt"
