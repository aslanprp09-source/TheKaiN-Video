# M6 dependency and provenance record

Reviewed 12 November 2026. Experimental packaging only. No new application/archival asset license is assigned; final distribution review remains a release gate.

| Component | Pin / use | License / provenance |
|---|---|---|
| CPython | 3.14.10, Windows x64 setup-python build | PSF license and bundled third-party terms, LICENSE.txt copied from actual interpreter |
| yt-dlp wheel | 2026.10.26, source wheel, separate frozen worker | Unlicense. No upstream standalone EXE/GPL bundle or extras downloaded |
| PySide6-Essentials / shiboken6 | 6.12.0, QtCore/Gui/Widgets/Svg, dynamic DLLs | Declared LGPL-3.0-only OR GPL-2.0-only OR GPL-3.0-only. Proposed LGPL path preserves replaceable libraries and modification debugging; full GPL/LGPL texts included. Actual vendor/native notices and source correspondence still need final review |
| PyInstaller | 6.22.3, build only / bootloader in outputs | GPL-2.0-or-later with bootloader exception; installed texts retained |
| pyinstaller-hooks-contrib | 2026.10, build only | Hook/helper licensing per installed distribution, typically Apache-2.0/GPL exception; metadata/text copied rather than flattened to a single guessed license |
| altgraph / packaging / pefile / pywin32-ctypes / setuptools | 0.17.5 / 26.3 / 2024.8.26 / 0.2.3 / 84.2.1, build dependencies | MIT / Apache-2.0 or BSD-2-Clause / MIT / BSD-3-Clause / MIT plus vendored terms; installed metadata/notices included |
| Inno Setup | 7.1.1 x64, build compiler, generated installer | [Inno license](https://github.com/jrsoftware/issrc/blob/57f93bac7e394f0fd5df2a13e16c6d6d98ad0c3f/license.txt), use including commercial allowed with stated attribution/origin conditions; no modifications to compiler |
| FFmpeg/FFprobe | Gyan 9.0.2 essentials, pinned QA prerequisite | GPL-enabled build, corresponding upstream/build terms; **excluded from distributed installer**. User-supplied FFmpeg licensing varies. Linux development smoke used system 7.1.5; it is distinct from native Windows baseline |
| Android runtime | [M4](M4_DEPENDENCIES.md)/[M5](M5_DEPENDENCIES.md) direct pins + app/gradle.lockfile | Runtime AndroidX, Kotlin/coroutines/annotations, Guava and JSpecify Apache-2.0; jsoup MIT. Resolved inventory hashes actual AAR/JAR files. New families require review before releaseNotices succeeds |
| Android adapter | Native HTTPS/static HTML5 only | No yt-dlp/Python/JS/FFmpeg, GPL wrapper or NewPipe runtime added; [research](M5_SOURCE_RESEARCH.md) remains applicable |

The Windows [wheel lock](../release/windows/requirements-build.lock) allows only exact Windows-compatible wheels and SHA-256. Tools use [separately pinned URLs/digests](../release/windows/tools.json), obtained from upstream GitHub release metadata. Never substitute “latest” or run a mismatched download. PyInstaller disables UPX; no unreviewed compressor is discovered from PATH.

`collect-notices.py` copies the actual installed distributions' available license files and metadata, and CPython LICENSE.txt. It also retrieves only notice/license/attribution text from pinned source commits, validates each Git blob and records per-file SHA-256:

- qtbase 6.12.0: `025bdad181de241e81bf853c8a2d7bf3d19261a9`
- qtsvg 6.12.0: `883a58532078d003cefef0a549bfe9f885ce41f5`
- pyside-setup 6.12.0: `f50facb83fc3dc5f3240c854668c7a6dd93871b2`

These are nominal upstream version sources, not proof of the exact official wheel's build configuration or all vendor source delivery obligations. The source links/texts do not substitute for corresponding source where required. Complete actual-binary inventory, source/configuration correspondence, remaining vendor notices and the project's distribution-license decision must be reviewed before public distribution. No automatic production publication exists.

Android `releaseNotices` lists actual releaseRuntimeClasspath binaries/versions/hashes, source families and embedded root notices, with Apache/jsoup texts in APK assets. It does not claim a full SPDX/CycloneDX audit or extraction of every nested vendor notice. AGP 8.9.2, Kotlin compiler 2.0.21, Gradle 8.11.1 and SDK/build tools 35 remain pinned; JDK 17 vendor patch/runner image are recorded by the CI logs, not byte-identical toolchain claims. Test-only tools are excluded from release runtime. AndroidX graphics-path carries native libraries: inspection checks all shipped ELF load alignments and APK ZIP alignment for 16 KiB prerequisites, while actual 16 KiB runtime validation stays BLOCKED.
