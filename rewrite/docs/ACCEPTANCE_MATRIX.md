# Acceptance matrix — verified behavior requirements

Current clean rebuild v0.1 only includes a Windows Tkinter proof of concept and Android DownloadManager direct-HTTPS prototype. The target product requirements below are NOT implemented yet.

| Test | Windows target | Android target | Priority |
|---|---|---|---|
| Direct HTTPS MP4 | download opens and plays | same | critical |
| Supported public page | real format list | after adapter integration | critical when advertised |
| Invalid URL | actionable inline error | same | critical |
| URL changes while analyzing | discard stale preview | same | critical |
| Unknown filesize | “—” or “≈” | same | major |
| Required format missing | disabled with reason | same | critical |
| Transfer 100% but corrupt result | FAILED after verify | same if verification path exists | critical |
| Interrupted network | resume only if supported | same | critical |
| Pause unsupported by transport | no fake pause button | no fake pause button | critical |
| Server HTTP 403/404/429 | distinct actionable error | same | major |
| Duplicate source/output | no overwritten verified output | same | critical |
| Restart during transfer | recover persisted state | same | release gate |
| Full storage | actionable error; no false success | same | critical |
| DRM-protected video | explain unsupported | explain unsupported | critical |
| Signed URLs | no secret query params in logs | same | critical |
| Accessibility | 100/150/200% and keyboard | 360/412/600dp + TalkBack | major |
| Theme | System/Dark/Light | System/Dark/Light | major |
| Playlist | explicit list, opt-in selected items | after feature integration | later |
| Packaging | clean Windows 11 VM | physical Android 10+ | release gate |

For each GitHub issue report: issue and PR; exact build and version; commands, fixture, expected/actual; screenshots for UI; test logs; unverified gaps; whether release-eligible. Do not treat GitHub Actions green status as physical-device verification.
