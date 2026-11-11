from pathlib import Path
from PySide6.QtCore import Qt, Signal
from PySide6.QtGui import QIcon, QPixmap, QPainter, QTextLayout, QTextOption
from PySide6.QtSvg import QSvgRenderer
from PySide6.QtWidgets import (QFrame, QLabel, QPushButton, QHBoxLayout, QVBoxLayout,
                               QProgressBar, QSizePolicy)
from jobs import JobStatus as S, STATUS_RU
from desktop.presentation import size_text, format_text, can_pause, can_resume, tr

BRAND = Path(__file__).with_name('assets') / 'thekain-icon.svg'
PATHS = {
    'home': '<path d="m3 10 9-7 9 7v10H3Z"/><path d="M9 20v-7h6v7"/>',
    'download': '<path d="M12 3v12m-5-5 5 5 5-5M4 16v5h16v-5"/>',
    'library': '<rect x="4" y="4" width="16" height="16" rx="3"/><path d="m10 8 6 4-6 4Z"/>',
    'settings': '<path d="M4 6h16M4 12h16M4 18h16"/><circle cx="8" cy="6" r="2"/><circle cx="16" cy="12" r="2"/><circle cx="10" cy="18" r="2"/>',
    'paste': '<rect x="5" y="5" width="14" height="16" rx="2"/><rect x="9" y="2" width="6" height="5" rx="1"/>',
    'clear': '<path d="m6 6 12 12M6 18 18 6"/>',
    'folder': '<path d="M3 7V4h6l3 3h9v13H3Z"/>',
    'play': '<path d="m9 5 11 7-11 7Z"/>',
    'check': '<path d="m5 12 4 4L19 6"/>',
    'error': '<circle cx="12" cy="12" r="9"/><path d="M12 7v6m0 3v1"/>',
    'search': '<circle cx="10" cy="10" r="6"/><path d="m15 15 6 6"/>',
    'shield': '<path d="m12 2 8 4v6c0 5-8 10-8 10S4 17 4 12V6Z"/><path d="m8 12 3 3 5-6"/>',
    'sun': '<circle cx="12" cy="12" r="4"/><path d="M12 1v3m0 16v3M1 12h3m16 0h3M4 4l2 2m12 12 2 2M4 20l2-2M18 6l2-2"/>',
}


def icon(name, color='#AEB7C5', size=20):
    data = f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="{color}" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round">{PATHS[name]}</svg>'
    pixmap = QPixmap(size * 2, size * 2)
    pixmap.fill(Qt.GlobalColor.transparent)
    painter = QPainter(pixmap)
    QSvgRenderer(data.encode()).render(painter)
    painter.end()
    pixmap.setDevicePixelRatio(2)
    return QIcon(pixmap)


def label(text='', role=None, wrap=False):
    widget = QLabel(text)
    widget.setTextFormat(Qt.TextFormat.PlainText)
    widget.setWordWrap(wrap)
    if role:
        widget.setProperty('role', role)
    return widget


def button(text, primary=False, name=None):
    widget = QPushButton(text)
    widget.setProperty('primary', primary)
    widget.setAccessibleName(name or text)
    widget.setMinimumHeight(44)
    widget.setCursor(Qt.CursorShape.PointingHandCursor)
    widget.setAutoDefault(False)
    return widget


def card(empty=False):
    frame = QFrame()
    frame.setObjectName('emptyCard' if empty else 'card')
    layout = QVBoxLayout(frame)
    layout.setContentsMargins(24, 24, 24, 24)
    layout.setSpacing(16)
    return frame, layout


class ElidedLabel(QLabel):
    def __init__(self, text='', lines=1, role=None):
        super().__init__()
        self.full_text, self.lines = text, lines
        self.setTextFormat(Qt.TextFormat.PlainText)
        self.setMinimumWidth(0)
        self.setSizePolicy(QSizePolicy.Policy.Ignored, QSizePolicy.Policy.Fixed)
        if role:
            self.setProperty('role', role)
        self.set_full_text(text)

    def set_full_text(self, text):
        self.full_text = str(text)
        self.setToolTip(self.full_text)
        self.setAccessibleName(self.full_text)
        self.refresh()

    def refresh(self):
        width = max(20, self.contentsRect().width())
        fm = self.fontMetrics()
        visual_text = ' '.join(self.full_text.split())
        if self.lines == 1:
            text = fm.elidedText(visual_text, Qt.TextElideMode.ElideRight, width)
        else:
            layout = QTextLayout(visual_text, self.font())
            option = QTextOption()
            option.setWrapMode(QTextOption.WrapMode.WrapAtWordBoundaryOrAnywhere)
            layout.setTextOption(option)
            layout.beginLayout()
            first = layout.createLine()
            if first.isValid():
                first.setLineWidth(width)
                count = first.textLength()
                text = visual_text[:count] + ('\n' + fm.elidedText(visual_text[count:], Qt.TextElideMode.ElideRight, width) if count < len(visual_text) else '')
            else:
                text = ''
            layout.endLayout()
        self.setText(text)
        self.setFixedHeight(fm.lineSpacing() * self.lines + 6)

    def resizeEvent(self, event):
        self.refresh()
        super().resizeEvent(event)


