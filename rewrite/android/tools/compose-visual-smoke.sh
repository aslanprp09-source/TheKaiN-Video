#!/usr/bin/env bash
set -Eeuo pipefail
trap 'echo "::error title=M5 visual host::Failed on line $LINENO: $BASH_COMMAND"' ERR
serial="${ANDROID_SERIAL:-emulator-5554}"
artifacts="${1:?Pass artifact output directory}"
package=com.thekain.video.rebuild
mkdir -p "$artifacts"
cleanup() {
    adb -s "$serial" shell wm size reset >/dev/null
    adb -s "$serial" shell wm density reset >/dev/null
}
trap cleanup EXIT
adb -s "$serial" devices
for width in 360 412 600; do
    adb -s "$serial" shell wm size "${width}x1100"
    adb -s "$serial" shell wm density 160
    for theme in DARK LIGHT; do
        output="$artifacts/$width-$theme.txt"
        adb -s "$serial" shell am instrument -w -e class com.thekain.video.M5VisualTest#renderMatrix -e width_dp "$width" -e ui_theme "$theme" "$package.test/androidx.test.runner.AndroidJUnitRunner" > "$output"
        python3 - "$output" <<'PY'
import pathlib,sys
s=pathlib.Path(sys.argv[1]).read_text()
print(s)
if 'FAILURES!!!' in s or 'shortMsg=' in s:
    print('::error title=M5 visual fixture::'+s[-6500:].replace('%','%25').replace('\r','%0D').replace('\n','%0A'))
assert 'OK (1 test)' in s or 'OK (1 tests)' in s, 'Visual test did not execute'
assert 'FAILURES!!!' not in s and 'shortMsg=' not in s
print('::notice title=M5 visual matrix::'+pathlib.Path(sys.argv[1]).stem+': 7 native Compose screenshots, width asserted')
PY
        lower="$(echo "$theme" | tr '[:upper:]' '[:lower:]')"
        for page in home-empty home-ready downloads-running downloads-failed downloads-completed library settings; do
            name="$width-$lower-$page.png"
            adb -s "$serial" exec-out run-as "$package" cat "files/m5-evidence/$name" > "$artifacts/$name"
            test -s "$artifacts/$name"
        done
    done
done
adb -s "$serial" logcat -b crash -d > "$artifacts/crash-buffer.txt"
