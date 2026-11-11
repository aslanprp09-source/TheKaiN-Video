"""Real yt-dlp -> separate tracks -> FFmpeg -> verifier -> publisher, loopback only."""
import functools
import http.server
import json
import subprocess
import sys
import tempfile
import threading
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from download_queue import DownloadQueue
from engine import Options
from extractor import parse_metadata
from jobs import JobStatus as S
from transports import YtDlpTransport


class Handler(http.server.SimpleHTTPRequestHandler):
    def log_message(self, *args):
        pass


def main():
    with tempfile.TemporaryDirectory() as directory:
        root = Path(directory)
        subprocess.run(['ffmpeg', '-nostdin', '-v', 'error', '-f', 'lavfi', '-i',
                        'color=c=blue:s=160x90:r=10', '-t', '1', '-an', '-c:v', 'mpeg4',
                        str(root / 'video.mp4')], check=True)
        subprocess.run(['ffmpeg', '-nostdin', '-v', 'error', '-f', 'lavfi', '-i',
                        'anullsrc=r=8000:cl=mono', '-t', '1', '-c:a', 'aac',
                        str(root / 'audio.m4a')], check=True)
        server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), functools.partial(Handler, directory=str(root)))
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        url = f'http://127.0.0.1:{server.server_port}/'
        data = {'id': 'owned-fixture', 'title': 'Owned M2 fixture', 'extractor': 'fixture',
                'webpage_url': url + 'watch', 'formats': [
                    {'format_id': 'video', 'url': url + 'video.mp4', 'ext': 'mp4',
                     'vcodec': 'mp4v.20.9', 'acodec': 'none', 'height': 90, 'width': 160,
                     'filesize': (root / 'video.mp4').stat().st_size},
                    {'format_id': 'audio', 'url': url + 'audio.m4a', 'ext': 'm4a',
                     'vcodec': 'none', 'acodec': 'mp4a.40.2', 'abr': 32,
                     'filesize': (root / 'audio.m4a').stat().st_size}]}
        info = root / 'fixture.json'
        info.write_text(json.dumps(data))
        variant = parse_metadata(data, url + 'watch').media.formats[0]
        events, condition = [], threading.Condition()

        def record(job):
            with condition:
                events.append(job)
                condition.notify_all()

        queue = DownloadQueue(root / 'data', callback=record,
                              transport_factory=lambda job: YtDlpTransport(fixture_info=info))
        try:
            queue.start()
            job = queue.enqueue(Options(url + 'watch', root / 'out', variant), title=data['title'])
            deadline = time.monotonic() + 30
            with condition:
                while True:
                    result = queue.repository.get(job.id)
                    if result.state in (S.COMPLETED, S.FAILED):
                        break
                    if time.monotonic() >= deadline:
                        raise AssertionError('Pipeline timed out')
                    condition.wait(.05)
            assert result.state == S.COMPLETED, result.error
            assert result.output.is_file() and result.verified_bytes > 0
            assert result.percent == 100
            assert any(e.state == S.POSTPROCESSING for e in events)
            assert any(e.state == S.VERIFYING for e in events)
            assert all(e.percent != 100 for e in events if e.state != S.COMPLETED)
            print('PASS: owned video+audio -> yt-dlp -> FFmpeg -> FFprobe/full decode -> unique published file')
        finally:
            queue.close()
            server.shutdown()
            server.server_close()
            thread.join()


if __name__ == '__main__':
    main()
