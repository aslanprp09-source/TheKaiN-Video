# TheKaiN Video — Clean Rebuild

Separate new implementation, started on 2026-11-11. Legacy code and releases are preserved. The code under this directory is written from scratch.

## Windows desktop — PySide6 development build

Python 3.11+, yt-dlp, FFmpeg and FFprobe (on PATH for merging and mandatory output verification). Run:

    cd rewrite/windows
    python -m pip install -r requirements.txt
    python app.py

The PySide6 UI follows the approved TheKaiN design: Home, Downloads, Library and Settings; System/Dark/Light themes; deliberate paste/clear; asynchronous source analysis and thumbnails; actual video/audio variants and destination selection. Downloads have search/state filters, measured rate/ETA only when defensible, accessible actions and verified-file opening. English localization keys back the Russian-first copy. See [M3 UI validation and screenshots](docs/M3_UI.md).

The accepted M2 mechanism remains unchanged: persistent SQLite queue (two concurrent jobs by default, maximum three), private staging, mandatory FFprobe/full FFmpeg decode and no-clobber publication before completion. yt-dlp Pause is unavailable until remote continuation can be proven. The direct HTTP service API supports pause/resume only after real 206/strong ETag checks. Unknown sizes remain unknown; no artificial qualities, MP3 conversions, subtitles or playlist controls are offered. Production installers and native Windows release verification remain later work. Requires authorized downloads. Run offline tests with FFmpeg/FFprobe and the Python requirements installed:

    python -m unittest discover -s rewrite/windows -p "test_*.py" -v

On headless Linux, set `QT_QPA_PLATFORM=offscreen` for Qt tests. The rebuild CI installs the required Qt runtime libraries and dependencies before running the same command.

See [M2 validation, recovery and limitations](docs/M2_QUEUE.md) and [M1 analysis contracts](docs/M1_EXTRACTOR.md). Default state storage is `%LOCALAPPDATA%/TheKaiNVideo` on Windows or `$XDG_DATA_HOME/thekain-video` on Linux. Set `THEKAIN_DATA_DIR` to a writable private directory to override it (for example `/workspace/thekain-data` in this cloud environment). Do not put queue databases in a shared folder: job URLs may contain expiring access tokens.

## Android prototype 0.1

Native Kotlin/Android DownloadManager app handling direct HTTPS MP4/WebM/MP3/M4A/MOV links only; this does NOT download video-page links from YouTube or social media. Open rewrite/android in Android Studio (JDK 17, SDK 35), or build with Gradle 8.11.1. A CI workflow builds a debug APK.

Review research and milestones under docs/. This is a development foundation, not a verified production release.
