# Source-built Android JNI experiment

Issue #5; isolated follow-up to PR31/PR33. This is a separate application
`com.thekain.runtime.lab`, with separate debug-only QA launchers, no exported worker, product integration,
release-signing Environment, publication or user-media access. `production_ready`
and `automatic_publication` remain false. The existing Android application's tests
and required branch checks remain in place.

The subsequent genuine UID boundary is described in [UID_ISOLATION.md](UID_ISOLATION.md).
The original shared-UID interpreter/process/QA tests remain a separate baseline.

## Fixed input and license inventory

`profile.json` pins CPython 3.14.8 and OpenSSL 3.5.9 to full commits in their
upstream Git repositories. Intake checks HEAD, tracked changes and Git object
integrity, then exports tracked objects with `git archive`. Git hashes establish
input identity; this does not claim an independently verified release signature.
The official Google NDK r27d (27.3.13750724) archive has a fixed URL, byte count
and SHA-256; its SHA-1 matches the official Google SDK repository metadata.
The pure-Python yt-dlp 2026.10.26 wheel has the same SHA-256 lock as PR31.
No third-party prebuilt Python/dependency archives or upstream download helpers
are used. The known official SDK/NDK tools are the binary build tools.

| Component | License and packaging |
| --- | --- |
| CPython 3.14.8 | PSF/Python license; root license and bundled notices copied into `assets/legal` |
| Included HACL hashing code | MIT headers retained as separate notice text; source hashes recorded |
| Included Expat/XML and other bundled CPython code | Root and per-directory LICENSE/COPYING/COPYRIGHT/NOTICE files retained |
| OpenSSL 3.5.9 | Apache-2.0; source license/notices retained; statically linked into Python TLS/hash extensions |
| yt-dlp 2026.10.26 pure-Python wheel | Unlicense; wheel license files retained, no bundled standalone executable/extras |
| NDK compiler runtimes | Official `NOTICE.toolchain` copied and hashed |
| Lab instrumentation only | AndroidX/OkHttp/Okio/Kotlin/coroutines/annotations: Apache-2.0; JUnit 4.13.2: EPL-1.0; Hamcrest 1.3: BSD-3-Clause; versions in `android/app/gradle.lockfile` |

This inventory precedes lab packaging. It is not a completed legal review for
product distribution. The broader PR33 runtime intake stays historically
`BLOCKED_NOT_RUN`; this experiment deliberately uses a smaller dependency set.
`_ctypes`, SQLite, bz2, lzma, zstd, Tk, readline/curses, dbm, UUID and decimal
extensions are disabled. The optional mimalloc allocator is disabled using
CPython's `--without-mimalloc`: its direct `SYS_open` call is blocked by API26
app seccomp. The normal GIL build uses CPython's standard allocator instead;
Android protection is never relaxed. The JNI probe checks `WITH_MIMALLOC=0`.
No libffi, JS/EJS runtime, FFmpeg, cookies or browser
integration is included. OpenSSL uses no external providers/modules.

## Build and execution boundary

The Linux/x86_64 builder exports clean tracked source, takes in the hash-locked
wheel and NDK, then runs configure/make/JNI compilation with a sanitized
environment and an inherited seccomp filter denying socket/connect/socketpair
(and x32 syscall entry). Failure to install the fence fails the build. This is
an offline compile boundary, **not** a filesystem sandbox for malicious source.
Intake, Gradle dependency resolution and the official SDK manager require HTTPS.
No signing credentials are forwarded to these jobs.

Both x86_64 and arm64-v8a are compiled for API 26. Each packaged ELF is checked
for ABI, PT_LOAD alignment of at least 16384, expected system dependencies and
absence of RPATH/RUNPATH. `--no-rosegment` follows upstream Android linker
flags for usable backtraces on older Android. Native modules are renamed `libpyext_*.so` and installed
by Android's package manager. A fixed import finder loads them from
`ApplicationInfo.nativeLibraryDir`; no executable code is copied into writable
app data. Only hash-verified Python/data assets go into private app storage.
The APK uses native-library extraction; ELF alignment and actual 16-KiB device
execution are separate checks. No claim of bit-for-bit reproducibility is made.

