# TheKaiN Video — Clean Rebuild

Separate new implementation, started on 2026-11-11. Legacy code and releases are preserved. The code under this directory is written from scratch.

## Windows prototype 0.1

Python 3.11+ and FFmpeg (for merging video/audio and converting MP3). Run:

    cd rewrite/windows
    python -m pip install -r requirements.txt
    python app.py

Includes pasted URL, one-click clear, cancelable metadata analysis, actual format selection, folder selection, download, pause and resume through yt-dlp partial files. Formats are discovered first; unknown sizes stay unknown and video/audio merges require FFmpeg. Audio retains its offered container; there is no synthetic MP3 or resolution option. Processes only one task at a time; playlists, concurrency, thumbnail rendering and production installers are scheduled for later. Requires authorized downloads. Run offline tests with:

    python -m unittest discover -s rewrite/windows -p "test_*.py" -v

See [M1 validation and limitations](docs/M1_EXTRACTOR.md) for deterministic fixtures, UI smoke checks, screenshots and manual steps. M1 does not implement the persistent/verified queue planned for M2.

## Android prototype 0.1

Native Kotlin/Android DownloadManager app handling direct HTTPS MP4/WebM/MP3/M4A/MOV links only; this does NOT download video-page links from YouTube or social media. Open rewrite/android in Android Studio (JDK 17, SDK 35), or build with Gradle 8.11.1. A CI workflow builds a debug APK.

Review research and milestones under docs/. This is a development foundation, not a verified production release.
