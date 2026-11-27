# Real progressive format selection in the internal main APK

Issue #62 follows approved PR #57. This is a small continuation of stage D,
not a declaration of YouTube, arbitrary-site, separate-track or mux support.

## Behavior

The existing format selector now includes extracted dimensions when known:
`320 × 180 · MP4 · —` versus `160 × 90 · MP4 · —`. A known height without
width appears as `180p`; wholly unknown dimensions add no quality claim.
Choices sort by declared height/width/FPS descending, with unknowns last;
equal/wholly unknown choices retain their source order.
Unknown filesize/FPS/codecs stay unknown. Existing format IDs, selection,
download submission and direct/static-source paths are reused.

For the explicit internal runtime build, paste:

```
https://raw.githubusercontent.com/aslanprp09-source/TheKaiN-Video/<build-source>/rewrite/android/app/src/androidTest/assets/owned-formats-page.html
```

Replace `<build-source>` with the exact receipt's source commit, not a branch.
Real isolated CPython/pinned yt-dlp reads the repository-owned HTML page and
returns two actual MP4 candidates. The HTML declares true dimensions and
AVC/AAC codecs; the pinned extractor currently drops the HTML video codec,
so video codec, filesize and FPS deliberately remain unknown in the result.
No title/size/quality is synthesized from a test oracle for the application.

The original 160×90 fixture is unchanged at commit `11fa103624275958f089d9566c7722fc64133094`.
The new synthetic 320×180 fixture is immutable at `5e3b70b75a3d70780e49e24ddbf64a10356f2c09`.
Generation, real media properties and independent hashes are documented in
`rewrite/android/app/src/androidTest/assets/OWNED_FORMATS.md`.

## Authority and unchanged safety boundaries

The application separately grants exactly these immutable owned MP4 URLs.
The extractor still returns `download_enabled=false`. The old C2 page stays
analysis-only; the old D1 download page grants only its original fixture.
The new two-format page may grant both. Transfer admission separately checks
the exact two-URL set, MIME and persisted isolated backend. Queries, mutable
branch aliases, different repository paths and explicit-port aliases are
refused before creating a network worker or staging file. Host membership
alone never grants a transfer.

The Python isolated UID, typed metadata/AIDL, no main-process Python, ordinary
app-UID trusted disposable HTTPS Service, public-address/all-DNS checks,
socket-pinned system TLS, no redirects, fixed 512 MiB/30 s transfer cap,
sampled resource limits and confirmed process/Binder/Service/lease retirement
are unchanged from `PROGRESSIVE_TRANSFER.md`. No new permission, executable,
native library or dependency is packaged. Developer-only FFmpeg generates
synthetic fixtures; Android does not run FFmpeg in this stage.

The existing Room schema/migration, WorkManager, verification and scoped
publication are unchanged. Distinct selected URLs and verified output URIs
survive Activity recreation and reopening the actual Room database. Neither
a successful network receipt nor the selector's source declaration can mark
a job completed without the existing media verification.

## Acceptance

`OwnedFormatsUiTest` adds two cases on each native API26/29/35/36 matrix entry:

1. Real main Home input/Analyze → actual isolated extraction of both variants.
   Open the real selector, check distinct dimension labels, explicitly choose
   the higher variant first and the lower one next. For each choice, assert
   the persisted URL/backend, distinct actual output bytes/SHA-256, one H.264
   video track of the selected dimensions, one AAC audio track, real samples
   in both tracks and Android MediaPlayer playback. Recreate the Activity,
   reopen the database and check both original records and Open actions.
   Retain selector screenshots, history screenshot and typed process receipts.
   Popup items expose their own resource semantics; the selector must have a
   full 48dp visible touch target before interaction. A failing attempt retains
   a bounded, URL-redacted hierarchy and screenshot before cleanup changes UI.
2. Exact-grant negative cases must refuse four ungranted aliases before network,
   identity/progress callbacks or staging. No direct-backend fallback.

`formats-smoke.sh` and `formats_report.py` require both raw successful cases,
all current-source/API/page identities, both independently pinned media
oracles, distinct jobs/process operations, isolated Python, confirmed process
cleanup, both-track samples, playback, history and three PNGs. Missing results
or screenshots fail. Host negatives reject stale/wrong source, wrong variant,
missing audio, duplicate output, missing death/lease/Service and false receipt
completion. Another host test runs the real pinned extractor offline under
the installed audit hook over the committed HTML, checking both candidates
and unknown fields against actual committed fixture bytes/hashes.

The accepted readiness fix (PR #65, merge `4086d26ec6048582cd5fe349d9e56133cc1c6183`)
adds two real configure/crash cases to the original93. All95 remain separate
mandatory gates; the two format cases make **97 per native API**. Both host
intake suites and both native drivers are retained after the normal base merge.
All original 41 main API35 tests and standard
API26/29, JVM, Lint, host and CI-policy gates remain intact. Results and resource
measurements belong to the exact-HEAD PR evidence, not compilation claims.

## Limits

Physical ARM64/current-source Samsung acceptance remains NOT_RUN. #46 and #61
remain unconfirmed; a successful emulator retry does not fix either. Sampled
RSS/CPU are not instantaneous hard limits or physical-device budgets. No
separate audio/video merging, unsupported format enablement, source thumbnail
invention, long-transfer acceptance or YouTube support is added. APK #89,
Windows race, long background, deep Doze and existing infrastructure issues
remain separate open work. `production_ready=false`; `automatic_publication=false`.
