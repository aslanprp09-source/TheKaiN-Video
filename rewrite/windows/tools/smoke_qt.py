"""Actual PySide6 screenshots: real queue/HTTP/probe states, owned fixtures only."""
import argparse
import json
import os
import sys
import tempfile
import threading
import time
from dataclasses import replace
from pathlib import Path
from http.server import ThreadingHTTPServer

os.environ.setdefault('QT_QPA_PLATFORM', 'offscreen')
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from PySide6.QtCore import QSettings, Qt
from PySide6.QtTest import QTest
from PySide6.QtWidgets import QApplication
from desktop.controller import Controller
from desktop.window import MainWindow
from download_queue import DownloadQueue
from engine import Options
from extractor import parse_metadata
from jobs import JobStatus as S
from transports import HttpTransport
from test_download_queue import FixtureHandler, VARIANT


class OwnedAnalyzer:
    def analyze(self, url, cancel):
        data = json.loads((Path(__file__).resolve().parents[1] / 'fixtures/three.json').read_text())
        data['title'] = 'Тестовый ролик — отдельные видео и аудио дорожки'
        data['thumbnail'] = None
        if cancel.wait(.05):
            from domain import AnalyzeResult, DownloadError, ErrorCode
            return AnalyzeResult(error=DownloadError(ErrorCode.CANCELED))
        return parse_metadata(data, url)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--screenshots', type=Path, required=True)
    parser.add_argument('--theme', choices=('dark', 'light'), required=True)
    args = parser.parse_args()
    app = QApplication.instance() or QApplication([])
    app.setStyle('Fusion')
    entered, release = threading.Event(), threading.Event()

    class GatedHttp(HttpTransport):
        def transfer(self, job, control, progress, capability, phase):
            def measured(amount, total):
                progress(amount, total)
                if amount >= 65536 and not entered.is_set():
                    entered.set()
                    while not release.wait(.01):
                        control.check()
            return super().transfer(job, control, measured, capability, phase)

    with tempfile.TemporaryDirectory() as directory:
        root = Path(directory)
        server = ThreadingHTTPServer(('127.0.0.1', 0), FixtureHandler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        url = f'http://127.0.0.1:{server.server_port}'
        def factory(*a, **kw):
            return DownloadQueue(*a, **kw, transport_factory=lambda job: GatedHttp())
        controller = Controller(root / 'data', queue_factory=factory, analyzer=OwnedAnalyzer())
        settings = QSettings(str(root / 'preferences.ini'), QSettings.Format.IniFormat)
        settings.setValue('theme', args.theme)
        settings.setValue('destination', str(root / 'Видео'))
        window = MainWindow(root / 'data', controller=controller, settings=settings)
        window.show()
        window.activateWindow()
        records = []

        def wait(predicate, timeout=10):
            deadline = time.monotonic() + timeout
            while not predicate() and time.monotonic() < deadline:
                QTest.qWait(10)
            assert predicate(), 'Actual application state timed out'
            QTest.qWait(120)

        def capture(state, job=None):
            QTest.qWait(50)
            args.screenshots.mkdir(parents=True, exist_ok=True)
            picture = window.grab()
            ratio = window.devicePixelRatioF()
            assert abs(ratio - float(os.environ.get('QT_SCALE_FACTOR', '1'))) < .05
            name = f'{args.theme}-{int(round(ratio * 100))}-{state}.png'
            assert picture.save(str(args.screenshots / name))
            records.append({'file': name, 'theme': window.resolved_theme, 'scale': ratio,
                            'logical_size': [window.width(), window.height()],
                            'pixel_size': [picture.width(), picture.height()],
                            'state': state, 'job_state': job.state.value if job else None,
                            'progress_bytes': job.progress_bytes if job else None})

        try:
            wait(lambda: window.backend_ready)
            capture('empty')
            window.url_edit.setText('https://example.invalid/owned-video')
            QTest.keyClick(window.url_edit, Qt.Key.Key_Return)
            wait(lambda: window.media is not None)
            assert window.download_button.isEnabled()
            capture('ready')
            # Downloading/failed/completed screenshots use a real local HTTP transfer
            # through the unchanged M2 queue. The gate holds real measured bytes.
            first = controller.queue.enqueue(Options(url + '/slow', root / 'Видео', VARIANT),
                'Собственная аудиозапись — локальная проверка', backend='http')
            QTest.mouseClick(window.nav[1], Qt.MouseButton.LeftButton)
            wait(lambda: first.id in window.jobs and window.jobs[first.id].progress_bytes >= 65536)
            capture('downloading', window.jobs[first.id])
            release.set()
            wait(lambda: window.jobs[first.id].state == S.COMPLETED)
            completed = window.jobs[first.id]
            assert completed.output.is_file() and completed.percent == 100
            capture('completed', completed)
            second = controller.queue.enqueue(Options(url + '/corrupt', root / 'Видео', replace(VARIANT, byte_size_known=None)),
                'Повреждённая запись — сохранение не подтверждено', backend='http')
            wait(lambda: second.id in window.jobs and window.jobs[second.id].state == S.FAILED)
            window.filters.setCurrentIndex(3)
            capture('failed', window.jobs[second.id])
            QTest.mouseClick(window.nav[3], Qt.MouseButton.LeftButton)
            capture('settings')
            # Minimum-window keyboard and full title checks are also exercised by tests.
            manifest = args.screenshots / f'{args.theme}-{int(window.devicePixelRatioF() * 100)}-manifest.json'
            manifest.write_text(json.dumps(records, ensure_ascii=False, indent=2))
            print(f'PASS: {args.theme} {window.devicePixelRatioF():g}x; 6 actual Qt states, real verified output')
        finally:
            release.set()
            window.close()
            wait(lambda: window.allow_close)
            server.shutdown()
            server.server_close()
            thread.join()
            window.deleteLater()
            app.processEvents()


if __name__ == '__main__':
    main()
