# TheKaiN Video — system architecture v1.0
2026-11-11. This is the target design, not a claim of current implementation. It supersedes rewrite/docs/ARCHITECTURE.md on conflicts.

## Platforms
Windows: existing Python 3.11+ yt-dlp / Tkinter prototype as a disposable starting point. Target UI PySide6; extractor yt-dlp as a subprocess with stable JSON metadata; FFmpeg and FFprobe for processing and verification. No GUI imports inside core engine.
Android: Kotlin native application; target UI Jetpack Compose (Material 3). Existing DownloadManager prototype supports direct HTTPS files only. For media pages, evaluate maintained yt-dlp adapter with distribution/licensing/Android execution constraints. Android background transfer via lifecycle-safe service/WorkManager/foreground-service as appropriate for OS version, not a fake pause on DownloadManager.
iOS: future SwiftUI exploration only after desktop/mobile milestone and Apple provisioning decision. No backend service in MVP.

## Pipeline
URL validation
  → Source analysis (Extractor)
  → Format variants (actual available)
  → User chooses (UI/Application)
  → Persisted job/queue
  → Transfer to unique staging path
  → Optional merge/transcode (FFmpeg)
  → Verify existence, nonzero size, expected media tracks/format
  → Safe publish to destination / MediaStore
  → Mark Completed.

The core invariant: a transfer reporting 100% is not equivalent to a completed file. Completion requires full postprocessing and verification. Errors must be typed and retryable only when appropriate.

## Layer contracts
Presentation: immutable UI state and domain commands; never parse stdout, manipulate SQLite directly, or perform downloads on UI thread.
Application: AnalyzeLink, SelectFormat, Enqueue, Pause, Resume, Cancel, Retry, OpenResult, ClearHistory; coordinates services.
Domain: SourceMedia, FormatVariant, DownloadJob, JobStatus, DownloadError, TransferProgress, DownloadOptions. Domain not dependent on GUI or OS-specific storage APIs.
Extractor: analyze an authorized URL, obtain title/duration/thumbnail/playlist and actual formats; supports cancellation and robust error classification.
Transport: streaming download to per-job staging dir, emits bytes transferred, total if known, smoothed speed and resumeCapability; cancel always; pause/resume only if truly supported.
Postprocessor: bounded FFmpeg job, no shell command injection, validates exit code.
Verifier: nonempty output, no .part/.tmp, FFprobe where possible, track checks, protects against partial or damaged outputs.
StoragePublisher: filename normalization and collision-safe output; publish atomically when possible; Android publish through MediaStore pending semantics rather than broad storage access.
Persistence: SQLite Windows / Room Android, schema migrations, transactional state updates; never store secret cookies or signed full URLs in diagnostics.
Scheduler: one owner for each task, bounded concurrency Windows 2, Android 1 initially, at most 3 configured; persisted queue, safe restart reconciliation, exponential backoff for retryable failures.

## Data contracts
SourceMedia: sourceId, sourceHost, displayTitle, durationSeconds?, thumbnailUri?, playlistCount?, items?, formats[].
FormatVariant: extractorFormatId, container, finalExtension, videoWidth?, videoHeight?, fps?, videoCodec?, audioCodec?, hasAudio, byteSizeKnown?, byteSizeEstimated?, requiresMerge, subtitleLanguages[].
DownloadJob: UUID id, createdAt, updatedAt, sourceId, selectedVariant, destinationUri, stageUri, state, progressBytes?, totalBytes?, resumeCapability yes/no/unknown, errorCode?, outputUri?, verifiedBytes?, attemptNumber.
Job states: NEW → ANALYZING → READY → QUEUED → DOWNLOADING → [POSTPROCESSING] → VERIFYING → COMPLETED. Exceptions: ANALYZING/DOWNLOADING → FAILED; DOWNLOADING → PAUSING → PAUSED → QUEUED if resume supported; nonterminal → CANCELED. COMPLETED is terminal for a given attempt. Retry creates an explicit new attempt, not a silently overwritten completed task.
Error codes: INVALID_URL, UNSUPPORTED_SOURCE, NO_FORMATS, AUTH_REQUIRED, DRM_PROTECTED, GEO_RESTRICTED, REMOTE_FORBIDDEN, REMOTE_MISSING, RATE_LIMITED, TIMEOUT, NETWORK_LOST, NO_DISK_SPACE, PERMISSION_DENIED, RESUME_UNSUPPORTED, MERGE_FAILED, VERIFY_FAILED, TOOL_MISSING, UNKNOWN.

## Engineering constraints
Security: default HTTPS; reject file/javascript/data URLs and embedded credentials. No DRM/access-control bypass. No automated browser-cookie harvesting. Redact expiring tokens and signed query params from logs. Use process argument vectors, never shell=True for untrusted URLs. Limit concurrency, CPU, bandwidth and retries; honor server rate limiting.
Windows: staging separate from final path, cleanup/retain partial according to explicit policy, atomic move on same filesystem, verified copy fallback across volumes; terminate subprocess cleanly.
Android: do not use legacy broad external-storage access for target API; handle background restrictions, notifications, MediaStore and postprocessing. No unfounded claim of compatibility with every provider.
Quality selection: only real extractor-returned variants; unknown bytes shown as unknown, never 0 or invented sizes.
No remote accounts/cloud processing before a separate privacy/security decision.

## Verification strategy
Unit: validation, metadata/format selection, state machine, retries/backoff, path collisions, error mapping, secret redaction.
Offline integration: local fixture HTTP with redirects, range supported/unsupported, 403/404/429/500, truncated streams, network interruption, no disk space, FFmpeg available/missing, corrupt video and empty file. Site-specific network tests run separately and only on licensed content.
UI: desktop snapshots for 100/150/200% zoom, Android 360/412/600dp and TalkBack, both themes; test stale preview cancellation.
Release gate: clean Windows 11 install test, native Android test on real Android devices, packaged dependency licenses, malware scanning and signed binaries as appropriate. CI compile success is necessary but not sufficient.

## Delivery order
M1 domain model, extractor metadata/format discovery, Windows integration tests.
M2 persistence, staging, verification, restart-safe queue and honest status.
M3 PySide6 desktop UI + accessibility + themes.
M4 Android transport/storage lifecycle and integration tests.
M5 Compose UI + authorized page adapter (after legal/technical feasibility check).
M6 packaging, QA and release criteria. iOS separate later.
