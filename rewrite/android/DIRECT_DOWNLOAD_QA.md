# Main Android direct-download reliability — issue #48

This change is stage A of the personal Android application request. It does not
integrate the Python laboratory or advertise YouTube support.

## Behavior and boundaries

`DirectTransfer` registers cancellation before each blocking HTTPS operation.
Only connection status, stream acquisition and bounded reads run in dispatched
network primitives. Those primitives have no staging writer, publication code or
progress callback. Cancellation disconnects the connection; a primitive returning
late checks its caller's cancellation and disconnects again. The caller checks
cancellation before writing bytes. Existing 10-second connect and 5-second read
timeouts remain unchanged. This is cooperative socket cancellation, not a hard
OS-level bound on every vendor network stack or DNS resolver.

`DownloadEngine` restarts an unvalidated partial from byte zero only when its
persisted state is `RETRY_WAIT`. It deletes only that task/attempt's private staging
file, clears its untrusted identity and retains the bounded retry counter. Strong
ETag/Content-Range resumption is unchanged. Ordinary crash recovery of an
unproven `DOWNLOADING` partial still fails with `INTERRUPTED` rather than appending
unrelated bytes. Verified publication and terminal rows remain authoritative.

The pre-transfer free-space check subtracts the reserve from available space
before comparing a remote length, avoiding signed overflow for enormous lengths.

## Automated evidence

Four additional JVM tests exercise stalled status cancellation, a late read, a
late connection result and an overflowing declared size. All original tests are
retained. `DirectDownloadReliabilityTest` is a separate additional three-test
gate on real Android API 26, 29 and 35, using only owned HTTPS/media fixtures:

1. Cancel an actual delayed HTTPS body before the existing socket timeout; assert
   durable `CANCELED`, no published URI/digest and no subsequent staging writes.
2. Disconnect midway through a non-Range response; persist `RETRY_WAIT`, reopen
   Room, fetch a complete response from zero, verify its exact bytes/hash and
   idempotent completion without another request or output URI.
3. Use the main Activity's actual analyze/download buttons and WorkManager;
   verify the published file, play its URI with Android MediaPlayer, recreate the
   Activity, reopen Room and find the verified history row and open action.
   This proves real platform playback of the URI; it does not certify every
   external player application's intent handling.

The primary API35 suite still requires exactly **41 executed tests with no
skips**. The three additional tests run through a separate instrumentation call;
they do not replace those 41 or overwrite their XML. Existing cold-process,
notification, removal, visual-layout, host, JVM, Lint and CI-policy gates remain.
The new gate rejects missing results, skips, failures and aborted instrumentation.
Test output, runtime API identity and UI evidence are uploaded with each device
job. A local compile is not evidence that an emulator or physical device passed.

To reproduce on a disposable configured emulator after installing both APKs:

```sh
cd rewrite/android
gradle --no-daemon :app:installDebug :app:installDebugAndroidTest
# API 33+ only, for the granted-notification main-UI case:
adb shell pm grant com.thekain.video.rebuild android.permission.POST_NOTIFICATIONS
ANDROID_SERIAL=emulator-5554 bash tools/direct-reliability-smoke.sh /tmp/thekain-direct-evidence
```

## Internal APK receipt and installation

The `android-debug` CI artifact contains the **main application** `app-debug.apk`
and `build-report.json`. Official SDK `apksigner` verifies the APK before upload;
official `aapt` reads its actual package, version, debug flag and SDK limits. The
receipt records the source head, tested checkout (a PR may use a merge checkout),
SHA-256, certificate digest and dirty-tree indicator. It is build evidence, not
independent artifact approval. `production_ready=false` and
`automatic_publication=false` remain explicit.

Check the receipt and APK SHA-256 before installation. This is a debug-signed APK,
not a release-signing build. Its certificate may differ from the installed QA
application, and the disposable CI debug key may differ between builds. A
signature/version conflict is a stop condition: **do not uninstall the existing
app or erase its data**. An update retaining existing data requires separately
authorized signing with the matching protected certificate and an appropriate
reviewed versionCode. No protected key is used by this change.

## Remaining acceptance

CI results and the exact reviewed SHA are recorded in the PR, not assumed here.
Physical Samsung acceptance of this new build is still
`IMPLEMENTED_NEEDS_DEVICE_QA` after successful CI; the earlier positive direct-MP4
test does not validate these new changes. No repeat Samsung testing is requested
until a suitable reviewed installable build is available.

Issue #46's missing isolated-worker deadline remains a separate **BLOCKED**
physical acceptance problem; this change does not claim to resolve it. APK #89
independent review (#38), the Windows callback race (#39), prolonged physical
background operation (#40), deep Doze (#41), and existing laboratory acceptance
tasks remain open. Native Python/YouTube integration, real split audio/video muxing
and expanded source support require subsequent scoped PRs and independent review.
