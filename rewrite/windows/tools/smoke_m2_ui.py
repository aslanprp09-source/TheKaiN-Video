"""Real queue/Tk states using owned loopback WAV fixtures; screenshots optional."""
import argparse
import http.server
import subprocess
import sys
import tempfile
import threading
import time
from dataclasses import replace
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from app import App
from download_queue import DownloadQueue
from engine import Options
from jobs import JobStatus as S
from media_tools import MediaVerifier
from test_download_queue import FixtureHandler, VARIANT


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--screenshots', type=Path)
    args = parser.parse_args()
    entered, release = threading.Event(), threading.Event()

    class GateVerifier(MediaVerifier):
        def verify(self, path, variant, control):
            entered.set()
            while not release.wait(.01):
                control.check()
            return super().verify(path, variant, control)

    with tempfile.TemporaryDirectory() as directory:
        server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), FixtureHandler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        url = f'http://127.0.0.1:{server.server_port}'
        queue = DownloadQueue(Path(directory) / 'data', verifier=GateVerifier())
        app = App(download_queue=queue)

        def wait(job, state):
            deadline = time.monotonic() + 10
            while time.monotonic() < deadline:
                app.update()
                if job.id in app.jobs and app.jobs[job.id].state == state:
                    return app.jobs[job.id]
                time.sleep(.01)
            raise AssertionError(f'UI did not reach {state}')

        def capture(name):
            app.update()
            if args.screenshots:
                args.screenshots.mkdir(parents=True, exist_ok=True)
                subprocess.run(['import', '-window', str(app.winfo_id()),
                                str(args.screenshots / (name + '.png'))], check=True)

        try:
            first = queue.enqueue(Options(url + '/good', Path(directory) / 'out', VARIANT), 'Локальный тест: проверка', backend='http')
            snapshot = wait(first, S.VERIFYING)
            assert snapshot.percent == 99 and str(app.pause_btn['state']) == 'disabled'
            assert str(app.progressbar['mode']) == 'indeterminate'
            capture('queue-verifying')
            release.set()
            wait(first, S.COMPLETED)
            assert app.progress.get() == 100
            capture('queue-completed')
            second = queue.enqueue(Options(url + '/corrupt', Path(directory) / 'out', replace(VARIANT, byte_size_known=None)),
                                   'Локальный тест: повреждённый файл', backend='http')
            app.selected_job = second.id
            wait(second, S.FAILED)
            assert app.progress.get() < 100 and str(app.retry_btn['state']) == 'normal'
            capture('queue-failed')
            print('PASS: real queue/Tk VERIFYING (not 100), COMPLETED (100), FAILED (not 100)')
        finally:
            release.set()
            app.close()
            queue.close()
            server.shutdown()
            server.server_close()
            thread.join()


if __name__ == '__main__':
    main()
