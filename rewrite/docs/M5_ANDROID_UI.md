# M5 — Android Compose interface and bounded source discovery

Issue: [#5](https://github.com/aslanprp09-source/TheKaiN-Video/issues/5). PR base: `rebuild-2026-11-11`, following M4 merge `4d3ecef`. This milestone is a development build, not signed distribution or physical-device release approval.

## Interface

Material 3 Home, Downloads, Library and Settings use the approved dark/light palette, Android system typography, 48 dp actions, system/IME insets and the existing approved K/play/download logo transcribed from the Windows SVG into a vector drawable. System/Dark/Light and Russian/English choices persist locally. Russian is the initial UI language independently of the emulator's locale. Both languages are packaged together; notification copy uses the selected locale too.

Home performs explicit Paste/Clear → asynchronous Analyze → preview/actual file variants → Download. The footer CTA remains in the available window above the keyboard. Missing/invalid/oversized posters produce a neutral 16:9 placeholder, not invented media. Long titles are limited to two lines; the body scrolls. The 600 dp layout places preview and source details side by side. The format menu contains discovered files only, with real header sizes or “—”; no resolution, duration, conversion or size is fabricated. A single available file disables the selector with an explanation. Audio-only appears only when an actual audio source is present.

Downloads has name search, All/Active/Completed/Failed filters and authoritative Room states. Pause requires the M4 proof of Range/ETag and positive partial bytes. Verification/publication are indeterminate; only COMPLETED can appear in Library or expose Open. Open grants read access for the actual persisted content URI and explains missing files/players. Settings explains the fixed scoped destination, one concurrent pipeline and notification behavior. There are no inert folder/concurrency controls or background clipboard reads.

Keyboard: Ctrl+L returns Home and focuses the link, Enter/IME Go analyzes, Ctrl+Enter downloads the ready Home choice, Escape dismisses input focus/IME and cancels active analysis. System Back closes IME/dialogs first, returns from Settings to its parent, and returns other tabs to Home. URL edits immediately cancel/invalidate old results. `HomeSession` synchronously locks submission, ignores unrelated acknowledgements, and requires explicit confirmation for another copy of a source already accepted in the session.

`presentation/` observes Room and issues application commands; it does not perform transfers, parse HTML or write SQL. `MainActivity` only owns Android clipboard, settings, file-open and permission interactions. The accepted M4 transfer/verifier/publisher remains unchanged. Only its notification resource lookup now honors the UI language. ViewModel state survives Activity recreation; process restart preserves the Room queue and draft without automatically re-enqueuing input.

## Supported sources

`SourceAnalyzer` is a typed, GUI-independent boundary. `HttpsSourceAnalyzer` currently supports direct HTTPS MP4/WebM/MOV/MP3/M4A and static UTF-8 HTML5 pages exposing `video[src]`, `audio[src]` or their `source[src]` children. It resolves relative URLs, accepts at most eight actual supported file candidates, checks their headers and maps HTTP/network errors. HEAD 405/501 falls back to a GET byte probe; a 206 probe uses Content-Range total rather than the one-byte response length.

Analysis uses system HTTPS trust/hostname checks, no cookies or credentials, at most six redirect requests, 8 s connection/5 s read timeouts, a 512 KiB HTML limit and 1 MiB poster limit. Each redirect is revalidated. Posters are restricted to the page origin even across redirects; other cross-origin scripts/images are not fetched. Preview decoding is bounded to 2048×2048. Cancellation invalidates UI state immediately; an in-flight blocking socket read may take up to its timeout to unwind. Source URLs may have expiring query tokens; typed errors and custom model diagnostics omit them.

HTML declarations/header sizes are candidates, not proof that a file is valid media. M4 still downloads to staging and verifies before success. JavaScript players, blob/data/HTTP URLs, HLS/DASH, playlists, encrypted media and access-controlled content are unsupported. No commercial video service compatibility is advertised. Only owned local HTTPS fixture pages have been tested. See [the adapter investigation](M5_SOURCE_RESEARCH.md) for why a native yt-dlp/Python/JS bundle is not included.

## Reproducible validation

JDK 17, SDK 35, Gradle 8.11.1:

```sh
cd rewrite/android
gradle --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
python3 tools/report-tests.py app/build/test-results/testDebugUnitTest
```

Local validation so far: 29 JVM tests passed, Lint has no errors, and app/instrumentation APKs compile. Native execution evidence is recorded in the PR after the CI run completes; compilation is not a device test.

The main API 35 instrumentation run retains all M4 engine tests and adds an owned HTTPS HTML page with MP4/WebM/M4A choices, a real selected-WebM download verified through M4/MediaStore, and Compose interaction tests. M4 Activity tests now exercise actual Analyze → Download before checking the same queue/output after recreation. The force-stop host script uses the Compose navigation tag's real UI-tree bounds and still requires a different PID plus unchanged output URI/hash.

The visual host script changes the actual emulator display size/density to 360/412/600 dp and executes one renderer test per theme/width. Seven states per configuration produce **42 PNGs**: Home empty/ready, Downloads running/failed/completed, Library and Settings. Widths are asserted on device. These screenshots render explicit `UiFixtures` models; their progress/completed values are test fixtures, not live transfer claims. Separate M4/HTML integration tests establish real pipeline behavior. The visual report validates PNG dimensions and produces a contact sheet; full originals remain in the Actions artifact. A bounded compressed review bundle is also emitted in check annotations so a restricted cloud executor can inspect the rendered evidence through the GitHub API when artifact-storage hosts are unavailable.

On a disposable API 35 emulator after installing both APKs:

```sh
adb -s emulator-5554 shell pm grant com.thekain.video.rebuild android.permission.POST_NOTIFICATIONS
gradle --no-daemon :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.notClass=com.thekain.video.ProcessRestartTest,com.thekain.video.NotificationDeniedTest,com.thekain.video.M5VisualTest
ANDROID_SERIAL=emulator-5554 bash tools/process-restart-smoke.sh /tmp/m4-evidence
ANDROID_SERIAL=emulator-5554 bash tools/compose-visual-smoke.sh /tmp/m5-evidence
```

Fixtures derive from the already-owned solid-color/silent M4 clip: WebM is VP9 video-only, M4A is its AAC audio track and the PNG is its first frame. No third-party copyrighted video, account or secret is used.

## Remaining device/website/release work

Physical Android devices, native Android 8–9/10 and newer-version behavior, OEM background restrictions, TalkBack traversal, real hardware keyboards and gesture navigation still need manual testing. Automatic staging cleanup remains unimplemented; additional storage is retained for recovery. M4 native packet/first-frame verification is not a full decode of every frame. These accepted M4 limits remain in force; see [M4_ANDROID.md](M4_ANDROID.md).

Before claiming a real site's compatibility, test an explicitly authorized/licensed page and actual selected files, redirects, expiry, denial, unknown sizes and playback. Document site/date/adapter version. No such third-party website claims or signed-release readiness follow from emulator/fixture success. Future native adapters must meet the license/update/ABI/OS gates in the research document before bundling.
