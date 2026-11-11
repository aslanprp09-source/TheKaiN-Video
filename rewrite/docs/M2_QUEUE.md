# M2 — persisted queue, verified publication and restart recovery

Issue #2; based on the accepted M1 merge `70d78e9` in `rebuild-2026-11-11`. All implementation changes stay under `rewrite/`. This document supersedes M1's queue/verification limitations; it does not establish release readiness on a Windows device.

## Pipeline and persistence

`DownloadQueue` owns scheduling, transport, verification and publication; Tk only issues commands and consumes immutable `DownloadJob` snapshots. Two workers run by default; configuration accepts 1–3. A held OS file lock prevents a second instance from owning the same queue. SQLite transactions guard typed state transitions, attempts, timestamps, bytes, resume capability and output evidence. Schema v1 is created in a transaction, existing jobs survive reopening, and unknown future schema versions are rejected without resetting data. Future incompatible changes must provide ordered migrations before increasing `user_version`.

State path: QUEUED → DOWNLOADING → optional POSTPROCESSING → VERIFYING → PUBLISHING → COMPLETED. Failure, retry-wait, acknowledged pause and cancellation have explicit transitions. Completed/canceled rows are immutable. Manual retry starts a numbered attempt; transient 429/5xx/network failures retry at most three attempts by default, with persisted exponential backoff and `Retry-After` support. 403/404, storage errors and verification failures do not automatically retry.

Each task/attempt uses `staging/<uuid>/<attempt>/` within the private data directory. yt-dlp never writes directly into the destination folder. Both FFmpeg and FFprobe are required before transfer. Verification checks nonempty output, expected extension/container and required audio/video tracks; known single-track byte sizes must match. FFmpeg then decodes the entire file with error termination and two decoder threads. SHA-256 and size are recorded before publication. Received bytes can equal total bytes while the job is VERIFYING or FAILED; progress is capped at 99 until COMPLETED. The UI uses an indeterminate bar for verification/postprocessing and does not invent speeds or ETAs.

`PUBLISHING` is a persisted commit intent. The publisher sanitizes Windows filenames, checks for symlinks/existing targets, and uses atomic no-overwrite hard links, choosing numbered names on collisions. Across volumes, it copies into a unique temporary file on the destination volume, fsyncs, checks the digest and links it into the final name. Destination files are never replaced. This requires hard-link support (normally NTFS for Windows). Unsupported filesystems produce an explicit storage error; there is no unsafe overwrite fallback.

## Resume, cancellation and recovery

- The Tk page-downloader uses yt-dlp. Pause remains disabled for this backend. yt-dlp re-extraction/download retries use new attempt folders; an interrupted page download is FAILED/INTERRUPTED and requires explicit retry. A `.part` file is not proof of resumability.
- An explicit direct-media API is available with `queue.enqueue(options, title, backend='http')`. It does not analyze webpages or merge tracks. A real `bytes=0-0` 206 response, matching total and a strong ETag are required to enable pause. Continuation uses Range/If-Range and verifies the exact Content-Range and ETag **before appending**. Servers ignoring Range or changing identity fail with RESUME_UNSUPPORTED, retaining the partial file. Fully received staging proceeds to verification rather than another transfer.
- Pause is PAUSING until the worker stops and acknowledges PAUSED. Cancellation interrupts transport/tool processes. It retains partial/staged files and never publishes them as completed. Cancellation is disabled once the PUBLISHING commit boundary is reached; an atomic save is then finished or recovered.
- On restart, queued work resumes scheduling. Active direct partial jobs with checkpoints become PAUSED with UNKNOWN capability and require explicit, revalidated continuation. Unknown page-download interruptions become FAILED. A job interrupted during VERIFYING reuses its staged artifact and verifies it again. PUBLISHING recovery recognizes matching size/hash at the persisted intended output and completes the same result without another download or duplicate target. Already completed outputs are neither re-run nor overwritten.
- Closing the window stops new scheduling and shuts workers down off the UI thread. A queue lock is retained until workers stop. Long atomic publication may delay shutdown; forced process termination is reconciled on next start.

Staging and previous attempt files are retained, including after success (same-volume links share file data). Cross-volume saves can therefore retain an additional staged copy. There is no automatic deletion/history cleanup in M2; clean the private staging tree only with the app stopped and after retaining any wanted partials. Do not delete the database if recovery is needed.

