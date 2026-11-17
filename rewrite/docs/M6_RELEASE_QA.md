# M6 — reproducible experimental packages and release gates

Issue [#6](https://github.com/aslanprp09-source/TheKaiN-Video/issues/6), base `rebuild-2026-11-11`. Updated 17 November 2026 against signed QA source `2b969ed9928758c11a19382026fa35d21f52c5eb` (run #89). No publishing, tag creation or changes to archival branches/releases. **Production release remains BLOCKED.** The machine-readable [gate record](../release/qa-gates.json) separates evidence by source run/artifact/version; an old artifact PASS cannot certify a newer APK. See [audit reconciliation](QA_AUDIT_RECONCILIATION_2026-11-17.md).

## Outputs and identity

Windows: unsigned x64 onedir portable ZIP and per-user Inno Setup installer, named `0.1.0-m6-<commit>-…-unsigned`. The installer requires Windows 11, defaults to `%LOCALAPPDATA%/Programs/TheKaiNVideo-Rebuild` and has its own AppId/shortcut. It does not migrate old installations or delete queue/download data on uninstall. Do not point an unattended QA install at a user's app directory. Queue storage remains the accepted M2 location; manual QA uses an isolated `THEKAIN_DATA_DIR`.

Python and dynamically loaded Qt are included. A dedicated console `TheKaiNExtractor.exe` handles yt-dlp; the windowed GUI has no usable stdio and must never relaunch itself with `-m yt_dlp`. Analysis and transfer share the worker selector; `CREATE_NO_WINDOW` and redirected pipes remain in the existing process runner. Tests cover the frozen path with spaces. Qt DLLs remain replaceable in `_internal`; no one-file encryption/obfuscation or updater is introduced. The approved SVG renders the installer/executable icon.

FFmpeg/FFprobe are **external PATH prerequisites**, excluded from the installer. The application already disables download when these verification tools are missing. For the reproducible QA baseline, `prepare-tools.ps1` obtains Gyan's upstream-linked FFmpeg 9.0.2 essentials build and Inno Setup 7.1.1 only after matching pinned SHA-256 values in [tools.json](../release/windows/tools.json). FFmpeg is a GPL-enabled CI tool, not a redistributed component here. Installing the app alone does not satisfy this prerequisite; no player/site/runtime functionality is inferred from that install.

Android: `com.thekain.video.rebuild`, versionCode **3**, versionName **0.1.0-m6**, minSdk 26, target/compile SDK 35. Run #69 was versionCode **2**; run #89 is the signed versionCode **3** update candidate. The existing filenames keep versionName M6, so identify a build by run/source/versionCode/hash rather than filename alone. Default release APK/AAB are unsigned, non-debuggable internal review artifacts. An unsigned APK is not installable. Existing debug APK remains a separate testing output. An optional protected job creates **signed internal QA** output, never a public/production release. Debug and release share the rebuild applicationId: signatures may prevent replacement; do not uninstall a user's build/data to bypass that check. Follow the [Samsung update/deletion checklist](ANDROID_INTERNAL_QA_UPDATE_V3.md).

Every artifact directory contains `manifest.json` and `SHA256SUMS`, recording the full source commit, channel, sizes and SHA-256 of actual files. Reports do not hash themselves. Verify from the artifact directory with `sha256sum -c SHA256SUMS`, or `Get-FileHash` on Windows. `inspection.json` independently checks APK identity, absence/presence of signatures, certificate digest, ZIP alignment and each native ELF's load alignment. AAB signatures are verified too. All these reports retain `production_ready: false`.

The inputs/commands are reproducible; **byte-for-byte determinism is not established** across hosted OS images, timestamps, toolchains or signing. `SOURCE_DATE_EPOCH`/`PYTHONHASHSEED` are set for freezing, but matching commands are not a determinism claim. Keep the exact commit, lockfiles, tool digests, runner image/logs and output manifests for comparisons.

## Exact commands

Windows x64, PowerShell 7, CPython **3.14.10**, a clean committed checkout. Use the pinned wheel lock for packaging, not an existing global Python installation:

```powershell
python -m venv rewrite/release/.venv
./rewrite/release/.venv/Scripts/Activate.ps1
python -m pip install --only-binary=:all: --require-hashes --no-deps -r rewrite/release/windows/requirements-build.lock
./rewrite/release/windows/prepare-tools.ps1 -Destination "$env:TEMP/TheKaiN-build-tools"
$tools = Get-Content "$env:TEMP/TheKaiN-build-tools/paths.json" -Raw | ConvertFrom-Json
$env:PATH = "$($tools.ffmpegBin);$env:PATH"
$env:QT_QPA_PLATFORM = 'offscreen'
python -m unittest discover -s rewrite/windows -p 'test_*.py' -v
$commit = git rev-parse HEAD
./rewrite/release/windows/build.ps1 -Iscc $tools.iscc -Commit $commit
```

Build output: `rewrite/release/out/windows-artifacts/`. Only on a disposable host with no existing rebuild installation:

```powershell
$installer = @(Get-ChildItem rewrite/release/out/windows-artifacts/*-setup.exe)
if ($installer.Count -ne 1) { throw 'Expected one installer' }
./rewrite/release/windows/smoke-installed.ps1 -Installer ($installer[0].FullName) -Evidence "$env:TEMP/TheKaiN-installed-QA"
```

This installs to a fresh isolated path, runs the actual frozen JSON worker, downloads two **owned generated tracks from loopback**, merges through FFmpeg, verifies with FFprobe/full decode/SHA-256, opens Qt, checks SVG/plugins, captures Home and uninstalls. It runs from a different working directory. `host.json` records the real host OS; Windows Server 2025 CI is explicitly not clean Windows 11 evidence. Python is not needed by the installed app, but the hosted runner has developer tooling; its success cannot establish a pristine-machine pass.

Android, JDK 17, SDK/build tools 35, Gradle **8.11.1**:

```sh
cd rewrite/android
gradle --no-daemon :app:testReleaseUnitTest :app:assembleRelease :app:bundleRelease
gradle --no-daemon :app:lintRelease
python3 tools/report-tests.py app/build/test-results/testReleaseUnitTest
python3 ../release/inspect-android.py \
  --apk app/build/outputs/apk/release/app-release-unsigned.apk \
  --aab app/build/outputs/bundle/release/app-release.aab \
  --tools "$ANDROID_HOME/build-tools/35.0.0" --output ../release/out/android-unsigned
python3 ../release/manifest.py ../release/out/android-unsigned \
  --commit "$(git rev-parse HEAD)" --platform android --channel unsigned-experimental
```

`app/gradle.lockfile` pins resolved configurations, including test/build tools; normal builds do not write locks. To review an intentional dependency update: change direct pins, run `gradle --no-daemon :app:dependencies --write-locks`, inspect the diff, rebuild/test both variants, regenerate the inventory. Dependency locking pins versions, not downloaded bytes: actual runtime artifact hashes are in the generated inventory. New unreviewed license families fail `releaseNotices`.

Manual CI without modifying main: **Clean rebuild → Run workflow → reviewed rebuild branch → `build_packages=true`, `sign_android=false`**. The existing workflow calls the packaging workflow from the same commit; ordinary pushes do not package/sign. CLI:

```sh
gh workflow run rewrite-ci.yml --ref rebuild-2026-11-11 -f build_packages=true -f sign_android=false
```

The original debug/JVM/emulator jobs still run. Packaging adds native Windows and Android release checks. The optional signing job being skipped is expected, not evidence of a signature.

## Android signing — protected internal QA PASS; public release BLOCKED

Key setup and restored encrypted backups are owner-confirmed. [Protected run #69](https://github.com/aslanprp09-source/TheKaiN-Video/actions/runs/43574136079) signed versionCode 2 on source `c1b8752d8eb4dfa065704edfa8b9c891168764cf`. Its [original evidence record](../release/evidence/android-signing-run-69.json) is a historical snapshot before independent verification; the later [run #80 verification record](../release/evidence/android-verification-run-80.json) supersedes that snapshot's pending verification status for this original artifact only.

After reviewed PRs #23/#26/#27, [run #89](https://github.com/aslanprp09-source/TheKaiN-Video/actions/runs/44031800034) signed versionCode 3 on source `2b969ed9928758c11a19382026fa35d21f52c5eb` after a separate KaiN1One Environment approval. All required source checks, actual API26/29, packaging, rehearsal and the protected signer passed. Official SDK/JDK checks in that signer enforced the expected package/version and matching permanent certificate before upload. [Run #89 evidence](../release/evidence/android-qa-run-89.json) distinguishes signing-CI checks, external audit claims and owner feedback from separate independent official verification. `ci-signing-protection` and `android-signing` are PASS; `production_ready` and `automatic_publication` remain false.

The [CI hardening requirements and owner runbook](CI_SIGNING_SECURITY.md) now govern this path. Actions use reviewed commit SHAs. Before a protected job can be scheduled, a read-only audit requires the current manual rebuild ref/commit, actual independent reviewers, no administrator/self-review bypass and an exact deployment branch allowlist. Missing/unreadable protection stays BLOCKED. No environment or user signing key is created by this task.

The repository must have a protected GitHub **environment `release-signing`**, restricted to reviewed branches and required reviewers controlled by the owner. Configure secrets there: `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`; configure the public environment variable `ANDROID_SIGNING_CERT_SHA256` as the trusted certificate's 64 hex characters. Do not paste keys/passwords into chat, source, PRs or logs. This task does not create a key/environment or invent a signing identity.

After owner configuration, explicitly choose `sign_android=true` on rebuild. The job depends on the protection audit and unsigned/native packaging checks, then signs only the same-run artifact ID and independently verified APK/AAB/manifest hashes. It performs no Gradle/build/cache execution with a key. A private temporary mode-0600 keystore is removed before credential-free verification scripts run; failures remove partial signed outputs. No key upload, release action or publication step exists. APK apksigner and AAB jarsigner/keytool checks must match the configured public fingerprint. Missing secrets/certificate explicitly fail an opted-in signing request as BLOCKED. Preserve the key securely for future upgrades; backup/rotation/store enrollment are owner responsibilities. The manual Gradle signing configuration remains separate from this isolated CI artifact signer and is not used by the protected job.

Windows Authenticode remains BLOCKED; unsigned installer/EXEs are labeled accordingly. No user-controlled Windows certificate/service has been supplied. SmartScreen reputation and clean-machine trust prompts need real Windows QA.

## Artifact review and remaining priorities

The scoped [independent official-tools verifier](INDEPENDENT_ANDROID_VERIFICATION.md) actually ran in [#80 / 43869853934](https://github.com/aslanprp09-source/TheKaiN-Video/actions/runs/43869853934): all seven real stages PASS for the **original run #69 ZIP/versionCode 2**. [Report artifact 14228005379](https://github.com/aslanprp09-source/TheKaiN-Video/actions/runs/43869853934/artifacts/14228005379) and [Issue #6 result](https://github.com/aslanprp09-source/TheKaiN-Video/issues/6#issuecomment-6916927501) record the actual download, digests, manifest and official APK/AAB verification. This is more than fixture success, but does not verify run #89. Historical verifier code/source pins remain unchanged.

1. **Original artifact component — PASS, scoped to #69.** The new `android-original-69-artifact-review` entry records run #80's actual seven-stage result with source run/artifact/version. It is not a general production gate and does not change the old evidence snapshot or verifier pins.
2. **Current artifact review — BLOCKED, scoped to #89.** Run #89 artifact **14306540310** is 13,641,154 bytes; GitHub ZIP SHA-256 is `f0c73413ffdffeea516c9475e905ca2dbce47cb52e7a8fbd4808083791ae13f7`. Signing-CI official checks passed. A supplied external audit reports further ZIP/manifest/custom APK crypto/strict AAB checks; those statements are attributed in the evidence record, not rerun or adopted as an independent official-apksigner PASS. Separate verification of this **versionCode 3** APK by official `apksigner verify --verbose --print-certs` remains NOT_RUN. Use a trusted download, original ZIP hash, safe extraction, SHA256SUMS/manifest/source checks and official SDK35/JDK17 on a suitable verification host; retain tool logs and the exact run/artifact/hash. No private key or SDK installation on the owner's Windows machine is needed. If a new independent CI entry point is required, review it separately; do not repoint the historical verifier or substitute a new build.
3. **Physical-device acceptance — BLOCKED with positive owner feedback.** In response to the run #89 update/deletion checklist, the owner reported **«Все работает.»** on Samsung Galaxy S24 Ultra / Android 17 / One UI 9.0; this is recorded in PR #27 and the run #89 evidence file. A detailed case protocol and installed APK hash were not separately supplied. Preserve that result without inventing per-case PASS or treating it as the whole matrix. Continue physical checks below, including Android 8–9: installation, playback, notifications, background/force-stop/reboot, permissions/storage, accessibility and upgrade/data preservation. A valid upgrade case needs a higher versionCode and the same signing identity. Emulator success does not close physical or 16 KiB runtime gates.

The post-merge push run #68 initially had four UI failures in 29 tests (window focus, IME timeout and missing source input). Its failed job passed on a fresh runner in attempt 2 without code/assertion changes; run #69 also passed all 29 primary device tests. This observed instability remains a QA concern, not proof of an exclusively infrastructure cause. These historical CI results do not grant production release approval.

Later run #86 failed four of 41 primary UI tests before signing. Reviewed PR #27 added emulator readiness and diagnostics; post-merge #88 and signed #89 passed, including 41/41 primary API35 tests, cold restart and visual phases. New logs show Awake before/after preparation, so they do not prove historical sleep as the precise cause. Keep failure history and [readiness notes](ANDROID_CI_UI_READINESS.md); do not classify old #86 as a current failure on #89. Issue #15 (async Qt failure-fixture cleanup) remains OPEN and distinct from this Android fix.

## QA record to complete before release

For each run record exact commit, manifest/hash, OS/device/OEM/ABI, fixture/permission, steps, expected/actual, timestamp, logs/screenshots and PASS/FAIL/BLOCKED. Use owned/licensed content and redact URL tokens. Never convert an emulator or code inspection into physical evidence.

| Flow | Expected evidence / current automation | Remaining release check |
|---|---|---|
| Direct file and selected formats | Existing queue/native tests; installed Windows owned separate-track merge, ffprobe/decode/hash | Actual playback on clean Windows 11 and physical Android |
| Format accuracy, unknown sizes, absent variants | M1/M3/M5 tests; no invented quality/conversion | Inspect each advertised format with ffprobe; unsupported choice stays unavailable |
| Corruption / truncated / transfer 100% | Verification failure; no completed/library/open state | Device interruption and damaged-file playback cases |
| Restart, force-stop, recovery, duplicate click/output | M2 tests, M4 host restart hash/URI, M3/M5 submit guards | Reboot, upgrade, app/process kill and no-overwrite on physical targets |
| Network loss, redirects, 403/404/429, expiry, slow source | Owned servers, typed failures, M5 30 s total deadline/cancel tests | Wi-Fi/mobile switch, airplane mode, expiry/reanalysis, private/LAN policy review |
| Disk full, permission denial, destination removal | Typed-error tests; Android notification denial | Actual low-space/media-provider/removable-storage cases; no false success |
| Privacy | No cookies/accounts/telemetry; redacted typed diagnostics | Inspect actual logs, notifications, backups/queue files and network capture with signed URL |
| Accessibility | Windows Qt keyboard/200% tests, Android 48 dp/IME/130%/360–600 dp tests | Windows 100/150/200% real keyboard/screen reader; TalkBack, 200% font, gestures, hardware keyboard |
| Installer and uninstall | Disposable hosted Windows installation, frozen pipeline/GUI and uninstall | Snapshot of clean Windows 11 x64 without Python, media tools explicitly supplied; Start menu, reopen, rollback, paths with Cyrillic, data preserved |
| Android signatures / 16 KiB | Protected checks #69/#89; independent official #80 PASS only for #69; ELF/ZIP prerequisites | Independent official downloaded #89 APK verification, detailed upgrade continuity, real 16 KiB runtime/device |
| Storage retention / background | Recovery staging deliberately retained; one Android pipeline | Cleanup implementation/retention agreement; OEM idle/battery/foreground notification checks |

Physical matrix — **release coverage remains BLOCKED**; one positive owner report from Samsung is recorded separately. No physical device was operated by this Cloud task:

| Targets | Required coverage |
|---|---|
| Android 10 / 11, API 29 / 30 | MediaStore publication, permissions, download/open/restart, low space |
| Android 12 / 12L, API 31 / 32 | Background service/process restrictions, notification controls |
| Android 13 / 14, API 33 / 34 | Notification denial/enable and foreground start/recovery |
| Android 15, API 35 | Edge-to-edge/IME/gesture navigation and physical transfer/playback |
| Android 16+ / latest supported OS at QA time | Foreground limits, latest target policy and 16 KiB where supported |
| Pixel plus Samsung/Xiaomi or other representative OEM | Idle/battery restrictions, reconnect/restart and actual file visibility |
| Android 8 / 9 (if minSdk 26 remains supported) | App-specific Downloads/FileProvider and runtime compatibility; also unverified |

Android no-native-yt-dlp/commercial-site limitations from M5 remain. Do not add site compatibility to release notes unless a named authorized source/date/version has actual extraction, selected-file transfer and playback evidence. M6 does not silently remove the accepted M4 limitations: staging cleanup, OEM/OS behavior and incomplete every-frame native verification.

## License and source record

See [M6 dependencies](M6_DEPENDENCIES.md). Windows artifacts include exact installed distribution metadata/licenses, CPython license and pinned upstream Qt/PySide notice/attribution files; Qt notices are a superset for the selected modules. Android release assets include the resolved binary/hash inventory, reviewed-family source/license references and available archive-root notices. These materials assist review; they do not establish complete vendor/source correspondence or select an application license for the owner. Final distribution/source/license review remains BLOCKED in the gate record. No changes to legacy licensing are made.
