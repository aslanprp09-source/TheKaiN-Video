"""Build identity and an ICO rendered from the unchanged approved SVG."""
import json
from pathlib import Path
import re
import sys
from PySide6.QtCore import Qt
from PySide6.QtGui import QGuiApplication, QImage, QPainter
from PySide6.QtSvg import QSvgRenderer

commit = sys.argv[1]
if not re.fullmatch('[0-9a-f]{40}', commit):
    raise ValueError('Full commit required')
release = Path(__file__).parents[1]
output = release / 'build'
output.mkdir(parents=True, exist_ok=True)
app = QGuiApplication([])
image = QImage(256, 256, QImage.Format.Format_ARGB32)
image.fill(Qt.GlobalColor.transparent)
painter = QPainter(image)
svg = QSvgRenderer(str(release.parent / 'windows/desktop/assets/thekain-icon.svg'))
assert svg.isValid()
svg.render(painter)
painter.end()
assert image.save(str(output / 'thekain.ico'))
(output / 'build-info.json').write_text(json.dumps({
    'version': '0.1.0-m6', 'source_commit': commit, 'channel': 'unsigned-experimental',
    'python': sys.version, 'yt-dlp': '2026.10.26', 'qt': '6.12.0',
    'ffmpeg': 'external prerequisite; CI 9.0.2', 'production_ready': False}, indent=2), encoding='utf-8')
