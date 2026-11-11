# M1 Windows source analysis — Issue #1

Base: `rebuild-2026-11-11`. Implementation is limited to `rewrite/`; archival main, tags and releases are unchanged. This is development work, not a release-ready downloader.

`windows/domain.py` defines immutable `SourceMedia`, `FormatVariant`, `AnalyzeResult` and typed `DownloadError` contracts without GUI imports. Error codes expose English localization keys and Russian messages. Raw extractor diagnostics are deliberately not retained or displayed, so signed URL tokens cannot leak through error text.

`windows/extractor.py` starts `python -m yt_dlp --ignore-config --dump-single-json --skip-download --no-playlist --no-warnings -- URL` using argument arrays and no shell. Analysis runs off the Tk thread, has a 30-second deadline, polls cancellation and terminates/reaps its child on cancellation/timeout. URL edits invalidate pending results and clear selection. Embedded credentials, control characters and non-HTTP(S) schemes are rejected. HTTP remains accepted for explicitly authorized sources; HTTPS is preferred.

Only returned format IDs with media codecs are selectable. Storyboards, DRM formats and malformed IDs are excluded. Separate video is paired with an actual audio track, preferring compatible MP4/M4A or WebM tracks, otherwise using MKV; the exact IDs are passed to download without a speculative fallback. Audio-only formats keep their actual extension. No artificial MP3 conversion or resolution is offered. Unknown sizes display `—`; source estimates have `≈`; merged size is only known/estimated if both components have size evidence. Video without an audio partner is explicitly labeled as silent.

## Reproduce tests

From the repository root, with Python 3.11+:

```sh
python -m unittest discover -s rewrite/windows -p "test_*.py" -v
```

The normal suite needs no yt-dlp installation, external media or network. `fixtures/one.json`, `three.json`, `none.json` and `audio.json` are authored synthetic metadata, not scraped data or third-party media. They cover one, three and zero formats, unknown/exact/estimated sizes, audio-only and missing thumbnail. `fixtures/extractor_process.py` is a local child-process double for success, malformed JSON, HTTP 403 and delayed output. Tests also cover other network/access error mappings, URL rejection, DRM, process timeout/cancel/reaping and stale GUI results. Tk controller tests skip explicitly if Tkinter is unavailable; extractor/domain tests remain independent of Tk.

Optional real dependency round trip (still no network/media requests):

```sh
python -m pip install -r rewrite/windows/requirements.txt
python rewrite/windows/tools/smoke_ytdlp.py
```

This enriches the owned one-format metadata fixture with reserved `example.invalid` URLs and passes it through yt-dlp's `--load-info-json`, with download and format checks disabled **only in this offline smoke command**. Production analysis retains normal TLS and extractor checks. It validates CLI/JSON compatibility, not any site's support.

With a real display or an already running Xvfb, test the real Tk window:

```sh
python rewrite/windows/tools/smoke_ui.py --screenshots rewrite/docs/evidence/m1
```

Screenshots require ImageMagick `import`. The smoke test uses the local subprocess double and checks empty → analyzing → three selectable variants, URL invalidation and localized 403 without exposing query tokens. Captures: [empty](evidence/m1/home-empty.png), [ready](evidence/m1/home-ready.png), [error](evidence/m1/home-error.png). These are Linux/Tk captures, not Windows accessibility or scaling certification.

Manual checks:

1. Run `python rewrite/windows/app.py`; Download is disabled before analysis.
2. Paste a public link to material you are authorized to save and select «Определить видео». Select only a returned format. Unknown size must remain `—`; merge variants indicate FFmpeg.
3. Edit the URL during analysis; old formats disappear and a delayed response must not re-enable Download. Use «Отменить анализ» to cancel; the GUI stays responsive.
4. Analyze a credential-bearing/unsafe URL and check the Russian validation error. No download starts. Use the offline smoke for deterministic 403 instead of depending on third-party network errors.
5. For an actual video+audio merge, FFmpeg must be on PATH; without it the existing dialog explains the requirement. Live media downloading and merge playback are separate authorized integration checks, not claimed by M1 tests.

## Recorded validation (2026-11-11)

- Windows module suite: 33 tests passed, no skips, on Python 3.12.14; the same suite also passed with the system Python 3.13.5.
- Tk smoke: passed on Linux/Xvfb with Python 3.13.5, Tk 8.6, using owned fixtures; screenshots attached above.
- Real dependency: yt-dlp 2026.10.26 offline JSON round trip passed. yt-dlp is an existing dependency (`>=2025.10.14`), installed from PyPI; core source license Unlicense. No binary is bundled or packaged in M1. FFmpeg remains an external prerequisite for merging; no packaging/license distribution claim is made here.
- Android regression command, from `rewrite/android`: `gradle --no-daemon :app:assembleDebug`, Gradle 8.11.1, Temurin JDK 17.0.17+10, SDK 35. Passed. Cloud JDK uses the system Java CA store (`JAVA_TOOL_OPTIONS=-Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts`); TLS verification stays enabled. APK identity: `com.thekain.video.rebuild`, version 0.1.0.
- Existing `.github/workflows/rewrite-ci.yml` runs Windows tests and Android assembly, but only on pushes to the rebuild base or manual dispatch. A feature-branch push does not establish a remote CI run. Remote CI evidence must be checked separately; no green GitHub run is claimed here.

## Limitations

Error classification is conservative text matching over yt-dlp's diagnostics; unknown failures use a generic localized error. It cannot guarantee every provider's private/geographic/DRM diagnosis. Playlist analysis is rejected rather than silently downloading entries. Thumbnail URI is represented but not fetched/rendered. No account, cookie import, DRM bypass or paid-source support is added. Codec-less metadata is not offered because track capabilities cannot be established truthfully. Analysis has a time bound but not an output-size sandbox.

M2 persistence, staging, robust verification and restart-safe scheduling remain unimplemented. The existing prototype's transfer completion and pause/resume behavior are not promoted to production guarantees by M1. Native Windows, 100/150/200% accessibility, physical Android, signed distribution, live sites and actual FFmpeg merges have not been validated in this task.
