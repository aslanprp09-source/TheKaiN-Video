"""The approved desktop workspace; presentation only, no SQL or stdout parsing."""
import time
from pathlib import Path
from PySide6.QtCore import Qt, QSettings, QUrl, QSize, Signal, QTimer
from PySide6.QtGui import QIcon, QKeySequence, QShortcut, QDesktopServices, QPixmap, QGuiApplication
from PySide6.QtWidgets import (QMainWindow, QWidget, QFrame, QVBoxLayout, QHBoxLayout,
    QStackedWidget, QScrollArea, QLineEdit, QComboBox, QTabBar, QButtonGroup, QFileDialog,
    QCheckBox, QDialog, QDialogButtonBox, QMessageBox, QApplication)
from domain import DownloadError, ErrorCode
from engine import Options
from extractor import validate_url
from jobs import JobStatus as S, STATUS_RU
from desktop.controller import Controller
from desktop.presentation import tr, format_text, filtered_jobs, SpeedMeter
from desktop.theme import stylesheet, PALETTES
from desktop.widgets import BRAND, icon, label, button, card, EmptyCard, ElidedLabel, JobCard


class MainWindow(QMainWindow):
    shutdown_finished = Signal()

    def __init__(self, root, controller=None, settings=None):
        super().__init__()
        self.root = Path(root)
        self.settings = settings or QSettings(str(self.root / 'preferences.ini'), QSettings.Format.IniFormat)
        self.controller = controller or Controller(root, int(self.settings.value('concurrency', 2)))
        self.jobs, self.cards, self.library_cards = {}, {}, {}
        self.speeds, self.meter = {}, SpeedMeter()
        self.media, self.analyzing, self.generation = None, False, 0
        self.backend_ready, self.tools = False, {}
        self.enqueue_pending, self.pending_key = False, None
        self.accepted_selections = set()
        self.repeat_dialog = None
        self.hidden = set(self.settings.value('hidden_jobs', [], list))
        self.theme_mode = str(self.settings.value('theme', 'system'))
        self.reduced = self.settings.value('reduced_motion', False, bool)
        self.destination = str(self.settings.value('destination', str(Path.home() / 'Downloads')))
        self.closing = self.allow_close = False
        self.refresh_timer = QTimer(self)
        self.refresh_timer.setSingleShot(True)
        self.refresh_timer.setInterval(100)
        self.refresh_timer.timeout.connect(self.refresh_lists)
        self.setWindowTitle('TheKaiN Video')
        self.setWindowIcon(QIcon(str(BRAND)))
        self.resize(1180, 760)
        self.setMinimumSize(900, 620)
        self.setAccessibleName(tr('app.accessible'))
        shell = QWidget()
        shell.setObjectName('shell')
        self.setCentralWidget(shell)
        outer = QHBoxLayout(shell)
        outer.setContentsMargins(0, 0, 0, 0)
        outer.setSpacing(0)
        self.sidebar = QFrame()
        self.sidebar.setObjectName('sidebar')
        side = QVBoxLayout(self.sidebar)
        side.setContentsMargins(16, 28, 16, 24)
        side.setSpacing(12)
        brand = QHBoxLayout()
        self.brand_icon = label()
        self.brand_icon.setPixmap(QIcon(str(BRAND)).pixmap(40, 40))
        self.brand_icon.setFixedSize(40, 40)
        self.brand_icon.setAccessibleName(tr('brand.accessible'))
        self.brand_text = label('TheKaiN\nVideo', 'brand')
        brand.addWidget(self.brand_icon)
        brand.addWidget(self.brand_text, 1)
        side.addLayout(brand)
        side.addSpacing(28)
        self.nav, self.nav_group = [], QButtonGroup(self)
        for index, (key, glyph) in enumerate((('home', 'home'), ('downloads', 'download'), ('library', 'library'), ('settings', 'settings'))):
            control = button(tr('nav.' + key))
            control.setCheckable(True)
            control.setProperty('nav', True)
            control.setToolTip(tr('nav.' + key))
            control.clicked.connect(lambda checked=False, page=index: self.navigate(page))
            control.glyph, control.full_text = glyph, tr('nav.' + key)
            self.nav_group.addButton(control, index)
            self.nav.append(control)
            side.addWidget(control)
        side.addStretch()
        self.side_note = label(tr('sidebar.verification'), 'muted', True)
        side.addWidget(self.side_note)
        outer.addWidget(self.sidebar)
        workspace_host = QWidget()
        workspace_host_layout = QHBoxLayout(workspace_host)
        workspace_host_layout.setContentsMargins(0, 0, 0, 0)
        workspace_widget = QWidget()
        workspace_widget.setMaximumWidth(1044)  # 980 content + two 32-pixel margins.
        workspace_host_layout.addWidget(workspace_widget)
        workspace = QVBoxLayout(workspace_widget)
        workspace.setContentsMargins(32, 28, 32, 24)
        workspace.setSpacing(20)
        header = QHBoxLayout()
        self.heading = label(tr('nav.home'), 'heading')
        header.addWidget(self.heading, 1)
        self.theme_combo = QComboBox()
        for text, value in ((tr('theme.system'), 'system'), (tr('theme.dark'), 'dark'), (tr('theme.light'), 'light')):
            self.theme_combo.addItem(text, value)
        self.theme_combo.setAccessibleName(tr('theme.label'))
        self.theme_combo.setMinimumWidth(150)
        self.theme_combo.setCurrentIndex(max(0, self.theme_combo.findData(self.theme_mode)))
        self.theme_combo.currentIndexChanged.connect(self.change_theme)
        header.addWidget(self.theme_combo)
        self.settings_button = button('')
        self.settings_button.setAccessibleName(tr('settings.open'))
        self.settings_button.setToolTip(tr('nav.settings'))
        self.settings_button.setFixedWidth(46)
        self.settings_button.clicked.connect(lambda: self.navigate(3))
        header.addWidget(self.settings_button)
        workspace.addLayout(header)
        self.notice = label('', 'error', True)
        self.notice.hide()
        workspace.addWidget(self.notice)
        self.pages = QStackedWidget()
        workspace.addWidget(self.pages, 1)
        outer.addWidget(workspace_host, 1)
        self.build_home()
        self.build_tasks()
        self.build_settings()
        self.nav[0].setChecked(True)
        self.apply_theme()
        self.controller.analysis.connect(self.on_analysis)
        self.controller.thumbnail.connect(self.on_thumbnail)
        self.controller.job.connect(self.on_job)
        self.controller.ready.connect(self.on_ready)
        self.controller.error.connect(self.show_error)
        self.controller.open_path.connect(lambda path: QDesktopServices.openUrl(QUrl.fromLocalFile(str(path))))
        self.controller.queued.connect(self.on_queued)
        self.controller.enqueue_failed.connect(self.on_enqueue_failed)
        self.controller.closed.connect(self.on_closed)
        self.shortcuts = []
        for key, callback in (('Ctrl+L', self.focus_url), ('Ctrl+Return', self.download), ('Ctrl+Enter', self.download), ('Esc', self.escape)):
            shortcut = QShortcut(QKeySequence(key), self)
            shortcut.activated.connect(callback)
            shortcut.setAutoRepeat(False)
            self.shortcuts.append(shortcut)
        QGuiApplication.styleHints().colorSchemeChanged.connect(self.on_system_theme)
        self.controller.start()
        self.collapse_sidebar()
        QTimer.singleShot(0, self.url_edit.setFocus)

    def page(self):
        scroll = QScrollArea()
        scroll.setWidgetResizable(True)
        scroll.setHorizontalScrollBarPolicy(Qt.ScrollBarPolicy.ScrollBarAlwaysOff)
        content = QWidget()
        content.setObjectName('page')
        layout = QVBoxLayout(content)
        layout.setContentsMargins(0, 0, 8, 0)
        layout.setSpacing(20)
        scroll.setWidget(content)
        self.pages.addWidget(scroll)
        return layout

    def build_home(self):
        home = self.page()
        home.addWidget(label(tr('home.subtitle'), 'muted', True))
        frame, layout = card()
        layout.addWidget(label(tr('url.caption'), 'eyebrow'))
        row = QHBoxLayout()
        row.setSpacing(8)
        self.url_edit = QLineEdit()
        self.url_edit.setObjectName('urlInput')
        self.url_edit.setPlaceholderText(tr('url.placeholder'))
        self.url_edit.setAccessibleName(tr('url.label'))
        self.url_edit.setMinimumWidth(80)
        self.url_edit.textChanged.connect(self.url_changed)
        self.url_edit.returnPressed.connect(self.analyze)
        row.addWidget(self.url_edit, 1)
        self.paste_button = button(tr('url.paste'))
        self.paste_button.clicked.connect(self.paste)
        row.addWidget(self.paste_button)
        self.clear_button = button('', name=tr('url.clear'))
        self.clear_button.setToolTip(tr('url.clear'))
        self.clear_button.setFixedWidth(44)
        self.clear_button.clicked.connect(self.url_edit.clear)
        self.clear_button.hide()
        row.addWidget(self.clear_button)
        self.analyze_button = button(tr('analyze.start'), primary=True)
        self.analyze_button.clicked.connect(self.analyze)
        self.analyze_button.setEnabled(False)
        row.addWidget(self.analyze_button)
        layout.addLayout(row)
        self.analysis_status = label('', 'muted', True)
        self.analysis_status.hide()
        layout.addWidget(self.analysis_status)
        layout.addWidget(label(tr('rights.hint'), 'muted', True))
        home.addWidget(frame)
        self.preview, preview = card()
        top = QHBoxLayout()
        top.setSpacing(20)
        self.thumbnail_label = label('▶')
        self.thumbnail_label.setObjectName('thumbnail')
        self.thumbnail_label.setFixedSize(208, 117)
        self.thumbnail_label.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self.thumbnail_label.setAccessibleName(tr('preview.missing'))
        self.thumbnail_label.setStyleSheet('font-size: 32px;')
        top.addWidget(self.thumbnail_label)
        metadata = QVBoxLayout()
        self.media_title = ElidedLabel('', lines=2, role='section')
        metadata.addWidget(self.media_title)
        self.media_meta = label('', 'muted', True)
        metadata.addWidget(self.media_meta)
        metadata.addStretch()
        top.addLayout(metadata, 1)
        preview.addLayout(top)
        self.media_tabs = QTabBar()
        self.media_tabs.setAccessibleName(tr('media.type'))
        self.media_tabs.setExpanding(False)
        self.media_tabs.currentChanged.connect(self.populate_formats)
        preview.addWidget(self.media_tabs)
        fields = QHBoxLayout()
        fields.setSpacing(16)
        formats = QVBoxLayout()
        formats.setSpacing(8)
        formats.addWidget(label(tr('format.caption'), 'eyebrow'))
        self.format_combo = QComboBox()
        self.format_combo.setAccessibleName(tr('format.label'))
        self.format_combo.setMinimumContentsLength(10)
        self.format_combo.currentIndexChanged.connect(self.update_download)
        formats.addWidget(self.format_combo)
        fields.addLayout(formats, 1)
        self.merge_hint = label('', 'muted', True)
        destination = QVBoxLayout()
        destination.setSpacing(8)
        destination.addWidget(label(tr('folder.caption'), 'eyebrow'))
        self.folder_edit = QLineEdit(self.destination)
        self.folder_edit.setReadOnly(True)
        self.folder_edit.setAccessibleName(tr('folder.label'))
        self.folder_button = button('', name=tr('folder.choose'))
        self.folder_button.setToolTip(tr('folder.collision_hint'))
        self.folder_button.setFixedWidth(44)
        self.folder_button.clicked.connect(self.choose_folder)
        folder = QHBoxLayout()
        folder.addWidget(self.folder_edit, 1)
        folder.addWidget(self.folder_button)
        destination.addLayout(folder)
        fields.addLayout(destination, 1)
        preview.addLayout(fields)
        self.download_button = button(tr('download.start'), primary=True)
        self.download_button.setMinimumHeight(48)
        self.download_button.setEnabled(False)
        self.download_button.clicked.connect(self.download)
        self.download_button.setMinimumWidth(200)
        footer = QHBoxLayout()
        footer.setSpacing(16)
        footer.addWidget(self.merge_hint, 1)
        footer.addWidget(self.download_button)
        preview.addLayout(footer)
        self.preview.hide()
        home.addWidget(self.preview)
        self.recent_heading = label(tr('home.recent'), 'eyebrow')
        home.addWidget(self.recent_heading)
        self.home_empty = EmptyCard(tr('home.empty.title'), tr('home.empty.body'))
        home.addWidget(self.home_empty)
        self.recent_layout = QVBoxLayout()
        self.recent_layout.setSpacing(12)
        home.addLayout(self.recent_layout)
        self.recent_card = None
        home.addStretch()

    def build_tasks(self):
        downloads = self.page()
        toolbar = QHBoxLayout()
        self.search = QLineEdit()
        self.search.setPlaceholderText(tr('search.placeholder'))
        self.search.setAccessibleName(tr('search.label'))
        self.search.textChanged.connect(self.refresh_lists)
        toolbar.addWidget(self.search, 1)
        self.sort_combo = QComboBox()
        for text, value in ((tr('sort.new'), 'new'), (tr('sort.old'), 'old'), (tr('sort.title'), 'title')):
            self.sort_combo.addItem(text, value)
        self.sort_combo.setAccessibleName(tr('sort.label'))
        self.sort_combo.currentIndexChanged.connect(self.refresh_lists)
        toolbar.addWidget(self.sort_combo)
        downloads.addLayout(toolbar)
        self.filters = QTabBar()
        self.filters.setAccessibleName(tr('filter.label'))
        self.filters.setExpanding(False)
        for name in (tr('filter.all'), tr('filter.active'), tr('filter.completed'), tr('filter.failed')):
            self.filters.addTab(name)
        self.filters.currentChanged.connect(self.refresh_lists)
        downloads.addWidget(self.filters)
        self.downloads_empty = EmptyCard(tr('downloads.empty.title'), tr('downloads.empty.body'))
        downloads.addWidget(self.downloads_empty)
        self.task_layout = QVBoxLayout()
        self.task_layout.setSpacing(12)
        downloads.addLayout(self.task_layout)
        downloads.addStretch()
        library = self.page()
        library.addWidget(label(tr('library.subtitle'), 'muted', True))
        self.library_empty = EmptyCard(tr('library.empty.title'), tr('library.empty.body'))
        library.addWidget(self.library_empty)
        self.library_layout = QVBoxLayout()
        self.library_layout.setSpacing(12)
        library.addLayout(self.library_layout)
        library.addStretch()

    def build_settings(self):
        settings = self.page()
        appearance, box = card()
        box.addWidget(label(tr('settings.appearance'), 'section'))
        box.addWidget(label(tr('settings.theme_hint'), 'muted', True))
        self.motion_box = QCheckBox(tr('settings.motion'))
        self.motion_box.setAccessibleName(tr('settings.motion_label'))
        self.motion_box.setChecked(self.reduced)
        self.motion_box.toggled.connect(self.change_motion)
        box.addWidget(self.motion_box)
        settings.addWidget(appearance)
        general, box = card()
        box.addWidget(label(tr('settings.saving'), 'section'))
        self.settings_folder = ElidedLabel(self.destination, role='muted')
        box.addWidget(self.settings_folder)
        control = button(tr('folder.change'))
        control.clicked.connect(self.choose_folder)
        box.addWidget(control, alignment=Qt.AlignmentFlag.AlignLeft)
        row = QHBoxLayout()
        row.addWidget(label(tr('queue.concurrency')), 1)
        self.concurrency_combo = QComboBox()
        for count in (1, 2, 3):
            self.concurrency_combo.addItem(str(count), count)
        self.concurrency_combo.setCurrentIndex(max(0, min(2, int(self.settings.value('concurrency', 2)) - 1)))
        self.concurrency_combo.setAccessibleName(tr('queue.concurrency_label'))
        self.concurrency_combo.setFixedWidth(100)
        self.concurrency_combo.currentIndexChanged.connect(lambda _: self.change_concurrency(self.concurrency_combo.currentData()))
        row.addWidget(self.concurrency_combo)
        box.addLayout(row)
        box.addWidget(label(tr('storage.limits'), 'muted', True))
        settings.addWidget(general)
        privacy, box = card()
        box.addWidget(label(tr('settings.privacy'), 'section'))
        box.addWidget(label(tr('privacy.explanation'), 'muted', True))
        data_button = button(tr('data.open'))
        data_button.clicked.connect(lambda: self.controller.command('data_folder'))
        box.addWidget(data_button, alignment=Qt.AlignmentFlag.AlignLeft)
        settings.addWidget(privacy)
        tools, box = card()
        box.addWidget(label(tr('settings.components'), 'section'))
        self.tools_label = label(tr('tools.checking'), 'muted', True)
        box.addWidget(self.tools_label)
        check = button(tr('tools.check'))
        check.clicked.connect(lambda: self.controller.command('tools'))
        box.addWidget(check, alignment=Qt.AlignmentFlag.AlignLeft)
        box.addWidget(label(tr('licenses.summary'), 'muted', True))
        settings.addWidget(tools)
        settings.addStretch()

    def navigate(self, index):
        self.pages.setCurrentIndex(index)
        self.nav[index].setChecked(True)
        self.heading.setText(self.nav[index].full_text)

    def focus_url(self):
        self.navigate(0)
        self.url_edit.setFocus()
        self.url_edit.selectAll()

    def paste(self):
        self.url_edit.setText(QApplication.clipboard().text().strip())
        self.url_edit.setFocus()

    def url_changed(self, text):
        self.generation = self.controller.invalidate()
        self.media, self.analyzing = None, False
        self.preview.hide()
        self.thumbnail_label.clear()
        self.thumbnail_label.setText('▶')
        self.clear_button.setVisible(bool(text))
        self.analyze_button.setText(tr('analyze.start'))
        self.analyze_button.setEnabled(bool(text.strip()) and self.backend_ready)
        self.analysis_status.hide()
        self.download_button.setEnabled(False)
        self.refresh_lists()

    def analyze(self):
        if self.analyzing:
            self.generation = self.controller.invalidate()
            self.analyzing = False
            self.analyze_button.setText(tr('analyze.start'))
            self.show_analysis(tr('analyze.canceled'))
            return
        if not self.backend_ready:
            return
        try:
            validate_url(self.url_edit.text())
        except ValueError:
            self.show_analysis(DownloadError(ErrorCode.INVALID_URL).message, error=True)
            self.url_edit.setFocus()
            return
        self.media = None
        self.preview.hide()
        self.analyzing = True
        self.generation = self.controller.analyze(self.url_edit.text())
        self.analyze_button.setText(tr('analyze.cancel'))
        self.show_analysis(tr('analyze.busy'))

    def show_analysis(self, text, error=False):
        self.analysis_status.setText(text)
        self.analysis_status.setProperty('role', 'error' if error else 'muted')
        self.analysis_status.style().unpolish(self.analysis_status)
        self.analysis_status.style().polish(self.analysis_status)
        self.analysis_status.show()

    def on_analysis(self, generation, result):
        if generation != self.generation or self.closing:
            return
        self.analyzing = False
        self.analyze_button.setText(tr('analyze.start'))
        if result.error:
            self.show_analysis(result.error.message, error=True)
            return
        self.media = result.media
        self.analysis_status.hide()
        self.media_title.set_full_text(self.media.display_title)
        duration = self.media.duration_seconds
        extra = f' · {int(duration // 60)}:{int(duration % 60):02}' if duration is not None else ''
        self.media_meta.setText(self.media.source_host + extra)
        self.media_tabs.blockSignals(True)
        while self.media_tabs.count():
            self.media_tabs.removeTab(0)
        if any(v.video_codec for v in self.media.formats):
            index = self.media_tabs.addTab(tr('media.video'))
            self.media_tabs.setTabData(index, 'video')
        if any(not v.video_codec and v.has_audio for v in self.media.formats):
            index = self.media_tabs.addTab(tr('media.audio'))
            self.media_tabs.setTabData(index, 'audio')
        self.media_tabs.setCurrentIndex(0)
        self.media_tabs.blockSignals(False)
        self.populate_formats()
        self.preview.show()
        self.refresh_lists()
        self.controller.load_thumbnail(generation, self.media.thumbnail_uri)

    def on_thumbnail(self, generation, image):
        if generation != self.generation or not self.media:
            return
        pixmap = QPixmap.fromImage(image).scaled(self.thumbnail_label.size(), Qt.AspectRatioMode.KeepAspectRatioByExpanding, Qt.TransformationMode.SmoothTransformation)
        x, y = max(0, (pixmap.width() - self.thumbnail_label.width()) // 2), max(0, (pixmap.height() - self.thumbnail_label.height()) // 2)
        self.thumbnail_label.setPixmap(pixmap.copy(x, y, self.thumbnail_label.width(), self.thumbnail_label.height()))
        self.thumbnail_label.setAccessibleName(tr('preview.prefix') + self.media.display_title)

    def populate_formats(self, *_):
        self.format_combo.clear()
        if not self.media:
            return
        audio = self.media_tabs.tabData(self.media_tabs.currentIndex()) == 'audio'
        for variant in self.media.formats:
            if bool(variant.video_codec) != audio:
                self.format_combo.addItem(format_text(variant), variant)
        self.update_download()

    def update_download(self, *_):
        variant = self.format_combo.currentData()
        tools = all(self.tools.get(key) for key in ('ffmpeg', 'ffprobe', 'yt-dlp'))
        enabled = self.backend_ready and self.media is not None and variant is not None and tools and not self.enqueue_pending
        self.download_button.setEnabled(enabled)
        reason = '' if enabled else (tr('download.pending') if self.enqueue_pending else
                    tr('download.analyze_first') if not self.media else
                    tr('download.no_variant') if variant is None else
                    tr('download.tools_missing'))
        self.download_button.setToolTip(reason or tr('download.shortcut'))
        self.download_button.setAccessibleDescription(reason)
        self.download_button.setText(tr('download.pending') if self.enqueue_pending else
            tr('download.repeat') if self.selection_key() in self.accepted_selections else tr('download.start'))
        self.merge_hint.setText((tr('download.merge') if variant and variant.requires_merge else tr('download.original')) if tools else reason)

    def download(self):
        if self.closing or self.enqueue_pending or not self.download_button.isEnabled() or not self.media:
            return
        key = self.selection_key()
        if key in self.accepted_selections:
            if self.repeat_dialog is not None:
                return
            dialog = QMessageBox(QMessageBox.Icon.Question, tr('download.repeat'), tr('download.repeat_question'),
                                QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.Cancel, self)
            dialog.setTextFormat(Qt.TextFormat.PlainText)
            dialog.button(QMessageBox.StandardButton.Yes).setText(tr('download.repeat_confirm'))
            dialog.button(QMessageBox.StandardButton.Cancel).setText(tr('download.repeat_cancel'))
            dialog.setDefaultButton(QMessageBox.StandardButton.Cancel)
            dialog.setEscapeButton(QMessageBox.StandardButton.Cancel)
            dialog.setWindowModality(Qt.WindowModality.WindowModal)
            dialog.finished.connect(lambda result: self.repeat_finished(result, key))
            self.repeat_dialog = dialog
            dialog.open()
        else:
            self.submit_download()

    def selection_key(self):
        variant = self.format_combo.currentData()
        return (self.url_edit.text().strip(), variant.selector, self.destination) if self.media and variant else None

    def repeat_finished(self, result, key):
        dialog, self.repeat_dialog = self.repeat_dialog, None
        if dialog:
            dialog.deleteLater()
        if result == QMessageBox.StandardButton.Yes.value and key == self.selection_key() and not self.closing:
            self.submit_download()

    def submit_download(self):
        if self.enqueue_pending or not self.media or not self.download_button.isEnabled():
            return
        self.enqueue_pending, self.pending_key = True, self.selection_key()
        self.notice.hide()
        self.update_download()
        self.show_analysis(tr('download.pending'))
        try:
            self.controller.command('enqueue', Options(self.url_edit.text(), Path(self.destination), self.format_combo.currentData()), self.media.display_title)
        except Exception:
            self.on_enqueue_failed(tr('error.command'))

    def on_queued(self, job_id):
        if self.pending_key is not None:
            self.accepted_selections.add(self.pending_key)
        self.enqueue_pending, self.pending_key = False, None
        self.update_download()
        self.show_analysis(tr('download.queued'))

    def on_enqueue_failed(self, message):
        self.enqueue_pending, self.pending_key = False, None
        self.update_download()
        self.show_error(message)

    def choose_folder(self):
        directory = QFileDialog.getExistingDirectory(self, tr('folder.label'), self.destination)
        if directory:
            self.destination = directory
            self.folder_edit.setText(directory)
            self.settings_folder.set_full_text(directory)
            self.settings.setValue('destination', directory)
            self.update_download()

    def on_ready(self, tools):
        self.backend_ready, self.tools = True, tools
        self.tools_label.setText(' · '.join(key + (tr('tools.found') if value else tr('tools.missing')) for key, value in tools.items()))
        self.analyze_button.setEnabled(bool(self.url_edit.text().strip()))
        self.update_download()

    def show_error(self, message):
        self.notice.setText(message)
        self.notice.show()

    def on_job(self, job):
        if self.closing:
            return
        previous = self.jobs.get(job.id)
        self.jobs[job.id] = job
        self.speeds[job.id] = self.meter.update(job, time.monotonic())
        if previous is None or previous.state != job.state or previous.resume != job.resume or previous.error != job.error:
            self.refresh_lists()
        elif not self.refresh_timer.isActive():
            self.refresh_timer.start()

    def update_card(self, item, job):
        speed, eta = self.speeds.get(job.id, (None, None))
        item.update_job(job, speed, eta, self.reduced, PALETTES[self.resolved_theme]['muted'])

    def sync_cards(self, layout, cards, jobs):
        visible = {job.id for job in jobs}
        for job_id, item in cards.items():
            item.setVisible(job_id in visible)
        for index, job in enumerate(jobs):
            if job.id not in cards:
                item = JobCard(job)
                item.action.connect(self.job_action)
                cards[job.id] = item
            item = cards[job.id]
            layout.removeWidget(item)
            layout.insertWidget(index, item)
            item.show()
            self.update_card(item, job)

    def refresh_lists(self, *_):
        if not hasattr(self, 'filters'):
            return
        items = list(self.jobs.values())
        groups = ('all', 'active', 'completed', 'failed')
        filtered = filtered_jobs(items, groups[self.filters.currentIndex()], self.search.text(), self.sort_combo.currentData(), self.hidden)
        self.sync_cards(self.task_layout, self.cards, filtered)
        self.downloads_empty.setVisible(not filtered)
        for index, name in enumerate((tr('filter.all'), tr('filter.active'), tr('filter.completed'), tr('filter.failed'))):
            self.filters.setTabText(index, f'{name} ({len(filtered_jobs(items, groups[index], hidden=self.hidden))})')
        completed = filtered_jobs(items, 'completed', hidden=self.hidden)
        self.sync_cards(self.library_layout, self.library_cards, completed)
        self.library_empty.setVisible(not completed)
        recent = filtered_jobs(items, 'active', hidden=self.hidden) or filtered_jobs(items, hidden=self.hidden)
        self.home_empty.setVisible(not recent and self.media is None)
        self.recent_heading.setVisible(bool(recent) or self.media is None)
        if recent:
            job = recent[0]
            if self.recent_card is None or self.recent_card.job.id != job.id:
                if self.recent_card:
                    self.recent_layout.removeWidget(self.recent_card)
                    self.recent_card.deleteLater()
                self.recent_card = JobCard(job)
                self.recent_card.action.connect(self.job_action)
                self.recent_layout.addWidget(self.recent_card)
            self.update_card(self.recent_card, job)
        elif self.recent_card is not None:
            self.recent_layout.removeWidget(self.recent_card)
            self.recent_card.deleteLater()
            self.recent_card = None

    def job_action(self, action, job_id):
        if action == 'details':
            self.details(self.jobs[job_id])
        elif action in ('open', 'folder'):
            self.controller.command('open', job_id, action == 'folder')
        else:
            self.controller.command(action, job_id)

    def details(self, job):
        dialog = QDialog(self)
        dialog.setWindowTitle(tr('details.title'))
        dialog.setMinimumWidth(440)
        layout = QVBoxLayout(dialog)
        layout.setContentsMargins(24, 24, 24, 24)
        layout.setSpacing(16)
        layout.addWidget(label(STATUS_RU[job.state], 'section'))
        layout.addWidget(label(job.error.message if job.error else tr('details.verification'), wrap=True))
        reason = tr('details.pause_unsupported') if job.backend == 'ytdlp' else tr('details.resume_validation')
        layout.addWidget(label(reason, 'muted', True))
        if job.error:
            layout.addWidget(label(tr('details.code') + job.error.code.value, 'muted'))
        layout.addWidget(label(tr('details.id') + job.id, 'muted', True))
        if job.state in (S.COMPLETED, S.FAILED, S.CANCELED):
            hide = button(tr('history.hide'))
            hide.setToolTip(tr('history.hide_hint'))
            hide.clicked.connect(lambda: self.hide_job(job.id, dialog))
            layout.addWidget(hide)
        controls = QDialogButtonBox(QDialogButtonBox.StandardButton.Close)
        controls.button(QDialogButtonBox.StandardButton.Close).setText(tr('dialog.close'))
        controls.rejected.connect(dialog.reject)
        layout.addWidget(controls)
        dialog.exec()

    def hide_job(self, job_id, dialog):
        self.hidden.add(job_id)
        self.settings.setValue('hidden_jobs', sorted(self.hidden))
        self.refresh_lists()
        dialog.accept()

    def change_theme(self, *_):
        self.theme_mode = self.theme_combo.currentData()
        self.settings.setValue('theme', self.theme_mode)
        self.apply_theme()

    def on_system_theme(self, _):
        if self.theme_mode == 'system' and not self.closing:
            self.apply_theme()

    def apply_theme(self):
        mode = self.theme_mode
        if mode == 'system':
            scheme = QGuiApplication.styleHints().colorScheme()
            mode = 'dark' if scheme == Qt.ColorScheme.Dark else 'light'
        self.resolved_theme = mode if mode in PALETTES else 'light'
        self.setStyleSheet(stylesheet(self.resolved_theme, self.reduced))
        color = PALETTES[self.resolved_theme]['muted']
        for control in self.nav:
            control.setIcon(icon(control.glyph, color))
            control.setIconSize(QSize(20, 20))
        self.paste_button.setIcon(icon('paste', color))
        self.clear_button.setIcon(icon('clear', color))
        self.settings_button.setIcon(icon('settings', color))
        self.folder_button.setIcon(icon('folder', color))
        self.refresh_lists()

    def change_motion(self, checked):
        self.reduced = checked
        self.settings.setValue('reduced_motion', checked)
        self.apply_theme()

    def change_concurrency(self, value):
        self.settings.setValue('concurrency', value)
        self.controller.command('concurrency', value)

    def escape(self):
        if self.analyzing:
            self.analyze()
        else:
            self.search.clear()

    def collapse_sidebar(self):
        collapsed = self.width() < 1020
        self.sidebar.setFixedWidth(80 if collapsed else 196)
        self.brand_text.setVisible(not collapsed)
        self.side_note.setVisible(not collapsed)
        for control in self.nav:
            control.setText('' if collapsed else control.full_text)

    def resizeEvent(self, event):
        if hasattr(self, 'sidebar'):
            self.collapse_sidebar()
        super().resizeEvent(event)

    def closeEvent(self, event):
        if self.allow_close:
            self.settings.sync()
            event.accept()
        else:
            event.ignore()
            self.closing = True
            self.setEnabled(False)
            self.notice.setText(tr('shutdown.busy'))
            self.notice.show()
            self.controller.shutdown()

    def on_closed(self):
        self.allow_close = True
        self.shutdown_finished.emit()
        self.close()
