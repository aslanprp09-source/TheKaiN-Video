# M6 — reproducible experimental packages and release gates

Issue [#6](https://github.com/aslanprp09-source/TheKaiN-Video/issues/6), base `rebuild-2026-11-11` after M5 merge `7f09a29`. No publishing, tag creation or changes to archival branches/releases. **Production release remains BLOCKED.** The machine-readable [gate record](../release/qa-gates.json) deliberately separates missing evidence from automated success.

## Outputs and identity

Windows: unsigned x64 onedir portable ZIP and per-user Inno Setup installer, named `0.1.0-m6-<commit>-…-unsigned`. The installer requires Windows 11, defaults to `%LOCALAPPDATA%/Programs/TheKaiNVideo-Rebuild` and has its own AppId/shortcut. It does not migrate old installations or delete queue/download data on uninstall. Do not point an unattended QA install at a user's app directory. Queue storage remains the accepted M2 location; manual QA uses an isolated `THEKAIN_DATA_DIR`.

Python and dynamically loaded Qt are included. A dedicated console `TheKaiNExtractor.exe` handles yt-dlp; the windowed GUI has no usable stdio and must never relaunch itself with `-m yt_dlp`. Analysis and transfer share the worker selector; `CREATE_NO_WINDOW` and redirected pipes remain in the existing process runner. Tests cover the frozen path with spaces. Qt DLLs remain replaceable in `_internal`; no one-file encryption/obfuscation or updater is introduced. The approved SVG renders the installer/executable icon.

FFmpeg/FFprobe are **external PATH prerequisites**, excluded from the installer. The application already disables download when these verification tools are missing. For the reproducible QA baseline, `prepare-tools.ps1` obtains Gyan's upstream-linked FFmpeg 9.0.2 essentials build and Inno Setup 7.1.1 only after matching pinned SHA-256 values in [tools.json](../release/windows/tools.json). FFmpeg is a GPL-enabled CI tool, not a redistributed component here. Installing the app alone does not satisfy this prerequisite; no player/site/runtime functionality is inferred from that install.

Android: `com.thekain.video.rebuild`, versionCode **2**, versionName **0.1.0-m6**, minSdk 26, target/compile SDK 35. Default release APK/AAB are unsigned, non-debuggable internal review artifacts. An unsigned APK is not installable. Existing debug APK remains a separate testing output. An optional protected job creates **signed internal QA** output, never a public/production release. Debug and release share the rebuild applicationId: signatures may prevent replacement; do not uninstall a user's build/data to bypass that check.

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

Manual CI without modifying main: **Clean rebuild → Run workflow → task branch → `build_packages=true`, `sign_android=false`**. The existing workflow calls the packaging workflow from the same commit; ordinary pushes do not package/sign. CLI:

```sh
gh workflow run rewrite-ci.yml --ref codex/issue-6-packaging -f build_packages=true -f sign_android=false
```

The original debug/JVM/emulator jobs still run. Packaging adds native Windows and Android release checks. The optional signing job being skipped is expected, not evidence of a signature.

## Signing access — BLOCKED until configured securely

The [CI hardening requirements and owner runbook](CI_SIGNING_SECURITY.md) now govern this path. Actions use reviewed commit SHAs. Before a protected job can be scheduled, a read-only audit requires the current manual rebuild ref/commit, actual independent reviewers, no administrator/self-review bypass and an exact deployment branch allowlist. Missing/unreadable protection stays BLOCKED. No environment or user signing key is created by this task.

The repository must have a protected GitHub **environment `release-signing`**, restricted to reviewed branches and required reviewers controlled by the owner. Configure secrets there: `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`; configure the public environment variable `ANDROID_SIGNING_CERT_SHA256` as the trusted certificate's 64 hex characters. Do not paste keys/passwords into chat, source, PRs or logs. This task does not create a key/environment or invent a signing identity.

After owner configuration, explicitly choose `sign_android=true` on rebuild. The job depends on the protection audit and unsigned/native packaging checks, then signs only the same-run artifact ID and independently verified APK/AAB/manifest hashes. It performs no Gradle/build/cache execution with a key. A private temporary mode-0600 keystore is removed before credential-free verification scripts run; failures remove partial signed outputs. No key upload, release action or publication step exists. APK apksigner and AAB jarsigner/keytool checks must match the configured public fingerprint. Missing secrets/certificate explicitly fail an opted-in signing request as BLOCKED. Preserve the key securely for future upgrades; backup/rotation/store enrollment are owner responsibilities. The manual Gradle signing configuration remains separate from this isolated CI artifact signer and is not used by the protected job.

Windows Authenticode remains BLOCKED; unsigned installer/EXEs are labeled accordingly. No user-controlled Windows certificate/service has been supplied. SmartScreen reputation and clean-machine trust prompts need real Windows QA.

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
| Android signatures / 16 KiB | Actual signature-state and ELF/ZIP checks | Configured owner signature, upgrade continuity, real 16 KiB runtime/device |
| Storage retention / background | Recovery staging deliberately retained; one Android pipeline | Cleanup implementation/retention agreement; OEM idle/battery/foreground notification checks |

Physical matrix — **all currently BLOCKED**, not attempted in this cloud:

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
