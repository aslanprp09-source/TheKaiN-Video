# TheKaiN Video — Clean Rebuild

Separate new implementation, started on 2026-11-11. Legacy code and releases are preserved. The code under this directory is written from scratch.

## Windows prototype 0.1

Python 3.11+, yt-dlp, FFmpeg and FFprobe (on PATH for merging and mandatory output verification). Run:

    cd rewrite/windows
    python -m pip install -r requirements.txt
    python app.py

Includes pasted URL, cancelable bounded metadata analysis, actual format selection and a persistent SQLite queue (two concurrent jobs by default, maximum three). Downloads use private per-job staging, FFprobe plus a full FFmpeg decode, then collision-safe publication before completion. The table shows persisted task state, byte counts, cancel and explicit retry. Pause is disabled for yt-dlp because remote continuation cannot be established from a partial file alone. The direct HTTP service API supports pause/resume only after a real 206 response with strong ETag, with revalidation on continuation. Unknown sizes stay unknown; audio retains its offered container, without synthetic MP3/resolution options. Playlists, thumbnail rendering, the PySide6 redesign and production installers remain later work. Requires authorized downloads. Run offline tests with FFmpeg/FFprobe installed:

    python -m unittest discover -s rewrite/windows -p "test_*.py" -v

See [M2 validation, recovery and limitations](docs/M2_QUEUE.md) and [M1 analysis contracts](docs/M1_EXTRACTOR.md). Default state storage is `%LOCALAPPDATA%/TheKaiNVideo` on Windows or `$XDG_DATA_HOME/thekain-video` on Linux. Set `THEKAIN_DATA_DIR` to a writable private directory to override it (for example `/workspace/thekain-data` in this cloud environment). Do not put queue databases in a shared folder: job URLs may contain expiring access tokens.

## Android prototype 0.1

Native Kotlin/Android DownloadManager app handling direct HTTPS MP4/WebM/MP3/M4A/MOV links only; this does NOT download video-page links from YouTube or social media. Open rewrite/android in Android Studio (JDK 17, SDK 35), or build with Gradle 8.11.1. A CI workflow builds a debug APK.

Review research and milestones under docs/. This is a development foundation, not a verified production release.
