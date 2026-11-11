# TheKaiN Video — product and UX specification v1.0
2026-11-11. Approved design direction for implementation; screens below are NOT yet implemented.

## Product promise
Paste link → analyze → show real available formats → choose → save → verify → open file. No mandatory sign-in for public links, no concealed limits, no misleading completed badge. Public sites may change and some sources cannot be supported, especially DRM/access-controlled content. Do not copy the competitor's proprietary graphics or interface assets.

## Visual system
Brand: retain previously approved dark K icon with red play/download badge; no unapproved rebranding.
Dark palette: background #0D1016, primary surfaces #171C25, raised #222936, foreground #F5F6F8, secondary text #AEB7C5, red accent #E5484D, hover #F06467, success #36B37E, warning #E5AD46.
Light palette: background #F6F7F9, primary surface #FFFFFF, ink #1A202B, accent #C52A38. Never rely on red/green color alone to communicate status.
Spacing 8 px/dp baseline, 12–16 corner radius, min click target 44 px Windows / 48 dp Android; OS system typography, high-contrast accessible text, visible keyboard focus, reduced-motion support.
Theme System/Dark/Light. Russian first, all UI messages localizable to English. UI must be comfortable at Windows scaling 100/150/200% and Android widths 360, 412, 600 dp.

## Navigation
Windows: sidebar Главная, Загрузки, Библиотека, Настройки; collapsible at narrow window. Initial window 1180x760 logical px, minimum 900x620. Main content max 980 px. Show active task preview on Home.
Android: bottom tabs Главная, Загрузки, Библиотека; top settings action, respect system back and safe-area insets. Use single-column portrait layout, wider/two-column where appropriate on tablet.

## Windows Home empty state
- Top: brand name, theme and settings buttons, no promotional banner.
- Primary large URL input with placeholder "Вставьте ссылку на видео"; paste and clear icons, clear displayed only when input nonempty.
- Primary button "Определить видео". Invalid input explains problem inline.
- Context hint: "Загружайте только видео, которое вам разрешено сохранять".
- Empty recent-list with useful visual placeholder, not sponsored content.

## Windows Home analyzed state
- Preview card: 16:9 thumbnail or neutral placeholder, max 2 lines for title, source host, duration if known; playlist count if any.
- Video / Только аудио switch only if source contains required tracks.
- Quality selector populated ONLY by actual extracted formats, sorted sensible descending, with container and final output extension; show estimated size prefixed with ≈, exact only if known, unknown as —.
- Destination directory selector and optional generated filename; advanced foldout for subtitles/audio tracks/metadata only when supported. Warn when FFmpeg required but missing.
- Primary CTA "Скачать" or "Добавить N видео" after explicit playlist item selection. Do not silently download full playlists.
- A compact current task row with progress, measured speed, ETA only when defensible, state, and genuine pause/cancel controls.

## Windows Downloads
Tabs Все / Активные / Завершённые / Ошибки, search, sort and per-task actions. Rows include source thumbnail, sanitized title, format, bytes, accurate status. Completed tasks can open file/folder. History removal must not remove the actual file without separate confirmation. No duplicate finished output names.

## Android home
Single-column: brand top, URL field with Вставить/Очистить, inline analysis state, 16:9 preview, quality/audio variant and destination preference, large "Скачать" CTA. Primary CTA must not be hidden under soft keyboard; use scrollable layout and proper insets. Download list displays state and notification where appropriate. Never expose "Пауза" if underlying system DownloadManager cannot support it. No all-files storage permission; use MediaStore or appropriate scoped APIs.

## Settings
Default folder/storage destination; concurrency (Windows default 2, Android 1; initial safe maximum 3); theme; locale; privacy/diagnostics; background behavior and permissions; licenses; version. Privacy-first: no telemetry by default, never read clipboard silently in background.

## UX state and copy
ANALYZING: "Определяем доступные форматы…" with cancel analysis, no stale preview on edited URL.
READY: format list and Download enabled if a valid choice exists.
QUEUED: "В очереди" and remove option.
DOWNLOADING: bytes and progress (0–99 while further stages remain), pause only if supported, cancel.
PAUSING/PAUSED: real backend acknowledgement; Resume is not allowed when unsupported.
POSTPROCESSING: "Обработка файла", distinct from download progress.
VERIFYING: "Проверяем сохранённый файл", indeterminate.
COMPLETED: "Файл сохранён", only on verified output; Open/Folder.
FAILED: actionable error, Retry and Details; no artificial 100%.
CANCELED: clear retention policy for partial files.

Typed errors map to helpful localized text: invalid URL, removed/private video, restricted source, rate limit, network loss, expired link, unsupported format, missing FFmpeg, no storage space, failed verification, DRM unsupported. Avoid blaming user or implying a workaround for DRM.

## Navigation and accessibility QA
Desktop keyboard: Ctrl+L URL focus, Ctrl+V when focused, Enter analyzes link, Ctrl+Enter downloads when a valid variant is ready, Esc closes dialogs. Screen-reader labels for icon-only controls. Motion reduction. Ensure all controls accessible at scaling 200%. Android: TalkBack semantics and 48 dp touch surfaces.

## Design acceptance
Test empty/long URLs, very long titles, RTL-unfriendly content, missing thumbnail, unknown format size, playlist 1/100 items, no network, errors, canceled download, simultaneous progress, light/dark, zoom and system text scale. Codex should submit screenshots of Home empty/ready, Downloads running/failed/completed, Settings and 360dp Android layouts before design sign-off.
