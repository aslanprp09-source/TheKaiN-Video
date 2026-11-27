# Internal main-APK progressive transfer (D1)

Issue #56, following PR #54. This stage connects a real isolated extractor result to the existing application's downloader. It does not complete the broader separate-track/mux/YouTube stage.

## User path and explicit grant

Build the **existing** `com.thekain.video.rebuild` debug APK with `-PisolatedRuntimeDir=/verified/same-run/package` as in `MAIN_ANALYZER.md`. The ordinary build retains direct/static analysis and contains no Python runtime. Internal release tasks remain refused.

In the internal APK paste this URL, replacing `<source>` with the exact build receipt's commit:

```
https://raw.githubusercontent.com/aslanprp09-source/TheKaiN-Video/<source>/rewrite/android/app/src/androidTest/assets/owned-download-page.html
```

The existing Home → Analyze action calls the real isolated CPython/yt-dlp source adapter. The app accepts only the actual returned URL of the immutable repository-owned `owned-sample.mp4` at commit `11fa103624275958f089d9566c7722fc64133094`. Python's result still has `download_enabled=false`; metadata never grants transfer authority. The app separately grants this one candidate `ISOLATED_OWNED` transfer. The previous `owned-isolated-page.html` remains analysis-only, including its original disabled-download regression test.

Home shows actual available formats, size if known, and available resolution/FPS/video/audio codec declarations. Unknown fields stay `—`; an MP4 extension does not imply an audio track. The owned fixture currently supplies one progressive candidate, not fabricated quality choices or separate audio selections. No thumbnail is invented when the source supplies none. Separate/fragmented/live/DRM formats remain excluded by the unchanged normalizer. No FFmpeg executable or dependency is added.

Download uses the existing Room job, staging, verifier and publisher. Completion requires actual media tracks/samples, a decoded video frame, SHA-256 and verification of the published URI. A successful HTTP receipt is never a completed download. The resulting URI can be opened using the existing action; history survives Activity recreation and a separately reopened database.

## Streaming boundary and limits

The trusted, nonexported ordinary-app-UID `:https_broker` Service performs all new transfer DNS/TLS/HTTP. It never loads Python. The main process does not execute those network primitives. Python still runs only under the isolated UID and never receives the streaming endpoint or a writable output descriptor.

The app grants one transfer mode in bounded typed AIDL configuration, with owner UID/PID, UUID capability, one-shot operation and the existing read-only kernel guard. Analysis configuration cannot call the streaming method or obtain its larger byte budget. Streaming configuration cannot call the analysis fetch/chunk APIs. External callers cannot configure either mode.

Only a writable **FIFO/pipe** descriptor is accepted: regular files, read-only descriptors and paths are refused. The Service copies through an 8 KiB buffer into the bounded kernel pipe. The app reads at most 32 KiB per primitive and writes staging only from its cancellable coroutine. Late read completion owns no output file and cannot publish bytes or progress. Pipe backpressure bounds queued data; neither process buffers the complete media response. The authenticated Service has the app UID's filesystem permissions: this is trusted-code process separation, not a sandbox for untrusted Java/native code.

Public-address validation (all DNS answers), socket pinning, system TLS/hostname verification, disabled proxies/cookies/auth/retries and strict HTTP/1 framing remain inherited from C1/C2. D1 additionally refuses **all redirects before another request**, including same-origin redirects. The final receipt must match the original URL. Unknown/chunked/duplicate framing and compressed responses are refused before entering their body parsers. No arbitrary request headers, filesystem paths, Python, executable downloads or browser credentials are accepted.

- Fixed transfer maximum: **512 MiB**, independently validated in framing, copy loop, receipt and owner reader. Analysis limits remain **512 KiB per response / 2 MiB aggregate**.
- Unchanged maximum operation deadline: **30 s**, shared as an absolute elapsed-realtime deadline across owner, Binder and Service. A shorter 5 s transfer test is additional coverage, not a changed analysis budget. Long media transfers are currently outside D1 acceptance.
- Existing sampled network RSS maximum: **384 MiB**, CPU maximum **10 s**, sampled every **40 ms**. These are internal candidate limits, not physical-device budgets or instantaneous hard limits. Pipe/file cache accounting is not equivalent to RSS.
- Existing retirement bound: **2500 ms**. Return to verification requires actual broker kernel absence, dead Binder, absent ServiceRecord, drained handshake/transfer RPC/read work, unchanged canary and exclusive lease guard. Unknown cleanup keeps admission blocked.
- No fake pause/range resume. A partial nonresumable transfer after process loss fails closed; an explicit retry creates a new attempt/staging path. Existing failed-attempt staging retention is unchanged.
- HTTP 403/404/429 remain distinct. No automatic retry is introduced around an unknown/long `Retry-After`. Safety/resource/cleanup failures have separate localized errors. No raw URL/query/header/exception is written to evidence.

