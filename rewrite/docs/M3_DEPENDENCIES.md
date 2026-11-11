# M3 desktop dependency and asset record

M3 adds `PySide6-Essentials==6.12.0`, which provides the `PySide6` namespace and the QtCore, QtGui, QtWidgets and QtSvg modules used here. Its transitive `shiboken6` version is 6.12.0. Qt runtime tested: 6.12.0. The Essentials wheel is sufficient: WebEngine, PDF and Addons are not requirements of this application.

The installed PyPI metadata for both packages declares `LGPL-3.0-only OR GPL-2.0-only OR GPL-3.0-only`. See the upstream [Qt for Python licenses](https://doc.qt.io/qtforpython-6/licenses.html) and license texts in the distribution. M3 runs the dynamically loaded Python packages from a development environment. It does not produce an installer or ship bundled Qt binaries; M6 must include the notices and compliance material for the exact build being distributed.

The existing yt-dlp dependency remains `>=2025.10.14`; the actual version tested was 2026.10.26, core source under Unlicense. FFmpeg/FFprobe are external PATH tools (tested 7.1.5); their license depends on build options. The tested Debian build includes GPL components. M3 does not bundle those tools or change their invocation/verification pipeline.

The approved logo is copied unchanged from `branding/thekain-icon.svg` to `rewrite/windows/desktop/assets/thekain-icon.svg`. Both SHA-256 values are `d1b24b2e94611593e34968cd99f8998dafef1762252a17bfd5b388cd611d0834`. No replacement branding is introduced. Small functional line icons, the dropdown chevron and checkbox check are drawn in the new presentation code; they are not copied third-party assets.

No additional thumbnail/image library is installed: Qt decodes bounded PNG/JPEG/WebP data. Metadata/queue/storage/transport/verifier modules keep their existing standard-library and subprocess dependencies and do not import Qt.
