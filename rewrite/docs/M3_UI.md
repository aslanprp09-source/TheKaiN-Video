# M3 — PySide6 desktop presentation

Issue #3; base `rebuild-2026-11-11`, following accepted M2 PR #8 / merge `c94845b`. This replaces the Tkinter view. It is a development UI, not a signed or native-Windows-certified release.

## Implemented behavior

- The approved K logo, exact dark/light palettes, red primary actions, system typography, 44-pixel controls, visible focus rings and status text accompany the four sections: Главная, Загрузки, Библиотека, Настройки. Initial size is 1180×760 logical pixels, minimum 900×620. The sidebar collapses below 1020 pixels; scroll areas keep controls reachable and main content is capped at 980 pixels.
- Home reads the clipboard only on explicit paste or the focused edit's standard shortcut. Clear/edit invalidates the preview and cancels prior analysis; generation checks reject stale responses. Analysis is cancelable with its button or Esc. Ctrl+L focuses/selects the URL, Enter in that field analyzes, Ctrl+Enter downloads only a valid selected variant.
- Ready preview shows a 16:9 image or neutral placeholder, two-line elided title with full accessible text/tooltip, host/duration, and only actual source video/audio variants. Unknown bytes display `—`; estimates have `≈`. Formats keep the real final extension. Merge requirements and missing tools are explained. No unsupported playlist/subtitle/conversion controls are shown.
- Downloads has state tabs with counts, case-insensitive title search and sorting. Job cards consume immutable M2 events, cap transfer progress below 100, use indeterminate postprocessing/verification and enable Open only for COMPLETED. Pause appears only with confirmed capability; resume/retry/cancel follow M2 state. Measured rate/ETA require actual positive byte/time observations and are reset on stage/attempt change. Details explain unsupported pause and show localized error/code without source URL tokens.
- Library contains completed jobs. File/folder opening goes through the application adapter and local-file QUrls, with existence checks; no shell is used. “Скрыть из списка” hides a terminal record in the presentation only and explicitly keeps the file and database entry. It is not advertised as privacy deletion or staging cleanup.
- Settings persist non-secret theme, destination, concurrency (1–3), reduced-motion preference and hidden UUIDs in `preferences.ini` alongside the private data directory. System theme follows Qt's OS color-scheme events. Reduced motion uses static phase progress, without turning verification into success. Storage/NTFS and signed-URL persistence limitations are explained; local data can be opened for inspection. Russian-first text is centralized under English keys. English language selection is not advertised before translations exist.

## Engine boundary

`desktop/controller.py` is the application adapter. It serializes queue commands on a worker, runs cancelable analysis separately and forwards immutable results/jobs through Qt signals. Startup, SQLite, output checks/open commands, shutdown and thumbnail reads are off the UI thread. Pure presentation helpers handle formatting/filtering/measured speed; widgets do not parse tool stdout or query SQLite. Frequent progress updates are coalesced before layout refresh.

The accepted `DownloadQueue`, job repository/state machine, yt-dlp/HTTP transport, process runner, FFmpeg verifier and publisher files are unchanged by M3. Setting concurrency uses the existing queue condition/field; enqueuing passes the original `Options`/selected `FormatVariant`. Queue workers remain the sole source of task truth. Shutdown waits asynchronously for the M2 publication commit boundary instead of discarding its worker.

Optional thumbnail fetching validates HTTP(S) URLs and redirects, disallows HTTPS downgrade, preserves inherited TLS/proxy trust, limits encoded data to 4 MiB and decoded allocation to 32 MiB, rejects dimensions above 4096, and accepts only PNG/JPEG/WebP. A timeout, invalid image or stale generation leaves the neutral placeholder. No remote SVG/HTML is rendered, and title/error labels use plain text.

## Reproduce and validate

With Python 3.11+, FFmpeg/FFprobe on PATH, from the repository root:

```sh
python -m venv rewrite/windows/.venv
# Activate rewrite/windows/.venv using the normal command for your OS.
python -m pip install -r rewrite/windows/requirements.txt
python rewrite/windows/app.py
python -m unittest discover -s rewrite/windows -p "test_*.py" -v
python rewrite/windows/tools/smoke_m2.py
```

Set `THEKAIN_DATA_DIR` to a writable private directory when LocalAppData/XDG data storage is unavailable. On headless Linux set `QT_QPA_PLATFORM=offscreen`; runtime packages used by CI are `ffmpeg libegl1 libopengl0 libxkbcommon0 fonts-dejavu-core`. Normal tests use owned metadata and loopback media only, never public-provider URLs. CI installs the pinned desktop requirements before running core plus Qt tests. [Dependency/license record](M3_DEPENDENCIES.md).

