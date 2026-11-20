# Isolated Android UID laboratory — issue #37

This backend belongs only to the separate runtime lab APK, after PR35/36.
The original shared-UID backend, its 23 native + 4 lifecycle tests, and all 41
product API35 tests remain unchanged. No product UI/site adapter, YouTube,
release signing, publication, branch protection or existing QA gate is changed.
`production_ready=false`, `automatic_publication=false`.

## Architecture and trust boundary

There are **two UIDs and three PIDs**:

| Role | UID | Executes Python | Responsibility |
| --- | --- | --- | --- |
| Main owner | Normal application UID | Never | Verified resources, bounded owned HTTPS, AIDL capability, global deadline |
| Private isolated Service | Android-assigned isolated UID | Never | Non-dumpable trusted Java/libc-only supervisor, kernel observation, forced kill/reap |
| Native exec child | Same isolated UID as Service | Yes, once | Fixed operation in the pinned CPython runtime; no ART/JNI/Binder handles |

`IsolatedRuntimeService` is explicit, non-exported and `isolatedProcess=true`.
Only the installed package UID may submit/retire work. Owner-generated UUIDs,
actual Binder credentials, typed AIDL and one Service lifetime per operation
bind the channel. It never receives arbitrary Python, directory capabilities,
user paths, cookies, credentials or public-site URLs.

The Service loads only the repository's libc-only `libthekain_supervisor.so`.
It sets and verifies `PR_SET_DUMPABLE=0` before spawning. A minimal fork/exec
launches the repository's `libthekain_child.so` from Android's installed native
library directory. Only async-signal-safe operations occur between ART fork and
exec; Python never runs in a forked ART address space. The executable and shared
libraries are source-built with the existing pinned NDK, both ABI/ELF/16KiB
checks, same-run APK hashes and receipt verification. No new third-party component,
downloaded binary, dependency version or license is introduced.

Before exec, all inherited descriptors are closed except the fixed socketpair,
three verified sealed resources and `/dev/null`. Binder and owner capabilities
are not inherited. Arguments contain only the installed library directory and
supervisor PID; URLs/JSON travel through a bounded fixed binary protocol.
The native child installs its fence before Python initialization, with no ART
threads that could remain unfenced. It cannot create sockets/descendants, exec,
ptrace, use process_vm or io_uring, change another process's limits/scheduling,
signal the supervisor/process group (including indirect SIGIO through fcntl/ioctl),
change credentials/namespaces, or clear parent-death protection. Before filtering,
the native bootstrap verifies that it has exactly one thread.
`PR_SET_PDEATHSIG=SIGKILL` plus a parent-identity race check handles parent loss.
Self-signals remain available for the real native-abort test. The exec child
explicitly uses dumpable=1 so its trusted parent can inspect it; the Java Service
remains dumpable=0. A fixed PID/UID/parent readiness handshake after exec precedes
proc opening and Python release, avoiding a hidepid/pre-exec race.

The Service and child **share** their isolated UID/SELinux category. They are
not two independent UID sandboxes. Protection of the trusted supervisor also
requires non-dumpability, the irreversible child-wide seccomp fence, no inherited
Binder/control descriptors, and bounded protocol parsing. Before Python, native
probes require actual kernel denials for supervisor memory, signals/group signal,
ptrace, prlimit, clearing PDEATHSIG, clone and exec. Incomplete proof aborts before
Python. The owner authenticates the Service PID, not worker-supplied counters.
This model excludes compromise of Android/kernel/ART or the pinned native
bootstrap itself; no claim of a complete kernel-exploit sandbox is made.

## Why direct proc-FD transfer was rejected

CI166 (44537400758, f96bad1) records an enforcing AVC: `untrusted_app` cannot
read an `isolated_app` task's `/proc/<PID>/stat`, even through a passed FD.
Binder returned DeadObjectException while the owner was alive. Callback lifetime
changes cannot resolve that policy restriction.

CI167 (44541028902, 6770009) and CI169 (44542347147, c3ab687) tried a third,
Java-only isolated UID. API29 records another enforcing AVC: the two
`isolated_app` processes have **different MCS categories**, so they cannot exchange
those proc FDs either. All four UID matrices failed; these are implementation/
platform-contract failures, not infrastructure passes. That unaccepted helper
and direct-JNI path were removed. No SELinux relaxation, root, hidden API,
self-reported Python RSS or fake success is used as a substitute.

The current native exec design keeps independent observation in a protected
parent process within the same isolated UID/category. Its actual Android matrix
is still **NOT_RUN** until a fresh source build and every target passes.

## Verified resources and HTTPS