## Privacy and process boundaries

The database contains source URLs needed for future attempts, potentially signed query strings; it is application state, not a diagnostic log. Use private local storage, not a shared folder. POSIX data directories/files are created with 0700/0600; Windows relies on the user's LocalAppData ACLs. URL fields are omitted from job/options repr. Raw upstream diagnostics are not displayed or persisted; only typed localized errors are shown. No cookies, login harvesting or DRM workarounds are added.

Tool commands use argument arrays with `shell=False`. Metadata/probe stdout is bounded at 2 MiB, stderr at 64 KiB and streaming progress lines at 64 KiB. Cancellation/timeout terminates and reaps subprocesses; POSIX process groups and Windows taskkill handle descendants. Analysis also uses this bounded runner, addressing M1's metadata-memory review note. TLS and artifact verification stay enabled.

## Reproduce

From the repository root, Python 3.11+, with FFmpeg/FFprobe on PATH:

```sh
python -m unittest discover -s rewrite/windows -p "test_*.py" -v
```

The suite uses authored PCM WAV bytes and an ephemeral `127.0.0.1` HTTP server; no third-party media/site connection is needed. It covers success with real probe/decode, corrupt/truncated outputs, 429/5xx retries, 403/404, retry limits/Retry-After, unavailable Range, changed ETag, missing tools, simulated ENOSPC, cancellation, two-worker concurrency/max-three validation, migration/reopen, duplicate targets, cross-volume copy and restart windows (partial, verification, publication). It also retains M1 tests and checks honest Tk controls without a display. Loopback access must be permitted by the local execution sandbox; no public egress is required.

Optional full integration with the actual installed yt-dlp:

```sh
python -m pip install -r rewrite/windows/requirements.txt
python rewrite/windows/tools/smoke_m2.py
```

This generates a one-second blue video and silent AAC audio locally, serves both on loopback, uses owned info-json metadata to select actual separate track IDs, downloads/merges through yt-dlp/FFmpeg, probes and fully decodes the result, and checks unique publication and state order. The temporary server/tree are cleaned afterward. It validates the pipeline; public-site analysis and native playback are separate checks.

With a display (or Xvfb), Tkinter and optional ImageMagick `import` for captures:

```sh
python rewrite/windows/tools/smoke_m2_ui.py --screenshots rewrite/docs/evidence/m2
```

The script exercises the real queue and verifier. An explicit verification gate holds VERIFYING for the screenshot; it does not replace verification. Captures: [verifying](evidence/m2/queue-verifying.png), [completed](evidence/m2/queue-completed.png), [failed](evidence/m2/queue-failed.png). They are Linux/Tk evidence, not Windows scaling certification.

Manual check: launch `python rewrite/windows/app.py`, analyze an authorized link, select a real format, choose a folder and add it to the queue. Another link can be analyzed while tasks run. Select rows to inspect progress/state or cancel/retry. Confirm yt-dlp Pause stays disabled, 100% appears only after verification/publication, collisions preserve existing files and restarting retains jobs. For reproducible network/failure behavior use the owned loopback fixtures above rather than a live provider.

## Recorded evidence and limits

Local validation on 2026-11-11: 62 tests passed on both Python 3.12.14 and 3.13.5; real yt-dlp 2026.10.26 + FFmpeg/FFprobe 7.1.5 merge/verification smoke passed; real Tk 8.6/Xvfb queue smoke passed. Android regression: `cd rewrite/android && gradle --no-daemon :app:assembleDebug`, Gradle 8.11.1, Temurin JDK 17.0.17+10, SDK 35 — passed (valid existing outputs reused). Remote CI evidence will be linked in the M2 PR for its exact commit.

No new third-party Python dependency or bundled binary is added. Existing yt-dlp core source is Unlicense; this task uses the environment's FFmpeg/FFprobe tools, whose licensing depends on build configuration (the tested Debian build includes GPL components). No binary packaging/distribution is performed; M6 must inventory the actual shipped builds and notices.

Native Windows 11 process-tree behavior, filesystem/ACL variants, long-duration/high-resolution decode cost, device playback, scaling/accessibility, physical Android and signed distribution remain unverified. Decode and metadata checks cannot prove source authenticity or completeness when the source provides neither length nor other integrity evidence. No Windows release or universal site support is claimed. The larger PySide6 UI and Android persistence work belong to later issues.
