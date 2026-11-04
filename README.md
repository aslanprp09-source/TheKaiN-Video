# TheKaiN Video

Android video downloader for links, with yt-dlp format selection and FFmpeg merging.

## APK (Android 10+)

Go to [Actions](https://github.com/aslanprp09-source/TheKaiN-Video/actions), choose **Build Android APK**, and open a successful run. Download the artifact **TheKaiN-Video-debug-apk** and extract `app-debug.apk`.

## Source project

For transport through the connected GitHub integration, the complete Android source is compressed into five base64 chunks in `source-parts/`. The workflow restores them, checks the SHA-256 checksum, then runs Gradle unit tests and APK assembly. No remote executable or generated secret is used.

Archive sha256: `c796870f5bbe1b8769396bb2f6322027116b53663668ea54b1f0a679ae0a2ff0`

This is an unsigned-release/debug build, not yet verified on a real phone. Supported video platforms are limited by yt-dlp, permissions and DRM; the app cannot bypass protected content.
