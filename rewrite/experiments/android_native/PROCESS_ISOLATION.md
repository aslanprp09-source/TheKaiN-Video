# Runtime process, cancellation and resource experiment

The subsequent standalone debug QA harness and physical ARM64/background protocol
are specified in [DEVICE_QA.md](DEVICE_QA.md). It adds four lifecycle tests plus
independent UID/owner-restart probes; all original 23 below remain. Phone results
remain NOT_RUN until an actual operator report is reviewed.

Issue #5; next implementation stage after PR34. Still only the separate
`com.thekain.runtime.lab` app. Product UI, `main`, release policy and publication
are outside this change; `production_ready=false`, `automatic_publication=false`.
The dependency versions, source pins and license inventory from README.md are
unchanged. New code uses only Java/Android/NDK platform APIs and own code.

## Execution and trust boundary

`NativeRuntime` is a Java-only supervisor. It binds the explicit, non-exported
`RuntimeService` in `com.thekain.runtime.lab:python_runtime`. Only `ChildRuntime`
loads CPython and JNI; it refuses initialization in any other process. Typed
AIDL uses fixed Parcelable schemas, bounded strings/enum values and no Bundles,
file descriptors, arbitrary Python code or URLs in returned diagnostics.

This Service deliberately shares its application's UID: assets and own CA
fixtures are private package files. Separate PID/address space contains hangs,
crashes and allocations; it is **not** Android isolated-UID/filesystem sandboxing.
Do not expose the lab to untrusted code, external callers or public source URLs.
An isolated-UID asset/transport design and a production background lifecycle
remain separate decisions before real-site integration.

One global lease admits one operation. A second client gets BUSY rather than
sharing a live interpreter. Each operation has an internally generated UUID,
monotonic deadline and fresh worker instance. The callback's actual Binder PID,
UID, exact `/proc` process name and start ticks establish the kill identity.
The parent PID can never be a kill target. Binder death is monitored; the worker
also links to its client's death. No `Py_Finalize` or reuse of interpreter state.

The worker remains quiescent after returning its one result. The parent removes
`BIND_AUTO_CREATE` before termination; early worker self-termination would make
Android schedule crash-restart backoff and delay subsequent operations. Service
callbacks return normally rather than killing themselves. Cleanup waits for both
kernel PID exit and disappearance of the worker/Service from Android's own state
before allowing another request; PID death alone is not sufficient readiness.
Cancellation is accepted only before terminal retirement. A successful cancel
request wins over a queued result. Late callbacks, mismatched IDs and malformed
messages cannot complete a subsequent request. Deadline, close, death and errors
unbind/terminate the owned worker. A result is delivered only after observed
process exit (or a confirmed never-started request). Cleanup has a 2.5s bound;
if exit cannot be confirmed, CLEANUP_FAILED poisons the lease rather than reusing
an uncertain worker. Normal successful work also retires its process.

## Limits outside Python

| Limit | Enforcement |
| --- | --- |
| Global operation deadline | Parent `elapsedRealtime` supervisor; max 30s including bind/asset setup; polling 40ms plus scheduling latency, bounded 2.5s termination observation |
| CPU | Kernel RLIMIT_CPU, based on consumed CPU plus remaining whole seconds and a small rounding margin; actual getrlimit values recorded |
| Virtual allocation | Kernel RLIMIT_AS = observed virtual size after library/transport setup + at most 256MiB; actual before/limit recorded |
| Resident memory | Independent parent reads `/proc/PID/status`; default threshold 384MiB (fault test 256MiB); kills at observed threshold violation |
| Direct Python sockets | Additional per-thread seccomp denies socket/connect/socketpair, inherited by Python-created threads; actual C socket call must fail EPERM before Python starts |
| HTTP requests | Prestarted Java transport thread, at most 12 requests/redirects, checked before each connection/request |
| Response bodies | Java read limit 512KiB/response, 2MiB aggregate; at most one sentinel byte for overflow detection, recorded as observed payload bytes |
| Headers/input/output | URL <=2048 ASCII chars, no userinfo/fragment, own CA <=16KiB, returned header text <=16KiB, JNI output <=64KiB, small typed IPC result |

The RSS threshold is sampled, **not** an instantaneous physical-memory hard cap.
Scheduler/GC pauses and allocation bursts can overshoot it; measured overshoot
is reported. RLIMIT_AS is a hard additional address-space ceiling, not a fixed
RSS ceiling: ART already reserves large virtual regions. No claim of cgroup-like
whole-app memory isolation or phone performance is made.

Only the dedicated Python execution thread receives the runtime seccomp filter
(no TSYNC). ART/Binder threads and global signal handlers are unchanged. The
prestarted Java HTTPS thread performs IO independently of Python, within the
same worker PID; terminating that PID ends blocked network IO as well. This is
not a general hostile-native-code sandbox. No extra runtime dependency or
prebuilt Python component is introduced.

The Java transport uses HTTPS and one numeric loopback origin/port, a private
fixture-only trust store, default hostname verification, no proxy, no redirects
outside the origin, no cookies, no compression, no media payloads or user headers.
Python gets bounded HTML bytes through a fixed native bridge. Real OpenSSL TLS
is retained as an additional MemoryBIO client/server handshake using on-device
fixture keys; release keys/signing credentials are never involved or recorded.

## Automated proof and measurements

All four original interpreter/HTTPS/unknown-track/wrong-CA/external-exec checks
remain, now through the worker. Nineteen additional tests cover actual Python
spin, accepted cancellation, wall deadline, native abort, memory exhaustion,
fresh restart, concurrent clients, close, stale/mismatched IPC, malformed Binder
parcels, input/output bounds, Java request/body budgets, redirect refusal,
direct network denial, parent PID/JNI guard and installed Service manifest.
Every test checks the main process/Looper where relevant; no CPython mapping is
permitted in the main process. Recovery performs real owned-HTTPS yt-dlp analysis
in a fresh process and checks Python invocation count=1.

The existing default-off native workflow builds both ABIs from source and runs
all 23 tests on real hosted x86_64 emulators API26/29/35 (4KiB) and API36 (16KiB,
official revision7). Existing four required checks and all 41 product tests stay
in place. API35 ps16k revision5's known system_server crash remains documented;
it is not relabelled as a stable result.

`device-smoke.sh` records each operation's parent-owned JSON measurements,
independent `dumpsys meminfo` snapshots (one per observed child PID), actual
SDK/ABI/page size, image fingerprint and system_server stability. The report
validator requires fault coverage, confirmed death, distinct parent/child PID,
real RSS samples, kernel limits and bounded network counters; incomplete or
fabricated evidence fails CI. `resource-report.json` labels sampled success/fault
RSS and termination latencies. Cancellation-to-confirmed-exit and retirement-to-exit
are measured separately from the entire operation's startup/analysis duration.
These are emulator observations, not exact peak
PSS, a leak diagnosis or a benchmark. No traces/heap dumps are needed to claim
only this bounded flow. ARM64 is compiled/static-checked, never claimed executed.

Reproduce with README.md build commands, then dispatch `rewrite-ci.yml` on the
separate reviewed branch with `native_runtime_lab=true` and build/sign/verify
flags false. Inspect the complete matrix and resource reports for the exact
HEAD. Fresh independent KaiN1One approval is required before normal merge.
