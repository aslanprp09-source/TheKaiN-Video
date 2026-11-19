# Isolated Android UID experiment — issue #37

This is a second backend inside the separate lab APK, after PR35/36. The original
shared-UID backend and all 23 native + 4 lifecycle tests remain as a baseline.
No product UI, public-site/YouTube adapter, signing, release or QA gate is changed.
`production_ready=false`, `automatic_publication=false`.

## Boundary and capability transport

`IsolatedRuntimeService` is explicit, non-exported, `isolatedProcess=true`, and
has its own Android-assigned UID. The main process never loads Python/JNI.
The service accepts only the installed application's UID; typed AIDL replaces
Bundles, arbitrary Python strings, paths and user-selected sources. Operations
retain owner-generated UUID capabilities, monotonic deadlines and one process
per operation. A different PID/UID, a fixed private Service binding, actual
Binder calling credentials and kernel identity establish the peer. Isolated
UIDs are ephemeral, not application permission/storage identities.

The owner reads every Python/data asset directly from the installed APK and
checks its pinned inventory size/SHA-256. It creates a bounded, uncompressed ZIP
with collision/path rejection, opens it read-only and unlinks its private name.
Only that FD, its size/digest and two read-only ephemeral owned TLS fixture files
cross IPC. No writable FD, directory capability, app data path or release secret
is granted. The isolated bootstrap rechecks size/digest before Python starts.
Reopening `/proc/self/fd/N` still checks the source inode's DAC permissions:
therefore the isolated bootstrap copies bytes from the received FD into its own
anonymous memfd and irreversibly seals WRITE/GROW/SHRINK/SEAL. CPython zipimport
reads this sealed FD; executable stdlib extensions remain in Android's installed
native-library directory. There is no writable extracted Python tree in the UID.
No new third-party dependency/version/license is introduced; README pins remain.

The UID itself lacks INTERNET and cannot open/read/write the owner's canary.
These OS denials are tested before the additional Python socket seccomp fence.
Python also attempts owner-file access and direct sockets. This is an Android
UID/SELinux sandbox, not a claim against kernel/ART/native-library exploits.
The APK/native libraries and OS-readable files are not confidential to the UID.

HTTPS runs in the trusted owner through a per-operation typed capability broker:
numeric loopback, only the owned fixture origin/port, HTTPS, fixed GET/HEAD,
private test CA, no proxies/cookies/user headers or public URLs. Request/body/
aggregate limits remain 12 / 512KiB / 2MiB, output <=64KiB. One fetch at a time;
revoked, late, wrong-capability, out-of-origin and forbidden-method requests are
rejected before network budget is consumed. No media transfer is authorized.

## Monitoring and termination without owner privileges

The shared-UID `/proc/PID` and direct `killProcess` design cannot be reused across
UIDs. Before Python, the worker opens read-only `/proc/self/stat` and `status`
descriptors and passes them to the owner. The owner verifies exact kernel
`/proc/<Binder PID>/{stat,status}` FD links, regular-file type, kernel fdinfo
read-only flags, actual UID/PID/start ticks and reads the kernel seq-files itself.
The worker cannot replace the records with a pipe, memfd or supplied counters.
These handles name the original task, not a future process that reuses its PID.
Any inability to authenticate/sample fails closed; no self-reported RSS fallback.
CPU uses actual SC_CLK_TCK. RSS is sampled every 40ms, not an instantaneous hard
memory cap; overshoot is retained. RLIMIT_AS/CPU remain additional kernel limits.

Cancellation/deadline revoke the broker, remove the final BIND_AUTO_CREATE and
stop the private service. Android's isolated-process lifecycle performs the
forced kill. No cross-UID signal permission, hidden API, root or relaxed SELinux
is used. The worker deliberately has **no cooperative onDestroy self-kill**:
tests block its main thread while Python spins, then require OS termination.
The owner requires original-kernel-task death, Binder death and ServiceRecord
retirement before releasing the lease. Failure within 2.5s gives CLEANUP_FAILED
and poisons the lease; uncertain state never authorizes a fresh operation.
Owner Binder death also makes the isolated worker self-terminate as a fallback.
The OS mechanism must be proven on each platform; AOSP source is not a timing SLA.

An AtomicFile reservation survives owner death. Losing the owner also loses its
authenticated kernel descriptors; ordinary app process listings hide other UIDs
and absence of a ServiceRecord alone cannot prove task death. A new owner therefore
fails closed instead of resetting its static lease and silently proceeding. A
strictly increased public Android BOOT_COUNT permits clearance after a real OS
reboot, when no old task can remain. Corrupt/unreadable state stays blocked.
Automatic recovery after owner death without reboot is **not implemented**;
same-owner worker crash/cancel/deadline recovery is tested. This remains a lab
limitation before product integration, not a claim that old processes are dead.