class EmptyCard(QFrame):
    def __init__(self, title, body):
        super().__init__()
        self.setObjectName('emptyCard')
        layout = QVBoxLayout(self)
        layout.setContentsMargins(32, 32, 32, 32)
        layout.setSpacing(12)
        mark = label('↓')
        mark.setStyleSheet('font-size: 36px; color: #AEB7C5;')
        for widget in (mark, label(title, 'section'), label(body, 'muted', True)):
            widget.setAlignment(Qt.AlignmentFlag.AlignCenter)
            layout.addWidget(widget)
        self.setMinimumHeight(210)


class JobCard(QFrame):
    action = Signal(str, str)

    def __init__(self, job):
        super().__init__()
        self.setObjectName('card')
        self.job = job
        layout = QVBoxLayout(self)
        layout.setContentsMargins(20, 16, 20, 16)
        layout.setSpacing(10)
        header = QHBoxLayout()
        self.mark = label()
        self.mark.setFixedSize(36, 36)
        header.addWidget(self.mark)
        self.title_label = ElidedLabel(job.title, role='section')
        header.addWidget(self.title_label, 1)
        self.badge = label('', 'status')
        header.addWidget(self.badge)
        layout.addLayout(header)
        self.meta = ElidedLabel(format_text(job.variant), role='muted')
        layout.addWidget(self.meta)
        self.progress = QProgressBar()
        self.progress.setTextVisible(False)
        self.progress.setAccessibleName(tr('progress.label'))
        layout.addWidget(self.progress)
        self.detail = label('', 'muted', True)
        layout.addWidget(self.detail)
        bottom = QHBoxLayout()
        self.stats = ElidedLabel('', role='muted')
        bottom.addWidget(self.stats, 1)
        self.actions = {}
        for key, text in (('pause', tr('download.pause')), ('resume', tr('download.resume')),
                          ('retry', tr('download.retry')), ('cancel', tr('download.cancel')),
                          ('open', tr('file.open')), ('folder', tr('file.folder_short')), ('details', tr('details.more'))):
            control = button(text)
            control.setMinimumWidth(44)
            control.clicked.connect(lambda checked=False, action=key: self.action.emit(action, self.job.id))
            bottom.addWidget(control)
            self.actions[key] = control
        layout.addLayout(bottom)

    def update_job(self, job, speed=None, eta=None, reduced=False, color='#AEB7C5'):
        self.job = job
        self.title_label.set_full_text(job.title)
        self.meta.set_full_text(format_text(job.variant))
        self.badge.setText(STATUS_RU[job.state])
        tone = 'good' if job.state == S.COMPLETED else 'error' if job.state == S.FAILED else 'warning' if job.state in (S.PAUSED, S.RETRY_WAIT) else ''
        self.badge.setProperty('tone', tone)
        self.badge.style().unpolish(self.badge)
        self.badge.style().polish(self.badge)
        kind = 'check' if job.state == S.COMPLETED else 'error' if job.state == S.FAILED else 'download'
        self.mark.setPixmap(icon(kind, color, 24).pixmap(28, 28))
        busy = job.state in (S.POSTPROCESSING, S.VERIFYING, S.PUBLISHING) or job.state == S.DOWNLOADING and job.percent is None
        self.progress.setRange(0, 100 if not busy or reduced else 0)
        if not busy or reduced:
            self.progress.setValue(int(job.percent or 0))
        self.progress.setVisible(job.state not in (S.FAILED, S.CANCELED))
        self.progress.setAccessibleDescription(STATUS_RU[job.state])
        self.detail.setText(job.error.message if job.error else '')
        self.detail.setVisible(job.error is not None)
        received = f'{size_text(job.progress_bytes)} / {size_text(job.total_bytes)}'
        if job.state == S.COMPLETED:
            received = tr('progress.verified') + size_text(job.verified_bytes)
        if speed is not None:
            received += ' · ' + size_text(int(speed)) + tr('progress.per_second')
        if eta is not None:
            received += ' · ' + tr('progress.eta', seconds=max(1, int(eta)))
        self.stats.set_full_text(received)
        visibility = {'pause': can_pause(job), 'resume': can_resume(job), 'retry': job.state == S.FAILED,
                      'cancel': job.state in (S.QUEUED, S.DOWNLOADING, S.PAUSING, S.PAUSED, S.POSTPROCESSING, S.VERIFYING, S.RETRY_WAIT),
                      'open': job.state == S.COMPLETED, 'folder': job.state == S.COMPLETED, 'details': True}
        for key, control in self.actions.items():
            control.setVisible(visibility[key])
            control.setAccessibleDescription(job.title)
        self.setAccessibleName(job.title + '. ' + STATUS_RU[job.state])
