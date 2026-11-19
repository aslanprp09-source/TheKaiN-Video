# ARM64 and background QA — separate debug laboratory

For the subsequent genuine isolated-UID backend, separate debug launcher and
report, see [UID_ISOLATION.md](UID_ISOLATION.md). PR36's bounded Samsung
shared-UID acceptance does not verify that new boundary. New UID phone execution
remains NOT_RUN until its exact-build report is reviewed.

Issue #5, next stage after PR35. Physical Samsung execution: **NOT_RUN** until an
actual report is returned and reviewed. Emulator results and ARM64 compilation
cannot fill this field. No product adapter, YouTube support, release publication,
release-signing environment, key/password, main change or release gate update.

## Deliverable and trust boundary

`lab.apk` installs as **TheKaiN Runtime QA**, `com.thekain.runtime.lab`, alongside
`com.thekain.video.rebuild`; it never updates/uninstalls the downloader. The debug
launcher exists only in `src/debug`. It ignores incoming Intent extras and offers
fixed operations only: interpreter probe, owned HTTPS analysis, hang/cancel,
6-second deadline, native crash, verified Service kill, five analyses, owner kill
with a local confirmation, and JSON export through Android's document picker.
No URL/code entry, arbitrary PID, cookies, public sites, file download or upload.

The non-exported Service still has the **same application UID**, distinct PID.
This contains failures; it does not sandbox untrusted Python or native code.
`probe.apk` is an SDK-only CI helper with a different Android UID which actually
tries to bind the Service and must be denied. It is not needed on the phone.
`tests.apk` also is CI-only. Only `lab.apk` is for manual Samsung testing.

The Application owns a single session, not the Activity. File preparation,
hashing, HTTPS, operations and export do not block the UI thread. A new session
reconciles a bounded AtomicFile journal, retires only a validated reserved worker
(UID/name/PID/kernel start ticks), records interrupted work without fabricating
a runtime error/success, and never resumes old operations. Owner Binder death
already makes its child exit; CI proves that death **before relaunch/recovery**.
The main process must never map libpython. A new analysis has a fresh worker ID.

A malformed journal fails closed and is preserved. Lifecycle callbacks must not
overwrite it after initialization fails. Export then offers only a minimal
source/error-class diagnostic, excluding the corrupt content; it is incomplete
test evidence, not a successful phone report. Cancel stays outside the scroll
area, including a recreated landscape window; CI checks its full visibility and
actually clicks it during a hang.

The foreground has no wake lock, battery exemption, foreground service or alarm.
Android may suspend/kill the app. Handler sampling is subject to scheduling and
deep sleep; reports include actual elapsed time and deadline lateness. This is
a measurement laboratory, not a guarantee of production background execution.
The 384MiB RSS threshold/256MiB virtual headroom/40ms polling/30s maximum deadline
are unchanged **lab budgets**. RSS is sampled, not an instantaneous hard limit;
prior API35/36 overshoot of 43.7/26.7MiB remains relevant. Do not copy these values
into the product without physical measurements. CPU is observed process
`utime+stime` in actual `_SC_CLK_TCK`, sampled before exit: a lower bound, not an
exact whole-operation CPU peak/profile. The existing kernel RLIMITs remain.

## Build, verify and review before phone delivery

1. Normal branch/PR against `rebuild-2026-11-11`; no force push or protection bypass.
2. All required product checks remain: host, policy, JVM, all 41 API35 UI tests,
   and legacy checks. Dispatch the default-off `native_runtime_lab=true` on the
   exact branch with build/sign/verify flags false, using the existing workflow.
3. Fresh CPython/OpenSSL/JNI build for both ABIs, lab+tests+probe assembly/Lint.
   Same-run `build-report.json` pins source commit, three APK SHA-256 values,
   debug certificate, original inventories/ELFs/notices plus QA notice hashes.
   Official SDK35 apksigner/aapt/zipalign verify the delivered bytes.
4. All original 23 plus four lifecycle tests on API26/29/35 4KiB and API36 16KiB.
   Additional different-UID bind and owner-kill/restart probes run on each target.
   The driver starts a normal Activity, uses its fixed hang/self-kill controls and
   confirmation, then proves both PID deaths before starting verification
   instrumentation. No shell SIGKILL/SELinux change; no test-runner force-stop
   during owner death. Empty/transient `/proc` reads are not proof of death.
   Readiness requires both `mCurrentFocus` and `mFocusedApp` from the full window
   dump (the `windows` subcommand omits display focus on API29+), plus an enabled
   fixed control in the current UI hierarchy. Failure captures focus/Activity,
   UI tree and screenshot. PID disappearance uses explicit ENOENT, never a
   permission error or an unavailable API26 toybox applet.
   New screenshots (`qa-ready`, `qa-running`, landscape) and owner journal are
   collected only from the owned `qa-evidence` directory. No fixture PEM export.
