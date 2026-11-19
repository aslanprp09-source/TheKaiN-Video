#!/usr/bin/env bash
set -Eeuo pipefail
driver="${1:?Instrumentation driver PID required}"
evidence="${2:?Evidence directory required}"
case "${3:-baseline}" in
  baseline) worker_name=com.thekain.runtime.lab:python_runtime ;;
  isolated) worker_name=com.thekain.runtime.lab:isolated_python ;;
  *) exit 1 ;;
esac
[[ "$driver" =~ ^[0-9]+$ ]]
count=0
last_pid=""
while kill -0 "$driver" 2>/dev/null; do
  # No worker between operations is normal; pipefail must not end sampling.
  pid=$(adb -s "$ANDROID_SERIAL" shell pidof "$worker_name" 2>/dev/null | tr -d '\r') || pid=""
  case "$pid" in ''|*[!0-9]*) ;; *)
    if [ "$count" -lt 32 ] && [ "$pid" != "$last_pid" ]; then
      last_pid="$pid"
      timeout 5 adb -s "$ANDROID_SERIAL" shell dumpsys meminfo "$pid" > "$evidence/meminfo-${3:-baseline}-$count-$pid.txt" 2>/dev/null || true
      count=$((count+1))
    fi ;;
  esac
  sleep 0.25
done
