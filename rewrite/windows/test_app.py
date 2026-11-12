"""Real Qt widgets and keyboard events; deterministic owned metadata, no public network."""
import os
os.environ.setdefault('QT_QPA_PLATFORM', 'offscreen')
import json
import tempfile
import time
import unittest
from contextlib import contextmanager
from dataclasses import replace
from pathlib import Path
from PySide6.QtCore import QObject, Signal, QTimer, Qt, QSettings, QEvent
from PySide6.QtTest import QTest
from PySide6.QtWidgets import QApplication, QLineEdit
from domain import AnalyzeResult, DownloadError, ErrorCode, FormatVariant
from jobs import DownloadJob, JobStatus as S, ResumeCapability as R
from extractor import parse_metadata
from desktop.controller import Controller
from desktop.window import MainWindow
from desktop.presentation import filtered_jobs, SpeedMeter

APP = QApplication.instance() or QApplication([])
APP.setStyle('Fusion')
FIXTURES = Path(__file__).with_name('fixtures')


def wait_until(predicate, timeout=3):
    deadline = time.monotonic() + timeout
    while not predicate() and time.monotonic() < deadline:
        QTest.qWait(10)
    assert predicate(), 'Qt operation timed out'


@contextmanager
def async_window(directory, analyzer=None):
    """A real background controller must stop even if fixture startup/assertions fail."""
    controller = Controller(directory, analyzer=analyzer)
    window = MainWindow(directory, controller=controller)
    window.show()
    try:
        # Hosted Windows cold initialization has a separate bounded allowance;
        # ordinary state transitions and responsiveness assertions retain 3s.
        wait_until(lambda: window.backend_ready, timeout=10)
        yield window, controller
    finally:
        window.close()
        wait_until(lambda: window.allow_close, timeout=10)
        window.deleteLater()
        APP.sendPostedEvents(None, QEvent.Type.DeferredDelete)
        APP.processEvents()


class FixtureController(QObject):
    analysis = Signal(int, object)
    thumbnail = Signal(int, object)
    job = Signal(object)
    ready = Signal(object)
    error = Signal(str)
    queued = Signal(str)
    enqueue_failed = Signal(str)
    open_path = Signal(object)
    closed = Signal()

    def __init__(self):
        super().__init__()
        self.generation, self.calls = 0, []

    def start(self):
        self.ready.emit({'yt-dlp': True, 'ffmpeg': True, 'ffprobe': True})

    def invalidate(self):
        self.generation += 1
        return self.generation

    def analyze(self, url):
        generation = self.invalidate()
        self.calls.append(('analyze', url))
        return generation

    def command(self, *args):
        self.calls.append(args)

    def load_thumbnail(self, *args):
        pass

    def shutdown(self):
        QTimer.singleShot(0, self.closed.emit)


class DesktopTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.controller = FixtureController()
        self.window = MainWindow(self.root, controller=self.controller)
        self.window.show()
        self.window.activateWindow()
        QTest.qWait(20)

    def tearDown(self):
        self.window.close()
        wait_until(lambda: self.window.allow_close)
        self.window.deleteLater()
        APP.processEvents()
        self.temp.cleanup()

    def ready(self, title=None):
        self.window.url_edit.setText('https://example.invalid/owned-fixture')
        data = json.loads((FIXTURES / 'three.json').read_text(encoding='utf-8'))
        if title:
            data['title'] = title
        result = parse_metadata(data, self.window.url_edit.text())
        self.controller.analysis.emit(self.window.generation, result)
        APP.processEvents()
        return result

    def job(self, state=S.DOWNLOADING, **changes):
        job = DownloadJob('owned-job', 'https://example.invalid/v?token=test', 'Собственный тестовый ролик',
            FormatVariant('muxed', 'mp4', 'mp4', video_codec='h264', audio_codec='aac', has_audio=True),
            self.root / 'out', self.root / 'stage', state=state, progress_bytes=100, total_bytes=100,
            resume=R.NO)
        return replace(job, **changes)

    def test_explicit_paste_and_clear_invalidate_preview(self):
        self.ready()
        self.assertFalse(self.window.preview.isHidden())
        QTest.mouseClick(self.window.clear_button, Qt.MouseButton.LeftButton)
        self.assertEqual(self.window.url_edit.text(), '')
        self.assertIsNone(self.window.media)
        self.assertTrue(self.window.preview.isHidden())
        self.assertFalse(self.window.download_button.isEnabled())
        APP.clipboard().setText('https://example.invalid/deliberate-paste')
        QTest.mouseClick(self.window.paste_button, Qt.MouseButton.LeftButton)
        self.assertEqual(self.window.url_edit.text(), 'https://example.invalid/deliberate-paste')

    def test_stale_analysis_is_discarded_after_url_edit(self):
        self.window.url_edit.setText('https://example.invalid/old')
        QTest.keyClick(self.window.url_edit, Qt.Key.Key_Return)
        old = self.window.generation
        result = parse_metadata(json.loads((FIXTURES / 'one.json').read_text(encoding='utf-8')), self.window.url_edit.text())
        self.window.url_edit.setText('https://example.invalid/new')
        self.controller.analysis.emit(old, result)
        self.assertIsNone(self.window.media)
        self.assertTrue(self.window.preview.isHidden())

    def test_invalid_link_is_inline_and_never_sent_to_extractor(self):
        self.window.url_edit.setText('https://user:password@example.invalid/video')
        QTest.keyClick(self.window.url_edit, Qt.Key.Key_Return)
        self.assertIn('HTTP(S)', self.window.analysis_status.text())
        self.assertEqual(self.window.analysis_status.property('role'), 'error')
        self.assertFalse(any(c[0] == 'analyze' for c in self.controller.calls))
        self.assertNotIn('password', self.window.analysis_status.text())

    def test_keyboard_focus_navigation_and_cancel(self):
        self.window.navigate(1)
        self.window.search.setFocus()
        QTest.keyClick(self.window, Qt.Key.Key_L, Qt.KeyboardModifier.ControlModifier)
        APP.processEvents()
        self.assertEqual(self.window.pages.currentIndex(), 0)
        self.assertTrue(self.window.url_edit.hasFocus())
        self.window.url_edit.setText('https://example.invalid/owned')
        QTest.keyClick(self.window.url_edit, Qt.Key.Key_Return)
        self.assertTrue(self.window.analyzing)
        QTest.keyClick(self.window, Qt.Key.Key_Escape)
        self.assertFalse(self.window.analyzing)
        QTest.keyClick(self.window.url_edit, Qt.Key.Key_Tab)
        self.assertTrue(self.window.paste_button.hasFocus())

    def test_unknown_size_and_only_offered_variants(self):
        result = self.ready()
        self.assertEqual(self.window.format_combo.count(), 2)
        self.window.format_combo.setCurrentIndex(1)
        self.assertIn('—', self.window.format_combo.currentText())
        self.assertNotIn('1080', self.window.format_combo.currentText())
        self.window.media_tabs.setCurrentIndex(1)
        self.assertEqual(self.window.format_combo.count(), 1)
        self.assertEqual(self.window.format_combo.currentData(), result.media.formats[-1])
        self.assertNotIn('[audio', self.window.format_combo.currentText())

    def test_download_shortcut_sends_exact_variant_without_blocking(self):
        self.ready()
        selected = self.window.format_combo.currentData()
        self.window.url_edit.setFocus()
        QTest.keyClick(self.window, Qt.Key.Key_Return, Qt.KeyboardModifier.ControlModifier)
        calls = [c for c in self.controller.calls if c[0] == 'enqueue']
        self.assertEqual(len(calls), 1)
        self.assertEqual(calls[0][1].quality, selected)

    def test_missing_tools_disable_download_with_reason(self):
        self.ready()
        self.window.on_ready({'yt-dlp': True, 'ffmpeg': False, 'ffprobe': True})
        self.assertFalse(self.window.download_button.isEnabled())
        self.assertIn('FFmpeg', self.window.download_button.toolTip())

    def test_fast_clicks_and_shortcuts_enqueue_once_then_require_explicit_repeat(self):
        from PySide6.QtWidgets import QMessageBox
        self.ready()
        control = self.window.download_button
        QTest.mouseClick(control, Qt.MouseButton.LeftButton)
        QTest.mouseClick(control, Qt.MouseButton.LeftButton)
        QTest.keyClick(self.window, Qt.Key.Key_Return, Qt.KeyboardModifier.ControlModifier)
        self.assertEqual(len([c for c in self.controller.calls if c[0] == 'enqueue']), 1)
        self.assertFalse(control.isEnabled())
        self.controller.queued.emit('accepted')
        QTest.mouseClick(control, Qt.MouseButton.LeftButton)
        self.assertEqual(len([c for c in self.controller.calls if c[0] == 'enqueue']), 1)
        self.assertIsNotNone(self.window.repeat_dialog)
        dialog = self.window.repeat_dialog
        QTest.mouseClick(dialog.button(QMessageBox.StandardButton.Cancel), Qt.MouseButton.LeftButton)
        APP.processEvents()
        self.assertIsNone(self.window.repeat_dialog)
        QTest.mouseClick(control, Qt.MouseButton.LeftButton)
        dialog = self.window.repeat_dialog
        QTest.mouseClick(dialog.button(QMessageBox.StandardButton.Yes), Qt.MouseButton.LeftButton)
        APP.processEvents()
        self.assertEqual(len([c for c in self.controller.calls if c[0] == 'enqueue']), 2)
        self.assertTrue(self.window.enqueue_pending)

    def test_enqueue_error_reenables_button_and_selection_cannot_bypass_pending(self):
        self.ready()
        self.window.download()
        self.window.format_combo.setCurrentIndex(1)
        self.assertFalse(self.window.download_button.isEnabled())
        self.controller.error.emit('Ошибка другого действия')
        self.assertTrue(self.window.enqueue_pending)
        self.assertFalse(self.window.download_button.isEnabled())
        self.controller.enqueue_failed.emit('Не удалось добавить задачу')
        self.assertFalse(self.window.enqueue_pending)
        self.assertTrue(self.window.download_button.isEnabled())
        self.window.download()
        self.assertEqual(len([c for c in self.controller.calls if c[0] == 'enqueue']), 2)

    def test_progress_is_truthful_and_pause_only_when_supported(self):
        job = self.job()
        self.window.on_job(job)
        item = self.window.cards[job.id]
        self.assertEqual(item.progress.value(), 99)
        self.assertTrue(item.actions['pause'].isHidden())
        self.window.on_job(replace(job, backend='http', resume=R.YES))
        self.assertFalse(item.actions['pause'].isHidden())
        self.window.on_job(replace(job, state=S.VERIFYING))
        self.assertEqual(item.progress.maximum(), 0)
        self.assertTrue(item.actions['open'].isHidden())
        self.window.on_job(replace(job, state=S.COMPLETED, verified_bytes=100, output=self.root / 'owned.mp4'))
        self.assertEqual(item.progress.value(), 100)
        self.assertFalse(item.actions['open'].isHidden())

    def test_failure_recovery_and_completed_library(self):
        job = self.job(S.FAILED, error=DownloadError(ErrorCode.VERIFY_FAILED))
        self.window.on_job(job)
        self.window.navigate(1)
        item = self.window.cards[job.id]
        QTest.mouseClick(item.actions['retry'], Qt.MouseButton.LeftButton)
        self.assertIn(('retry', job.id), self.controller.calls)
        self.assertTrue(self.window.library_empty.isVisibleTo(self.window.pages.widget(2)))
        self.window.on_job(replace(job, state=S.COMPLETED, error=None, output=self.root / 'owned.mp4', verified_bytes=100))
        self.assertIn(job.id, self.window.library_cards)
        self.assertTrue(self.window.library_empty.isHidden())

    def test_filters_search_sort_and_history_hiding_never_delete_file(self):
        one = self.job(S.FAILED, error=DownloadError(ErrorCode.VERIFY_FAILED))
        two = replace(one, id='second', title='Другой файл', state=S.COMPLETED, error=None)
        self.window.on_job(one)
        self.window.on_job(two)
        self.window.navigate(1)
        self.window.filters.setCurrentIndex(3)
        self.assertTrue(self.window.cards[two.id].isHidden())
        self.assertFalse(self.window.cards[one.id].isHidden())
        self.window.search.setText('нет совпадений')
        self.assertTrue(self.window.cards[one.id].isHidden())
        output = self.root / 'preserved.mp4'
        output.write_bytes(b'user file')
        from unittest.mock import Mock
        self.window.hide_job(two.id, Mock())
        self.assertEqual(output.read_bytes(), b'user file')
        self.assertIn(two.id, self.window.hidden)
        self.window.hide_job(one.id, Mock())
        self.assertIsNone(self.window.recent_card)

    def test_long_russian_title_is_elided_with_full_accessible_name(self):
        title = 'Очень длинное русское название видео с символами <и без HTML> ' * 15
        self.ready(title)
        self.window.resize(900, 620)
        QTest.qWait(30)
        self.assertIn('…', self.window.media_title.text())
        self.assertEqual(self.window.media_title.accessibleName(), title)
        self.assertEqual(self.window.media_title.toolTip(), title)
        self.assertEqual(self.window.sidebar.width(), 80)
        self.assertTrue(all(not b.text() for b in self.window.nav))
        self.assertEqual(self.window.minimumWidth(), 900)
        self.window.pages.widget(0).ensureWidgetVisible(self.window.download_button)
        QTest.qWait(10)
        self.assertTrue(self.window.download_button.isVisible())

    def test_settings_persist_theme_motion_and_concurrency(self):
        self.window.navigate(3)
        APP.processEvents()
        selector = self.window.concurrency_combo
        self.assertEqual(selector.currentText(), '2')
        self.assertGreater(selector.width(), selector.fontMetrics().horizontalAdvance(selector.currentText()) + 40)
        self.window.theme_combo.setCurrentIndex(self.window.theme_combo.findData('dark'))
        self.assertEqual(self.window.resolved_theme, 'dark')
        self.window.theme_combo.setCurrentIndex(self.window.theme_combo.findData('light'))
        self.assertEqual(self.window.resolved_theme, 'light')
        selector.setFocus()
        QTest.keyClick(selector, Qt.Key.Key_Down)
        self.assertIn(('concurrency', 3), self.controller.calls)
        self.assertEqual(selector.currentData(), 3)
        self.assertEqual(selector.count(), 3)
        self.window.motion_box.setChecked(True)
        self.window.settings.sync()
        stored = QSettings(str(self.root / 'preferences.ini'), QSettings.Format.IniFormat)
        self.assertEqual(stored.value('theme'), 'light')
        self.assertTrue(stored.value('reduced_motion', False, bool))

    def test_icon_only_controls_have_accessible_names(self):
        self.assertTrue(self.window.settings_button.accessibleName())
        self.assertTrue(self.window.clear_button.accessibleName())
        self.assertTrue(self.window.folder_button.accessibleName())
        self.assertTrue(all(b.accessibleName() for b in self.window.nav))


