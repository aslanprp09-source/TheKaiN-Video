# TheKaiN Video v0.1 — architecture

Goal: simple, responsive user experience, resilient media downloads, transparent permissions and licensing.

Main flow: validate URL → extract metadata → enumerate available formats → user chooses → queue → download to partial file → optional FFmpeg → verify final output → publish → completed.

State machine: NEW, ANALYZING, READY, QUEUED, DOWNLOADING, PAUSING, PAUSED, POSTPROCESSING, VERIFYING, COMPLETED, FAILED, CANCELED.

Invariant: showing 100% transfer progress MUST NOT by itself mark a job as completed. Success requires final output verification. Avoid leaking sensitive tokens and cookie values into diagnostics.

Windows: Python GUI prototype and replaceable yt-dlp/FFmpeg engine; eventual PySide6 UI. Use subprocess argument arrays, not a shell command. Android: native Kotlin app, DownloadManager for the earliest direct HTTPS prototype; later use a maintained yt-dlp Android wrapper and a modern Compose UI. iOS: future SwiftUI work with Apple signing and iOS background-transfer limitations.

M0: independent new branch and source, research, simple functional Windows and Android prototypes, CI.
M1: metadata+format preview, persistent task DB, verified output, network failure tests, restart/recovery; Android extraction.
M2: playlist/batch queues, concurrent downloading, thumbnail, subtitles, audio formats, polished dark/light UI.
M3: signed APK/Windows installer, full real-device QA, localization and accessibility; iOS feasibility.

Acceptance tests for stable releases: HTTPS/direct media, authorized public-site sample URLs, 403/404/429, disconnect/reconnect, disk full, stop/resume, file integrity, clean Windows installer, Android 10+ devices. A successful compile is not equivalent to real-device verification.

No DRM circumvention, no credential harvesting, no claims of 'all sites'. Upstream yt-dlp warns that extractors can break: https://github.com/yt-dlp/yt-dlp/blob/master/supportedsites.md.