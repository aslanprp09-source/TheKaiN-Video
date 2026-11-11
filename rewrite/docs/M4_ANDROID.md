# M4 — persistent Android direct transfer

Issue: [#4](https://github.com/aslanprp09-source/TheKaiN-Video/issues/4). Base: `rebuild-2026-11-11`. This is a development milestone; physical-device release validation is pending.

## Behavior and ownership

`domain/Transfer.kt` owns URL validation, states, progress and typed errors. `data/Downloads.kt` persists each UUID/attempt and publication intent in Room (exported schema v1). `transfer/DirectTransfer.kt` owns HTTPS streaming; `DownloadEngine.kt` coordinates transfer → verification → publication. `storage/` owns native verification and scoped storage. `AppGraph` owns the application scope and recovery; a unique WorkManager job contains only the task ID. The Activity observes persisted tasks and submits commands. No DownloadManager pause emulation remains.

Android concurrency is one pipeline. Sources must be direct HTTPS MP4/WebM/MOV/MP3/M4A URLs; no page extractor, cookies or account access. System TLS trust and hostname validation remain enabled. Only tests inject trust for their generated local CA. Signed source URLs stay in the private Room database, with backups disabled; diagnostics and WorkManager inputs exclude them.

Unknown total stays unknown. Download progress is capped at 99 until verified output is published. Media verification checks nonempty/expected length, container signature, expected track type, all native extractor samples, a decodable first video frame and SHA-256. Publication verifies the destination media and digest again. This is **not** a full decode of every video/audio frame. Unsupported/encrypted media fails verification; no DRM bypass. The verifier currently bounds video dimensions to 4096 and samples to an 8 MiB buffer.

API 29+ reserves a UUID-named MediaStore Downloads row with `IS_PENDING=1`, saves its URI, copies and verifies it, then clears pending before COMPLETED. Recovery reuses an existing owned reservation/output; it does not overwrite published wrong content. API 26–28 uses app-specific external Downloads and FileProvider, with a verified temporary file and move without replacement. Such files are removed by Android on uninstall. No broad storage permission is requested.

Pause is available only after an actual 206 byte probe, strong ETag and positive partial bytes. Resume requires exact Content-Range, matching ETag and byte offset; a server returning 200 cannot silently append. Unproven partials after interruption become an actionable failure; explicit retry creates a fresh attempt. Cancellation closes the connection before acknowledging. Retryable network/server failures have a three-failure budget and WorkManager exponential backoff. Retry-After seconds and HTTP dates persist a no-earlier-than timestamp; cooldowns over one day require deliberate retry. Publication failures retain the URI/digest so retry does not duplicate an output.

Foreground work uses the dataSync service type, low-importance progress notifications and an immutable cancellation action. Notification permission is requested by the UI; refusal does not prevent the transfer or request storage permissions. Android/OEM foreground restrictions may still prevent a job; the UI has an actionable failure. Force-stop suspends Android scheduling until the user opens the app again; the app does not promise execution while force-stopped.

Private staging is retained for recovery, including completed/canceled attempts. Automatic retention cleanup is deferred; it can consume additional storage. App data deletion/uninstall removes the queue and staging. Modern public Downloads output remains independent. Room disk-full errors are classified when a state write is possible; a completely full database volume cannot guarantee a new persisted error row.

## Reproducible automated checks

JDK 17, SDK 35, Gradle 8.11.1:

```sh
cd rewrite/android
gradle --no-daemon :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
python3 tools/report-tests.py app/build/test-results/testDebugUnitTest
```

Local results: 14 JVM tests passed; debug app and instrumentation APK compiled. No local Android target is available in this cloud environment. Native execution evidence is recorded separately after CI actually runs.

On a disposable API 35 emulator, install both APKs, grant notifications, then:

```sh
gradle --no-daemon :app:installDebug :app:installDebugAndroidTest
adb -s emulator-5554 shell pm grant com.thekain.video.rebuild android.permission.POST_NOTIFICATIONS
gradle --no-daemon :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.notClass=com.thekain.video.ProcessRestartTest,com.thekain.video.NotificationDeniedTest
ANDROID_SERIAL=emulator-5554 bash tools/process-restart-smoke.sh /tmp/m4-evidence
```

The instrumentation suite covers actual HTTPS and native MediaStore/Room/verifier behavior: owned MP4, chunked unknown length, corrupt full transfer, 403/404, 429/503, deterministic disk exhaustion at transfer/publication, distinct duplicate names, queued/active cancel, real pause/206 resume, reopened Room partial recovery, URI reuse after publication interruption, API 28 publisher branch, Activity recreation and foreground WorkManager completion. The host script additionally revokes notifications **before** starting a separate test, then tests a real force-stop/relaunch with different PIDs, preserved completed URI/hash, and an interrupted partial. Each host phase must report exactly one successful test. No device-wide data reset or deletion of unrelated outputs is used.

The fixture `app/src/androidTest/assets/owned-sample.mp4` was generated locally with FFmpeg: 160×90 solid-color video at 10 fps for one second, H.264 baseline/yuv420p, silent AAC at 8 kHz, MP4 faststart. No third-party media or credentials. ENOSPC uses deterministic injected free-space/publication failures rather than filling a runner disk.

The workflow uploads the debug APK and `M4-Android-device-validation` with native XML/reports, force-stop test logs, adb screenshots/UI trees and emulator/crash logs. Test-count annotations distinguish executed checks from compilation. Screenshots are evidence of the M4 native debug UI, not Compose design acceptance.

## Manual release follow-up (pending)

1. Install the debug APK on physical Android 10+ and an older supported device. Use an owned HTTPS clip; verify open/play in an external player and file visibility in Downloads. Check unknown-length and error endpoints.
2. Deny notifications, download, then grant permission and check foreground/cancel behavior. Test screen off, background, network loss/reconnect and OEM battery restrictions; do not expect work during force-stop.
3. With a server supporting strong ETag and Range, pause/resume; then change the remote entity and confirm failure rather than appending. Kill the process mid-transfer and during publication, reopen, inspect a single verified output.
4. Download the same name twice; compare both files. Check near-full internal/output storage and revoked destination access. Retained staging should be accounted for.
5. Android 8–9 fallback is exercised as a publisher branch on API 35; native 8–9 validation, Android 360/412/600 dp, TalkBack, Compose, device performance, signed APK/AAB, installers and release eligibility remain pending.
