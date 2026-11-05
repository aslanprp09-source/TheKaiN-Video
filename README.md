# TheKaiN Video

Android video downloader for links, with yt-dlp format selection and FFmpeg merging.

## APK (Android 10+)

Go to [Actions](https://github.com/aslanprp09-source/TheKaiN-Video/actions), choose **Build Android APK**, and open a successful run. Download the artifact **TheKaiN-Video-debug-apk** and extract `app-debug.apk`.

## Source project

For transport through the connected GitHub integration, the complete Android source is compressed into five base64 chunks in `source-parts/`. The workflow restores them, checks the SHA-256 checksum, then runs Gradle unit tests and APK assembly. No remote executable or generated secret is used.

Archive sha256: `c796870f5bbe1b8769396bb2f6322027116b53663668ea54b1f0a679ae0a2ff0`

This is an unsigned-release/debug build, not yet verified on a real phone. Supported video platforms are limited by yt-dlp, permissions and DRM; the app cannot bypass protected content.


## Windows 10/11 (x64) — TheKaiN Video 1.1.0

- [Download Windows EXE installer](https://github.com/aslanprp09-source/TheKaiN-Video/releases/download/v1.1.0-windows-test/TheKaiN-Video-Setup-x64.exe)
- [Download portable Windows ZIP](https://github.com/aslanprp09-source/TheKaiN-Video/releases/download/v1.1.0-windows-test/TheKaiN-Video-Windows-x64-portable.zip)
- [Verified build and installer smoke tests](https://github.com/aslanprp09-source/TheKaiN-Video/actions/runs/41991199667)

The installer uses a per-user installation and includes yt-dlp, FFmpeg and Deno. It offers Start-menu and optional desktop shortcuts, and can be uninstalled from Windows Settings. Twelve Python tests and an automated Windows install/launch/uninstall test passed. It is an **unsigned testing release**; Windows SmartScreen can warn about an unknown publisher. A physical Windows PC and external media-site downloads have not yet been tested. Site restrictions and DRM still apply.