Public AOSP15 references: [OomAdjuster](https://github.com/aosp-mirror/platform_frameworks_base/blob/android15-release/services/core/java/com/android/server/am/OomAdjuster.java)
(isolated process without running services),
[ActiveServices](https://github.com/aosp-mirror/platform_frameworks_base/blob/android15-release/services/core/java/com/android/server/am/ActiveServices.java)
(service removal before teardown callback),
[ActivityManagerService](https://github.com/aosp-mirror/platform_frameworks_base/blob/android15-release/services/core/java/com/android/server/am/ActivityManagerService.java)
(cross-UID getProcessMemoryInfo restrictions). No privileged API is assumed.

## Verification protocol and current status

The existing default-off read-only native CI builds both ABIs from pinned source,
runs original 27 tests and adds 22 isolated/backend/SDK-free UI tests on API26/29/35 (4KiB) and
API36 official rev7 (16KiB). Negative tests cover owner file read/write, missing
network permission, actual OS socket denial, read-only/sealed resources, forged
proc FDs, tampered resource hash, malformed reply allocation, invalid capability/
method/origin, output/request/body limits, wrong CA, redirection, stale result,
busy clients, close, native crash, memory fault and blocked-main cancellation/
deadline with clean real-HTTPS restart. Independent external-app binding checks
cover both services. Kernel CPU/RSS, independent shell meminfo and cleanup
latencies are retained in `isolated-{evidence.jsonl,resource-report.json}`.

Implementation-stage status: local JNI compilation x86_64/ARM64 PASS; Android
matrix and new physical ARM64 isolated-UID execution **NOT_RUN** until linked
run/device evidence is added. PR36's Samsung same-UID reports are historical,
not evidence of this new boundary. Compile success is not execution.

Initial CI154 (44522260214, source 0e74795) built both native ABIs from source,
but lab Gradle dependency resolution failed with Maven Central HTTP429 before
APK assembly/device tests. Product API35/29 emulator preparation separately
failed with Ubuntu mirror timeout/HTTP503 (zero tests). Product Android JVM/Lint,
legacy API26 and 111 host tests passed. PR CI155 separately exposed an existing
Linux procfs test-observer race: ESRCH at read after task exit; ENOENT alone was
caught. The test now recognizes both kernel death errors, with a regression that
still rejects permission errors/empty records. This is separate from open
Windows callback race #39. Infra reruns are not code or UI-stability fixes.

## SDK-free ARM64 procedure

The debug-only **TheKaiN UID QA** launcher is separate from the retained
shared-UID QA launcher. It uses a separate owned HTTPS fixture directory so
certificate files do not interfere. Run only one lab at a time. No SDK or ADB
is required: press the UID/JNI probe, then the fixed safety/recovery suite,
then save `TheKaiN-isolated-uid-qa-report.json` through Android's document picker.
The suite includes actual probe/OpenSSL, Python file/network denials, blocked
main cancellation/deadline, native crash, sampled memory limit, tampered inputs
and five fresh own-HTTPS analyses. Manual hang/cancel and Activity recreation
are also available. It accepts no external-intent operations/URLs/code.

Lab versionCode=3 is distinct from product APK89 versionCode=3. CI debug signing
is not release signing: compare the exact same-run source/APK/certificate receipt.
A different CI debug certificate can require exporting prior evidence and
uninstalling **only the separate runtime lab**, not the user's downloader.
No actual installation or physical execution is claimed by providing a harness.
Reports are operator evidence requiring review, never cryptographic device
attestation or a change to the APK89 gate. Export includes device identity;
review before sharing. No certificates' private bytes, URLs or Python are exported.

Reproduce with the README source-build/assemble commands and default-off dispatch
`native_runtime_lab=true`, build/sign/verify flags false on this task branch.
All 41 product API35 tests, Windows/JVM/Lint and CI-policy remain mandatory.
Record infrastructure failures separately; reruns alone are not code fixes.

Separate OPEN follow-ups: [APK89 independent review #38](https://github.com/aslanprp09-source/TheKaiN-Video/issues/38),
[Windows callback race #39](https://github.com/aslanprp09-source/TheKaiN-Video/issues/39),
[prolonged background #40](https://github.com/aslanprp09-source/TheKaiN-Video/issues/40),
[deep Doze #41](https://github.com/aslanprp09-source/TheKaiN-Video/issues/41).
No production memory budget, deep-Doze guarantee or release eligibility follows.