The current process-isolation stage uses a non-exported `:python_runtime` bound
Service, typed AIDL, one request per fresh process, and a main-process supervisor.
The main process never loads CPython. Requests have internal IDs and a global
monotonic deadline; cancellation/death/IPC failures retire the worker before a
result is delivered. A validated UID/name/start-time identity protects kill
operations. Python runs behind a per-thread socket-denying seccomp filter;
HTTPS runs on a prestarted Java transport thread with independent counters.

Kernel `RLIMIT_AS`/`RLIMIT_CPU`, external RSS sampling and forced worker termination
provide limits outside Python. This is shared-UID failure containment, not a
filesystem sandbox for hostile code. Actual measured RSS/PSS evidence and the
sampling caveat are described in [PROCESS_ISOLATION.md](PROCESS_ISOLATION.md).

The native test invokes the real pinned yt-dlp on one own HTTPS HTML5 fixture.
The Java transport uses only the fixture CA and numeric loopback origin; plugins,
JS/remote components, cookies, media transfer and external executables stay off.
OpenSSL is additionally exercised with a real in-memory client/server TLS
handshake inside the worker, without granting Python direct network sockets.
Unknown codec metadata remains unknown/ineligible. Public sites and YouTube
remain unintegrated. The prior PR34 evidence is historical; the current exact
commit's process/fault evidence belongs in its implementation PR.

## Reproduce

On a Linux/x86_64 build host with Git, GCC/make, Perl, pkg-config, unzip, Python
3.11+, official Android SDK 35/build-tools 35.0.0, JDK17 and Gradle8.11.1:

```sh
python3 rewrite/experiments/android_native/build.py --work /tmp/thekain-native-build
gradle --no-daemon -p rewrite/experiments/android_native/android \
  :app:assembleDebug :app:assembleDebugAndroidTest \
  -PlabRuntimeDir=/tmp/thekain-native-build/package
python3 rewrite/experiments/android_native/delivery.py pack \
  --work /tmp/thekain-native-build --output /tmp/thekain-native-delivery
```

Delivery verification requires `GITHUB_SHA` equal to the checked-out source
commit. It checks official apksigner, aapt and zipalign results, separate lab
package IDs, min/target API, both ABI inventories and exact ELF/notices bytes.
The ephemeral debug certificate identifies same-run test APKs only; it is not
the production certificate or a replacement for APK89's independent verifier.

The existing `rewrite-ci.yml` has a **default-off** manual
`native_runtime_lab=true` input. It fails closed when combined with build,
signing or historical-verifier modes. Two jobs build both ABIs once, then feed
only that run's artifact ID to four hosted x86_64 emulator jobs: API26/4KiB,
API29/4KiB, API35/4KiB and API36/16KiB. The script asserts actual SDK, ABI and
page size (API26 reads `AT_PAGESZ`, which works without shell `getconf`)
and requires all 23 JNI/process tests to pass, with no skip/fallback.
The 16KiB image is pinned to official API36 revision7 and provisioned with
4GiB guest RAM. Official API35 ps16k revision5 was rejected after a reproduced
`system_server` crash: `DisplayFoldController.onDefaultDisplayFocusChanged`
`NoSuchMethodError` caused `DeadSystemException` and aborted instrumentation
(CI121 attempt2 job134632656982). This is an image limitation, not a successful
API35/16KiB validation. API35/4KiB, all 41 product tests and all 23 JNI/process tests
remain required as before. Image fingerprint/revision and system_server PID
before/after tests are retained; a system_server restart/fatal also fails the job.
Failure logs are retained; APKs expire after one day. An emulator timeout stops
only this lab application. ARM64 execution and physical-device testing remain
`NOT_RUN` until separately performed. Do not install this lab over the product.

## Evidence status

Build/static checks do not establish Android execution. The implementation PR
records exact-head CI IDs, all 23 tests on each target, measured resource reports
and failures found during validation. ARM64 execution, physical-device QA,
full foreground/background lifecycle policy and product distribution remain
separate gates. All 41 product API35 tests remain unchanged.