The main reads Python resources from installed APK assets and verifies every
pinned inventory size/SHA-256. A collision/path-checked stored ZIP is bounded to
150MiB input / 160MiB archive, 32MiB per file. Its private name is unlinked after
opening read-only. Only that FD and two <=16KiB ephemeral owned TLS fixture files
cross AIDL. These fixture credentials are unrelated to release-signing keys.

The isolated supervisor rechecks read-only flags, size and digest, then copies
the resources to anonymous memfds and irreversibly seals WRITE/GROW/SHRINK/SEAL.
The child inherits only those sealed copies. Android denies reopening even an
inherited memfd through `/proc/self/fd`: CI179 API26/29 reached interpreter
initialization but recorded an enforcing tmpfs `open` denial. The child installs
the public CPython `PyFile_SetOpenCodeHook` before initialization. Only the exact
verified fd3 archive is accepted; `PyFile_FromFd(..., closefd=False)` reads it
without reopening a pathname, including encodings bootstrap. A host embedded-
Python test imports real modules while all open/openat calls are denied.
The child is single-threaded and zipimport seeks for each archive access.
The OpenSSL memory self-test reads existing sealed PEM FDs directly and uses BIO
pairs; it verifies the pinned _ssl/OpenSSL version, trusted certificate, IP SAN
and negotiated TLS, without reopening a certificate path or granting sockets.
CPython zipimport reads fd3;
installed stdlib extensions come only from the pinned native directory. There is
no writable extracted Python tree. The isolated UID itself lacks INTERNET and
cannot read/write the owner's private canary; Java OS-denial checks occur before
the additional child filter, and Python repeats the file/network negative tests.
On API35 app-data namespace isolation can return ENOENT for a real owner file.
Only kernel EACCES/EPERM/ENOENT are accepted; the main owner must independently
prove its unique canary exists and remains unchanged before clearing the lease.
Missing or changed owner data poisons reuse rather than authorizing success.

HTTPS stays in the trusted main owner: the owned numeric-loopback origin/port,
HTTPS only, fixed GET/HEAD, private test CA, no proxy/cookies/user headers.
Limits remain 12 requests, 512KiB per response, 2MiB aggregate; Python output
<=64KiB. No media download is enabled. Redirect/method/origin/CA/capability and
late-result checks remain mandatory. The native channel also bounds message
count, URL/response/output lengths and state transitions before allocation.
A real oversized child-frame test must fail IPC and permit a fresh owned analysis.

## Resources, retirement and recovery

The trusted Service opens the native child's genuine read-only proc descriptors,
checks PID/UID/parent/start ticks, kernel proc-path inode/device identity, regular-file type and libc F_GETFL
read-only flags. The descriptor check avoids proc fd/fdinfo traversal after non-dumpability,
which failed with FileNotFoundException before Python in CI171 API29. Direct
libc flags and proc inode identity work without temporarily relaxing dumpability. It reads kernel seq-files in a thread outside Python every 40ms.
Only authenticated, sequenced, timestamped typed snapshots reach the owner.
Service RSS/CPU and absence of libpython mappings are recorded separately.
CPU uses actual SC_CLK_TCK. Missing/stale observation fails closed; unknown exit
records have at most 120ms grace and cannot authorize OK. A >1s stale observer
fails IPC. No Python-provided RSS/CPU value is trusted.

RLIMIT_AS and RLIMIT_CPU are kernel-enforced. The native child also has a hard
64-descriptor limit. Each socketpair endpoint requests 32KiB send/receive buffers
(the kernel may double these); the child cannot increase them with setsockopt.
New memfd/System-V shared-memory creation is denied in the child: retaining
writable shmem through descriptors could otherwise bypass sampled RSS/AS.
Only the supervisor creates the bounded, sealed resource memfds before exec. RSS is a sampled retirement
threshold, **not an instantaneous hard memory limit**; measured overshoot is
retained. A separate 64MiB RSS case must show a real over-budget kernel sample
and forced retirement, independently of Python MemoryError/RLIMIT_AS. Resource
ZIP bytes and sealed-memfd/file-cache storage are recorded separately; process
RSS is not the complete three-process memory footprint. Physical-device budgets
must be measured rather than copied into production.

Cancel/deadline first revoke owner HTTPS. Typed one-way AIDL asks the trusted
Service's separate supervisor thread to SIGKILL its recorded native child;
Python and a deliberately blocked Service main Looper cannot prevent this.
The Service validates/reaps only its one spawned child. It retains kernel
handles until actual original-task death. Then the main removes the Service
binding and requires its rooted Binder death and ServiceRecord removal, ended
owner network tasks/callbacks and closed resources before releasing the lease.
Unconfirmed cleanup within 2.5s yields CLEANUP_FAILED and durably poisons reuse.