class AsyncBridgeTest(unittest.TestCase):
    def test_failed_startup_wait_stops_controller_and_releases_queue_lock(self):
        from unittest.mock import patch
        from download_queue import InstanceLock
        with tempfile.TemporaryDirectory() as directory:
            controller = Controller(directory)
            real_wait = wait_until
            calls = []
            def fail_first_wait(predicate, timeout=3):
                calls.append(timeout)
                if len(calls) == 1:
                    real_wait(lambda: controller.queue is not None and controller.queue.lease is not None, timeout=10)
                    raise AssertionError('owned injected startup timeout')
                real_wait(predicate, timeout)
            with patch('test_app.Controller', return_value=controller), \
                    patch('test_app.wait_until', side_effect=fail_first_wait), \
                    self.assertRaisesRegex(AssertionError, 'owned injected startup timeout'):
                with async_window(directory):
                    self.fail('Failed fixture startup must not enter the test body')
            self.assertTrue(controller.stopping)
            lock_path = Path(directory) / 'queue.lock'
            lease = InstanceLock(lock_path)
            lease.close()
            lock_path.unlink()  # Windows denies this if any fixture handle survived.

    def test_actual_enqueue_failure_signal_releases_pending_guard(self):
        from unittest.mock import patch
        class OwnedAnalyzer:
            def analyze(self, url, cancel):
                return parse_metadata(json.loads((FIXTURES / 'one.json').read_text(encoding='utf-8')), url)
        with tempfile.TemporaryDirectory() as directory, async_window(directory, OwnedAnalyzer()) as (window, controller):
            window.url_edit.setText('https://example.invalid/owned')
            window.analyze()
            wait_until(lambda: window.media is not None)
            with patch.object(controller.queue, 'enqueue', side_effect=OSError('owned failure')) as enqueue:
                window.download()
                self.assertTrue(window.enqueue_pending)
                wait_until(lambda: not window.enqueue_pending)
                enqueue.assert_called_once()
            self.assertTrue(window.download_button.isEnabled())
            self.assertFalse(window.notice.isHidden())

    def test_actual_bounded_thumbnail_worker_and_stale_image_rejection(self):
        import threading
        from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
        from PySide6.QtCore import QBuffer, QByteArray, QIODevice
        from PySide6.QtGui import QImage, QColor
        image = QImage(8, 8, QImage.Format.Format_RGB32)
        image.fill(QColor('#336699'))
        data, buffer = QByteArray(), QBuffer()
        buffer.setBuffer(data)
        buffer.open(QIODevice.OpenModeFlag.WriteOnly)
        self.assertTrue(image.save(buffer, 'PNG'))
        payload = bytes(data)
        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass
            def do_GET(self):
                self.send_response(200)
                self.send_header('Content-Type', 'image/png')
                self.send_header('Content-Length', str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)
        server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        class OwnedAnalyzer:
            def analyze(self, url, cancel):
                fixture = json.loads((FIXTURES / 'one.json').read_text(encoding='utf-8'))
                fixture['thumbnail'] = f'http://127.0.0.1:{server.server_port}/owned.png'
                return parse_metadata(fixture, url)
        try:
            with tempfile.TemporaryDirectory() as directory, async_window(directory, OwnedAnalyzer()) as (window, controller):
                window.url_edit.setText('https://example.invalid/owned')
                window.analyze()
                wait_until(lambda: not window.thumbnail_label.pixmap().isNull())
                previous = window.generation
                window.url_edit.setText('https://example.invalid/changed')
                controller.thumbnail.emit(previous, image)
                self.assertTrue(window.thumbnail_label.pixmap().isNull())
                self.assertTrue(window.preview.isHidden())
        finally:
            server.shutdown()
            server.server_close()
            thread.join()

    def test_actual_worker_analysis_and_shutdown_leave_ui_responsive(self):
        class OwnedAnalyzer:
            def analyze(self, url, cancel):
                cancel.wait(.06)
                return parse_metadata(json.loads((FIXTURES / 'one.json').read_text(encoding='utf-8')), url)
        with tempfile.TemporaryDirectory() as directory, async_window(directory, OwnedAnalyzer()) as (window, controller):
            window.url_edit.setText('https://example.invalid/owned')
            window.analyze()
            self.assertTrue(window.analyzing)
            window.navigate(1)
            self.assertEqual(window.pages.currentIndex(), 1)
            wait_until(lambda: window.media is not None)


class PresentationTest(unittest.TestCase):
    def test_speed_requires_real_observed_time_and_resets_on_stage_change(self):
        job = DownloadJob('fixture', 'https://example.invalid/v', 'fixture', FormatVariant('a', 'wav', 'wav'),
                          Path('/out'), Path('/stage'), state=S.DOWNLOADING, total_bytes=1000)
        meter = SpeedMeter()
        self.assertEqual(meter.update(job, 10), (None, None))
        speed, eta = meter.update(replace(job, progress_bytes=200), 12)
        self.assertEqual((speed, eta), (100, 8))
        self.assertEqual(meter.update(replace(job, state=S.VERIFYING), 13), (None, None))
        self.assertEqual(meter.update(replace(job, attempt=2, progress_bytes=0), 14), (None, None))
