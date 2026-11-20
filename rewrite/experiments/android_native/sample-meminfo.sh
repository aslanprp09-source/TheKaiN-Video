#!/usr/bin/env bash
set -Eeuo pipefail
driver="${1:?Instrumentation driver PID required}"
evidence="${2:?Evidence directory required}"
case "${3:-baseline}" in
  baseline) worker_names=(com.thekain.runtime.lab:python_runtime) ;;
  isolated) worker_names=(thekain-py-lab) ;;
  observer) worker_names=(com.thekain.runtime.lab:isolated_python com.thekain.runtime.lab:isolated_python:com.thekain.runtime.lab.IsolatedRuntimeService) ;;
  *) exit 1 ;;
esac
[[ "$driver" =~ ^[0-9]+$ ]]
count=0
last_pid=""
while kill -0 "$driver" 2>/dev/null; do
  # No worker between operations is normal; pipefail must not end sampling.
  pid=$(adb -s "$ANDROID_SERIAL" shell pidof "${worker_names[@]}" 2>/dev/null | tr -d '\r') || pid=""
  case "$pid" in ''|*[!0-9]*) ;; *)
    if [ "$count" -lt 32 ] && [ "$pid" != "$last_pid" ]; then
      # Native exec children have no Java ProcessRecord. Android prints their
      # per-PID MEMINFO heading only with explicit details (-a); keep the same
      # strict evidence requirement used for Java processes.
      snapshot="$evidence/meminfo-${3:-baseline}-$count.txt"
      # The system native-process tracker can initially miss a fresh exec PID.
      # Retry that PID until a complete, matching dump succeeds. Reuse the slot
      # for unsuccessful attempts, keeping at most 32 successful snapshots.
      if timeout 5 adb -s "$ANDROID_SERIAL" shell dumpsys meminfo -a "$pid" > "$snapshot" 2>/dev/null \
          && grep -Fq "MEMINFO in pid $pid " "$snapshot"; then
        last_pid="$pid"
        count=$((count+1))
      fi
    fi ;;
  esac
  sleep 0.25
done
