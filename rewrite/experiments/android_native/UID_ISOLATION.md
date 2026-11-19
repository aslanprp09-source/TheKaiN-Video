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
Retirement explicitly cancels the owner-side Future and disconnects active TLS,
then observes the IO task and broker callback actually finish. Killing Python
alone is insufficient now that network IO lives in the owner. A blocked owned
HTTPS response/cancel/fresh-analysis regression enforces this cleanup boundary.

## Monitoring and termination without owner privileges

The shared-UID `/proc/PID` and direct `killProcess` design cannot be reused across
UIDs. Android SELinux additionally prohibits passing the isolated task's proc
FDs directly into `untrusted_app`: CI166 API26 records an actual enforcing AVC
`untrusted_app -> isolated_app:file read` for `/proc/<worker>/stat`. Binder
reported DeadObjectException although the owner was alive. Retaining callbacks
cannot fix that policy boundary; no policy/permissions are relaxed.

A third process, `ResourceObserverService`, is non-exported and also
`isolatedProcess=true`: a **different Linux UID** from both owner and Python,
but Android's same `isolated_app` SELinux domain. It executes only pinned Java,
never Python/JNI. Owner-only `IResourceObserver` prepares it; the worker receives
only a single-use `IKernelSink`, with no monitor configuration/stop operation.
Actual Binder credentials and the operation nonce authenticate both peers; the
owner fixes the expected worker PID/UID before descriptor attachment. Before
Python, the worker opens read-only `/proc/self/stat` and `status` and passes them
directly to the observer, never the owner. The observer verifies exact kernel
`/proc/<Binder PID>/{stat,status}` FD links, regular-file type, kernel fdinfo
read-only flags, UID/PID/start ticks and reads the kernel seq-files itself.
The worker cannot replace records with a pipe, memfd or supplied counters.
These handles name the original task, not a future process that reuses its PID.
The observer cannot read owner storage or use the owner's network permission;
its separate UID prevents worker signals/ptrace under Linux DAC. No Python
resource or HTTPS capability is given to the observer. JNI's exact-process guard
is deliberately exercised there, and native-map absence is checked.

A final, separate observer-loss test deliberately removes the Android binding
while Python spins. It requires both actual OS retirements and a responsive main,
but refuses to claim kernel death or reuse without the lost independent proof.
The durable refusal survives construction of a replacement supervisor; no test
reset erases that uncertainty. It runs after the complete successful suite.

Only typed kernel snapshots cross to the owner. They carry monotonic sequence,
measurement time, task identity and observer RSS/CPU; actual sender credentials
must match the independently bound observer. Missing/error records fail closed;
no self-reported Python RSS fallback. A stale observer (>1s) fails IPC; transient
exit-record errors have only the existing 120ms grace, with no OK result allowed.
Synchronous observer setup runs outside the deadline thread and owner locks.
CPU uses actual SC_CLK_TCK. RSS is sampled every 40ms, not an instantaneous hard
memory cap; overshoot is retained. RLIMIT_AS/CPU remain additional kernel limits.
The ZIP input byte count is recorded separately: owner file cache and isolated
sealed-memfd backing pages are not fully accounted by process RSS. This bounded
extra storage must be considered before choosing any physical-device budget.

Cancellation/deadline revoke the broker, remove the final BIND_AUTO_CREATE and
stop the private service. Android's isolated-process lifecycle performs the
forced kill. No cross-UID signal permission, hidden API, root or relaxed SELinux
is used. The worker deliberately has **no cooperative onDestroy self-kill**:
tests block its main thread while Python spins, then require OS termination.
The observer remains bound until original-kernel-task death is confirmed; the
owner additionally requires worker Binder death and ServiceRecord removal.
It then retires the Java observer and requires its rooted Binder death and
ServiceRecord removal, plus ended owner transport/configuration callbacks,
before releasing the lease. Losing the observer before kernel death durably poisons the
lease rather than trusting the Python worker or pretending cleanup. Failure within 2.5s gives CLEANUP_FAILED
and poisons the lease; uncertain state never authorizes a fresh operation.
Owner Binder death also makes the isolated worker self-terminate as a fallback.
The OS mechanism must be proven on each platform; AOSP source is not a timing SLA.
Deadline supervision also requires Android to schedule the owner. Cached-process
freezing/deep Doze can delay the Handler until wake; no real-time background SLA
is claimed. These cases remain separate open tasks, not passed by foreground CI.