The Qt smoke uses actual widgets, Qt mouse/key events, a real M2 queue, owned WAV data on loopback and real FFprobe/decode. Its transfer gate holds measured bytes while the downloading screenshot is taken; it does not create fake task states or mark a file complete. The Ready analyzer uses authored metadata; no live provider is claimed.

```sh
# Run each combination as a separate process (Qt reads scale at startup).
QT_QPA_PLATFORM=offscreen QT_SCALE_FACTOR=1 python rewrite/windows/tools/smoke_qt.py --theme dark --screenshots rewrite/docs/evidence/m3
QT_QPA_PLATFORM=offscreen QT_SCALE_FACTOR=1 python rewrite/windows/tools/smoke_qt.py --theme light --screenshots rewrite/docs/evidence/m3
QT_QPA_PLATFORM=offscreen QT_SCALE_FACTOR=1.5 python rewrite/windows/tools/smoke_qt.py --theme dark --screenshots rewrite/docs/evidence/m3
QT_QPA_PLATFORM=offscreen QT_SCALE_FACTOR=1.5 python rewrite/windows/tools/smoke_qt.py --theme light --screenshots rewrite/docs/evidence/m3
QT_QPA_PLATFORM=offscreen QT_SCALE_FACTOR=2 python -m unittest discover -s rewrite/windows -p test_app.py -v
```

The older `smoke_ui.py` / `smoke_m2_ui.py` names forward to the current Qt smoke and require `--theme` / `--screenshots`. Historical M1/M2 Tk captures remain historical evidence, not the current UI. The current images are direct QWidget captures, not design mockups. Manifests record logical/pixel sizes, device-pixel ratio and real job states; the 150% captures are 1770×1140 for an 1180×760 window.

| State | Dark 100% | Light 100% | Dark 150% | Light 150% |
|---|---|---|---|---|
| Empty Home | [image](evidence/m3/dark-100-empty.png) | [image](evidence/m3/light-100-empty.png) | [image](evidence/m3/dark-150-empty.png) | [image](evidence/m3/light-150-empty.png) |
| Ready Home | [image](evidence/m3/dark-100-ready.png) | [image](evidence/m3/light-100-ready.png) | [image](evidence/m3/dark-150-ready.png) | [image](evidence/m3/light-150-ready.png) |
| Downloading | [image](evidence/m3/dark-100-downloading.png) | [image](evidence/m3/light-100-downloading.png) | [image](evidence/m3/dark-150-downloading.png) | [image](evidence/m3/light-150-downloading.png) |
| Failed | [image](evidence/m3/dark-100-failed.png) | [image](evidence/m3/light-100-failed.png) | [image](evidence/m3/dark-150-failed.png) | [image](evidence/m3/light-150-failed.png) |
| Completed | [image](evidence/m3/dark-100-completed.png) | [image](evidence/m3/light-100-completed.png) | [image](evidence/m3/dark-150-completed.png) | [image](evidence/m3/light-150-completed.png) |
| Settings | [image](evidence/m3/dark-100-settings.png) | [image](evidence/m3/light-100-settings.png) | [image](evidence/m3/dark-150-settings.png) | [image](evidence/m3/light-150-settings.png) |

Recorded locally (2026-11-11): clean Python 3.11.17 environment with only Essentials/shiboken/yt-dlp requirements; 73 automated tests passed (16 Qt/presentation tests, plus the preserved 57 core tests). The Qt suite also passed at 200%; 24 captures passed scale/dimension/state assertions. Real yt-dlp → separate generated tracks → FFmpeg merge → probe/full decode → publication smoke passed. Android debug regression passed with Gradle 8.11.1/JDK17/SDK35 using existing valid outputs. Exact-commit remote CI will be linked in the M3 PR.

## Remaining release checks

These are Linux/Qt offscreen results. Windows 11/Narrator, native dialogs, live monitor DPI changes, OS text scaling/high contrast, native playback and long-history performance still need platform testing. System theme is wired to Qt's real OS signal, but native Windows theme transitions are not certified here. No public-site download success, installer, signing, filesystem compatibility improvement or staging-cleanup policy is claimed. M2's hard-link/storage/privacy/decode-cost limitations remain; M4–M6 are separate work. Main and old releases are unchanged, and the UI must not be presented as a production Windows release yet.
