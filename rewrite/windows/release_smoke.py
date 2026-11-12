"""Explicit packaging QA mode, using owned generated media and loopback only."""
import functools
import hashlib
import http.server
import json
import shutil
import subprocess
import tempfile
import threading
import time
from pathlib import Path


def pipeline(root, evidence):
    from download_queue import DownloadQueue
    from engine import Options
    from extractor import SourceAnalyzer
    from extractor_runtime import extractor_command
    from jobs import JobStatus as S
    from process_runner import Control, run_process
    from transports import YtDlpTransport
    version = run_process(extractor_command() + ['--version'], Control(), timeout=30)
    assert version.returncode == 0 and version.stdout.strip() == '2026.10.26', 'Worker version mismatch'
    for filename, arguments in [
        ('video.mp4', ['color=c=blue:s=160x90:r=10', '-an', '-c:v', 'mpeg4']),
        ('audio.m4a', ['anullsrc=r=8000:cl=mono', '-c:a', 'aac'])]:
        subprocess.run(['ffmpeg', '-nostdin', '-v', 'error', '-f', 'lavfi', '-i', arguments[0],
                        '-t', '1', *arguments[1:], str(root / filename)], check=True, timeout=30)

    class Handler(http.server.SimpleHTTPRequestHandler):
        def log_message(self, *args):
            pass
    server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), functools.partial(Handler, directory=str(root)))
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    url = f'http://127.0.0.1:{server.server_port}/watch'
    data = {'id': 'owned-packaging', 'title': 'Собственное видео — упаковка', 'extractor': 'fixture',
            'webpage_url': url, 'formats': [
                {'format_id': 'video', 'url': url.replace('watch', 'video.mp4'), 'ext': 'mp4',
                 'vcodec': 'mp4v.20.9', 'acodec': 'none', 'height': 90, 'width': 160},
                {'format_id': 'audio', 'url': url.replace('watch', 'audio.m4a'), 'ext': 'm4a',
                 'vcodec': 'none', 'acodec': 'mp4a.40.2'}]}
    info = root / 'owned.json'
    info.write_text(json.dumps(data), encoding='utf-8')
    queue = None
    try:
        # Metadata crosses the real frozen worker boundary too. No public-site claim.
        analyzer = SourceAnalyzer(extractor_command() + ['--load-info-json', str(info), '--no-check-formats'])
        analyzed = analyzer.analyze(url)
        assert analyzed.media and len(analyzed.media.formats) == 2, 'Worker JSON analysis failed'
        assert analyzed.media.display_title == data['title'], 'Worker corrupted the Unicode protocol'
        states = []
        queue = DownloadQueue(root / 'queue', callback=lambda job: states.append(job.state),
                              transport_factory=lambda job: YtDlpTransport(fixture_info=info))
        queue.start()
        job = queue.enqueue(Options(url, root / 'Видео с пробелами', analyzed.media.formats[0]), data['title'])
        deadline = time.monotonic() + 90
        while time.monotonic() < deadline:
            result = queue.repository.get(job.id)
            if result.state in (S.COMPLETED, S.FAILED):
                break
            time.sleep(.05)
        assert result.state == S.COMPLETED and result.output.is_file(), 'Frozen pipeline failed'
        assert S.POSTPROCESSING in states and S.VERIFYING in states, 'Verification/merge was skipped'
        copied = evidence / 'owned-result.mp4'
        shutil.copyfile(result.output, copied)
        digest = hashlib.sha256(copied.read_bytes()).hexdigest()
        assert digest == result.digest, 'Published checksum mismatch'
        return {'worker': version.stdout.strip(), 'sha256': digest, 'bytes': copied.stat().st_size,
                'merge': True, 'ffprobe_and_full_decode': True}
    finally:
        if queue:
            queue.close()
        server.shutdown()
        server.server_close()
        thread.join()


def gui(root, evidence):
    from PySide6.QtCore import QSettings, Qt, qVersion
    from PySide6.QtWidgets import QApplication
    from desktop.window import MainWindow
    app = QApplication.instance() or QApplication([])
    app.setStyle('Fusion')
    settings = QSettings(str(root / 'qa.ini'), QSettings.Format.IniFormat)
    window = MainWindow(root / 'gui-data', settings=settings)
    window.show()
    def wait(predicate):
        deadline = time.monotonic() + 20
        while not predicate() and time.monotonic() < deadline:
            app.processEvents()
            time.sleep(.01)
        assert predicate(), 'Packaged Qt startup/shutdown timed out'
    try:
        wait(lambda: window.backend_ready)
        assert all(window.tools.values()), 'Installed runtime prerequisites missing'
        assert not window.brand_icon.pixmap().isNull(), 'Approved SVG/plugin missing'
        assert window.grab().save(str(evidence / 'installed-home.png')), 'Screenshot failed'
        preview = evidence / 'installed-home.webp'
        for quality in (65, 45, 25):
            assert window.grab().scaledToWidth(720, Qt.TransformationMode.SmoothTransformation).save(str(preview), 'WEBP', quality)
            if preview.stat().st_size <= 18000:
                break
        assert preview.stat().st_size <= 18000, 'Bounded review preview is too large'
        return {'qt': qVersion(), 'tools': window.tools, 'startup': True, 'svg': True}
    finally:
        window.close()
        wait(lambda: window.allow_close)
        window.deleteLater()
        app.processEvents()


def main(directory):
    evidence = Path(directory).resolve()
    evidence.mkdir(parents=True, exist_ok=True)
    report = {'status': 'FAIL', 'fixture': 'generated owned video+audio, loopback HTTP'}
    try:
        with tempfile.TemporaryDirectory(prefix='TheKaiN QA ') as directory:
            root = Path(directory)
            report['pipeline'] = pipeline(root, evidence)
            report['gui'] = gui(root, evidence)
        report['status'] = 'PASS'
        return 0
    except Exception as error:
        report['error_type'] = type(error).__name__  # No raw URL/diagnostic disclosure.
        return 1
    finally:
        (evidence / 'packaging-smoke.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