## Persistence and compatibility

Room schema **1 → 2** adds `backend TEXT NOT NULL DEFAULT 'DIRECT'`. No destructive fallback is configured. Existing task IDs, output URIs, digests, bytes, state and files remain unchanged. New backend identity survives retry/reopen. A standard APK refuses an isolated-only persisted job; it cannot silently downgrade it to direct `HttpsURLConnection` transport. Installing an older schema-1 APK after schema-2 migration is not supported; preserve the installed app and data rather than uninstalling it.

The actual standard APK additionally executes `IsolatedBackendRefusalTest` on API26/29/35, after the unchanged three direct HTTPS regressions. Even the allowed immutable fixture URL is refused for a persisted isolated backend before opening a connection or staging file. The original 41-test API35 inventory is unchanged.

No new Android permission, dependency version, key, signing environment, ruleset or publication path is introduced. Android minSdk remains 26. The existing API26 app-private / API29+ MediaStore publication paths are used unchanged.

## Additional acceptance gates

`transfer-smoke.sh` runs after the existing C2 checks on each native API26/29/35/36 matrix entry. It never replaces the original 84 unique cases or the separate main product gates.

- **4 remote tests:** exact streaming of 2 MiB + 17 bytes (larger than analysis budget); redirect/TLS/framing/size/truncation/HTTP refusals and fresh workers; actual interruption-resistant DNS cancellation/deadline with explicit failure and kernel death; capability, regular-file descriptor and analysis-grant refusals.
- **4 main backend tests:** corrupt-file refusal then verified retry; real HTTPS cancellation/deadline, no late writes and fresh verified files; no-space failure and persisted scope refusal without direct fallback; real schema-1 migration preserving history, followed by durable isolated backend identity.
- **1 actual main UI test:** real owned page → isolated analysis → allowed real MP4 → WorkManager transfer → verified publication → Android MediaPlayer playback → recreated Activity/reopened history → visible Open action and retained screenshot.

Strict current-source `transfer_report.py` requires every raw JUnit result and all typed proofs, explicit deadline failures, receipt, exact fixture SHA/bytes, screenshot, playback, history and actual cleanup. Missing output is never treated as a timeout PASS. Host negative intake tests reject stale/wrong-source/wrong-page/only-Binder/lease/receipt/playback/migration evidence. The full CI results and exact HEAD belong in the PR; local compilation using cached native assets is not source-build/device proof.

Reproduce the new groups on a disposable emulator only:

```
adb shell am instrument -w -r -e class com.thekain.runtime.lab.RemoteDownloadTest com.thekain.runtime.lab.test/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument -w -r -e class com.thekain.runtime.lab.MainProgressiveTransferTest com.thekain.video.rebuild.test/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument -w -r -e class com.thekain.video.OwnedProgressiveUiTest com.thekain.video.rebuild.test/androidx.test.runner.AndroidJUnitRunner
```

Full matrix uses the existing `rewrite-ci.yml` native dispatch with packages/signing/artifact-verification disabled. Same-run native source build, legal inventory, official ELF/ZIP 16 KiB verification and debug APK receipts remain mandatory. Never install a differently signed APK by deleting the user's existing app/data.

## Remaining limitations

Current-source physical ARM64, Samsung full acceptance and prolonged background/deep Doze remain unconfirmed. This PR does not establish arbitrary-site/YouTube support, separate tracks, mux, thumbnails without source metadata, long-transfer budgets or release readiness. APK #89 independent review (#38), Windows race (#39), background (#40), Doze (#41), physical acceptance (#43), API35 ANR (#45), Samsung #46 and API26 infrastructure #55 remain separate open work. `production_ready=false`; `automatic_publication=false`.