An AtomicFile reservation survives owner death. Losing the owner also loses its
authenticated observer channel; ordinary app process listings hide other UIDs
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
runs original 27 tests and adds 24 isolated/backend/SDK-free UI/regression tests on API26/29/35 (4KiB) and
API36 official rev7 (16KiB). Negative tests cover owner file read/write, missing
network permission, actual OS socket denial, read-only/sealed resources, forged
proc FDs, tampered resource hash, malformed reply allocation, invalid capability/
method/origin, output/request/body limits, wrong CA, redirection, stale result,
busy clients, close, native crash, memory fault and blocked-main cancellation/
deadline with clean real-HTTPS restart. Independent external-app binding checks
cover all three services. Kernel CPU/RSS, independent shell meminfo and cleanup
latencies are retained in `isolated-{evidence.jsonl,resource-report.json}`.

Implementation-stage status: local JNI compilation x86_64/ARM64 PASS; Android
isolated-UID matrix acceptance is **BLOCKED** until a fresh full matrix passes.
New physical ARM64 isolated-UID execution is **NOT_RUN**. PR36's Samsung same-UID reports are historical,
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

CI160 (44527432744, source 8222b62) completed both-ABI source/APK/Lint/receipt
verification. Original 27 tests passed on API26/29/35; API36 exposed the existing
manual-SIGKILL classification race (expected WORKER_DIED, got IPC_ERROR). Proc
records can become unavailable before death notification; both supervisors now
allow at most 120ms of unknown state before failing IPC, while rejecting OK
results during that interval. Actual kernel/Binder death still establishes death;
no missing/permission-denied record is silently treated as a dead process.
Three repeated validated kills with real-HTTPS recovery supplement the unchanged
strict lifecycle assertion. This is a source fix, not a claimed rerun cure.

The first isolated runs on API26/29/35 failed before Python: Android names the
process `:isolated_python:<full Service component>`, whereas the initial guard
expected the manifest name alone. The guard and independent sampler now handle
only those two exact names and still require an isolated UID. No prefix match or
main-process fallback is permitted. Fresh full-matrix proof remains required.

CI163 (44531679887, source 9cf04e6) still failed before Python during initial
IPC (`DeadObjectException` in the worker handshake). It is not an infrastructure
failure or accepted UID proof. Owner and worker now retain their callback Binder
endpoints explicitly for the whole lease. Connected-but-unready IPC has a 3s
bound, within the global deadline, and emits fixed stage/stack-location diagnostics
without URLs/key bytes. The full matrix remains blocked until proven successful.
An upfront real isolated JNI/OpenSSL test exposes this boundary early; successful
runs must subsequently execute all original 27 and additional 24 tests. CI166 (44537400758, source f96bad1) confirmed the actual cross-domain proc FD
SELinux denial. The three-UID observer design above addresses that denial; its
fresh runtime matrix is still pending. Proc FD authentication and isolated UID
checks are never replaced with self-reported RSS. Independent shell meminfo for
both Python worker and Java observer, observer CPU/RSS and two-process retirement
are mandatory evidence; compilation alone does not prove the design works.

Native Windows CI161 ran 114 collected tests successfully (16 Linux-only skips),
then the unchanged packaging guard rejected generated bytecode in two newly
imported directories. Scoped `__pycache__/` ignores match existing repository
practice; tracked/untracked source and workflow dirtiness are still rejected.
The file-entrypoint package-path regression is tested from a foreign directory
under Python `-I`, using real package-based validator tests. Callback race #39
remains open independently. Final exact-HEAD results belong in the PR evidence.

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