A final separate fault test removes the supervisor binding while Python spins.
The main must stay responsive and refuse to claim missing kernel-death proof or
start another operation. Independent shell evidence must confirm the native
PDEATHSIG fallback actually ended the child. That outside evidence does **not**
automatically clear the app's poisoned lease. The test runs last and never erases
its uncertainty with a test-only reset.

An AtomicFile reservation survives owner loss. A replacement owner/supervisor
fails closed until a real OS reboot increases public BOOT_COUNT. Automatic
recovery after losing the trusted observer/owner is not implemented. Recovery
after native child crash/cancel/deadline/IPC/memory failures is mandatory while
the observer remains healthy. Android freezing/Doze may delay scheduling: no
real-time deadline or prolonged-background guarantee is claimed.

## Validation and current status

The lab supports only the pinned 64-bit x86_64/ARM64 ABIs; no 32-bit runtime claim is made.
Mandatory native targets: API26/29/35 at 4KiB and official API36 ps16k rev7 at
**actual 16KiB**, with both x86_64 and ARM64 source builds. An upfront real
UID native interpreter/OpenSSL probe exposes bootstrap failures
early; success must still be followed by all original 27 tests, **27 additional**
backend/UI tests, external-app bind rejection and the separate observer-loss test.
Independent shell meminfo must include both native child and trusted Service.
The resource journal/report includes identities, kernel CPU/RSS, overshoot,
resource byte counts, hard-limit values and cleanup/cancellation latency.

Local native helper compilation/ELF16KiB validation for both ABIs: PASS.
Local AIDL/APK/app+probe Lint: PASS. Host checks include real embedded-Python
imports from a sealed FD with all file reopens denied, in addition to the kernel
process/fence/lifetime checks. Final exact-HEAD CI is still required. Linux kernel tests exercise actual non-dumpability, seccomp
foreign-process denials and parent-death SIGKILL; Android is still mandatory.
The new native exec runtime matrix, new physical ARM64 boundary, fresh independent
review and product integration are **NOT_RUN / NOT_ACCEPTED** pending evidence.
Final exact-HEAD results belong in the PR, never inferred from compilation.

Historical green product/unsigned CI162/164 retained all 41 API35 tests, JVM/Lint,
policy, host tests and native Windows packaging checks. CI154/157 Maven failures,
Ubuntu mirror failures, and CI168's API26 runtime-identity precondition failure
before Gradle/tests are recorded separately as infrastructure/precondition
failures; none is a passed runtime test. The ESRCH proc-observer and legacy
exit-classification fixes retain strict assertions. Known Windows callback race
#39 remains open even when CI happens to pass.

## SDK-free Samsung protocol and separate gates

The debug-only **TheKaiN UID QA** launcher remains separate from the shared-UID
QA launcher. Its private HTTPS fixture directory prevents certificate collisions.
Press the probe, then the fixed **14-case** suite and export
`TheKaiN-isolated-uid-qa-report.json` through Android's document picker.
It includes native ARM64/OpenSSL, UID denials, blocked-main cancel/deadline,
crash, AS and actual low-RSS retirement, tampered resources/proc descriptors,
and five fresh owned analyses. Manual hang/cancel and Activity recreation remain.
External intents cannot supply operations/URLs/Python. JSON is bounded to 256KiB;
source/APK/certificate/device and both process identities accompany results.
It is editable operator evidence requiring independent review, not attestation.
PR36's successful Samsung reports establish only the old shared-UID backend.

Lab versionCode=3 is distinct from product APK89 versionCode=3. CI debug signing
is not protected release signing. A different debug certificate can require
exporting old evidence and reinstalling **only the separate runtime lab**; never
remove the user's downloader. No physical install/execution is claimed here.

Separate OPEN tasks remain [APK89 review #38](https://github.com/aslanprp09-source/TheKaiN-Video/issues/38),
[Windows callback race #39](https://github.com/aslanprp09-source/TheKaiN-Video/issues/39),
[prolonged background #40](https://github.com/aslanprp09-source/TheKaiN-Video/issues/40),
and [deep Doze #41](https://github.com/aslanprp09-source/TheKaiN-Video/issues/41).
Request fresh KaiN1One review only after all final-HEAD CI passes. Do not merge
before approval. Main, protection, signing keys, releases and QA gates stay intact.

The successful bootstrap does not invoke Android-blacklisted `setfsuid`: AOSP's
[app blacklist](https://github.com/aosp-mirror/platform_bionic/blob/android10-release/libc/SECCOMP_BLACKLIST_APP.TXT)
kills it, overriding an additional ERRNO filter with the stronger SIGSYS action.
A dedicated negative test now requires actual waitpid SIGSYS evidence, no Python
entry and successful fresh analysis. Linux host tests retain the direct ERRNO
probe; credential denials remain in the native filter. Exit signal/code are
recorded from the trusted supervisor's waitpid result, never inferred from EOF.