5. Require fresh independent KaiN1One approval of the final HEAD before merge
   and phone delivery. A debug signature is **not** protected product signing or
   a verifier for APK89. No release-signing dispatch is part of this stage.

QA-only fixture adds the already pinned/tested okhttp-tls/okhttp 4.12.0, Okio 3.6.0,
Kotlin 1.9.10 and annotations 23.0.0 to this debug variant. Their Apache-2.0 license,
upstream source locations and resolved JAR SHA-256 inventory are packaged under
`assets/legal/qa-debug-*`; Gradle rejects unknown dependencies. No prebuilt
interpreter/yt-dlp, product dependency or new executable download is introduced.
TLS uses newly generated per-session loopback certificate/private key; these are
fixture secrets in app-private files, never release credentials or report data.

## Samsung procedure — no SDK/ADB

Use only the reviewed exact CI artifact/receipt; download `lab.apk`, not
`tests.apk`/`probe.apk`. Verify the displayed SHA-256 against the receipt using an
available trusted tool. On Samsung allow this one installation through the
normal system installer; do not disable device protection. If the installer
rejects an existing laboratory signature, stop and report it; do not uninstall
the downloader or delete its data. CI debug certificates can differ between runs.

1. Open **TheKaiN Runtime QA**, wait for **Готово**, mark the physical-phone
   checkbox and choose **Обычная** battery mode matching Samsung settings.
2. **Проверить JNI / ARM64**: expect code 0, `aarch64`, 64 bits, interpreter and
   OpenSSL execution, child PID different from the app. Then run owned analysis:
   code 0/one format; this fixture never downloads a video.
3. **Зависание Python**, wait for Python PID; **Отменить операцию**. Expect code 1
   and confirmed process exit. Analyze again, expect a fresh instance/code 0.
4. **Дедлайн 6 секунд**: expect code 2/confirmed exit. Run again, press Home and
   lock the screen for at least 15 seconds; return and inspect the actual time,
   any lateness/error and responsiveness. Do not count a late result as on-time.
5. **Авария Python**: expect code 4, app responsive; analyze again. Start a hang,
   wait for PID, **Завершить Service**: code 4; analyze again.
6. **Пять анализов подряд**: wait for all five; no retained Service or owned temp
   files. Start a hang, rotate/recreate the screen, cancel; inspect one terminal
   result only. Repeat Home/lock with the long hang and cancellation on return.
7. Start a hang, wait for PID, **Завершить процесс QA**, confirm. Reopen the lab;
   old operation must be `INTERRUPTED_OWNER_OR_SESSION` in JSON, no old success.
   Analyze again: fresh process. If it fails, preserve/report the error; do not
   repeatedly relaunch to turn one lucky success into evidence.
8. Set this **lab app only** to Samsung's restricted battery mode, select
   **Ограниченная** in the lab (operator label, not automatic verification), repeat
   owned analysis, Home/lock/deadline and recovery. Record Samsung settings and
   any OS kill/lateness. Restore the chosen battery setting afterward.
9. After a final successful analysis and idle state, **Сохранить JSON-отчёт**.
   Share only that JSON plus observations/optional QA screenshots, no private
   app-directory archive, PEM, phone identifier, token or signing data.

The report contains device model/SDK/fingerprint/ABI, installed APK and public
certificate SHA-256, source commit, typed case results, PID/UID/start identities,
sampled RSS/CPU/limits, power/lifecycle observations and bounded file counts.
No IMEI, account, raw URL, media, key or Python text is exported. File byte counts
include immutable unpacked runtime assets; bounded journal growth is expected.

Offline intake (original same-run receipt required):

```sh
python3 rewrite/experiments/android_native/phone_report.py \
  --report TheKaiN-runtime-qa-report.json --receipt build-report.json
```

Intake rejects source/APK/certificate mismatch, x86/emulator evidence, missing
ARM64 interpreter proof, incomplete fault/background/battery cases, stale cases,
unconfirmed cleanup, active workers/temps and secret-like exported fields. A
valid input returns **OPERATOR_REPORT_REQUIRES_REVIEW**, never physical-device
attestation or automatic release approval. UI checkbox/fingerprint/JSON can be
forged: review measured timing, repetitions, file trends, logs and user feedback.
Current physical execution and OEM background acceptance remain **NOT_RUN**.

APK89's independent artifact-review ledger remains **BLOCKED**, a separate gate.
Historical verifier run102 technical PASS and device QA cannot update that ledger
implicitly. UID-isolation design and eventual product integration remain later
separate reviewed work.
