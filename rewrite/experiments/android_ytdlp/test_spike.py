"""Owned HTTPS fixtures only. No live video sites, keys, media transfer or Android claims."""
import copy
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import ssl
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

from rewrite.experiments.android_ytdlp.spike import (
    SpikeError, analyze_fixture, bounded_process, decode, normalize, origin)


def kernel_record_exited(state):
    try:
        value=state.read_text()
    except (FileNotFoundError,ProcessLookupError):
        # procfs can return ENOENT at open or ESRCH after open when the task exits.
        return True
    return value.rsplit(')',1)[1].split()[0] == 'Z'


class ProtocolTests(unittest.TestCase):
    def setUp(self):
        self.url = 'https://127.0.0.1:443/video.mp4?token=fixture-secret'
        self.data = {'formats': [{'format_id': 'owned-360', 'url': self.url,
            'protocol': 'https', 'ext': 'mp4', 'vcodec': 'avc1.42E01E', 'acodec': 'mp4a.40.2'}]}

    def test_unknown_size_is_unknown_and_download_stays_off(self):
        result = normalize(self.data, origin(self.url))
        self.assertIsNone(result.candidates[0].byte_size)
        self.assertFalse(result.download_enabled)
        self.assertTrue(result.candidates[0].transfer_eligible)
        self.assertNotIn('fixture-secret', repr(result))

    def test_no_audio_inference_or_unsupported_streams(self):
        for change in ({'acodec': 'none'},
                {'protocol': 'm3u8_native'}, {'protocol': 'http_dash_segments'},
                {'ext': 'webm'}, {'has_drm': True}, {'manifest_url': self.url},
                {'http_headers': {'Cookie': 'fixture-secret'}}):
            with self.subTest(change=change):
                data = copy.deepcopy(self.data); data['formats'][0].update(change)
                with self.assertRaises(SpikeError) as caught: normalize(data, origin(self.url))
                self.assertEqual(caught.exception.code, 'UNSUPPORTED_MEDIA')

    def test_unknown_tracks_are_reported_but_never_eligible(self):
        for field in ('acodec', 'vcodec'):
            for value in (None, '', 'unknown'):
                data = copy.deepcopy(self.data); data['formats'][0][field] = value
                result = normalize(data, origin(self.url))
                self.assertFalse(result.candidates[0].transfer_eligible)
                self.assertEqual(result.candidates[0].blocked_reason, 'TRACKS_UNKNOWN')
                self.assertIsNone(getattr(result.candidates[0], 'audio_codec' if field == 'acodec' else 'video_codec'))
                self.assertFalse(result.download_enabled)

    def test_reject_playlist_live_drm_and_oversized_format_list(self):
        for change in ({'_type': 'playlist'}, {'entries': []}, {'is_live': True},
                {'has_drm': True}, {'formats': self.data['formats'] * 129}):
            data = {**self.data, **change}
            with self.assertRaises(SpikeError): normalize(data, origin(self.url))

    def test_malformed_sizes_duplicate_ids_and_json(self):
        for size in (True, -1, 0, 1.5, '99', 2**63):
            data = copy.deepcopy(self.data); data['formats'][0]['filesize'] = size
            with self.assertRaises(SpikeError): normalize(data, origin(self.url))
        self.data['formats'] *= 2
        with self.assertRaises(SpikeError): normalize(self.data, origin(self.url))
        for raw in (b'{"a":1,"a":2}', b'{"a":NaN}', b'\xff', b'{'):
            with self.assertRaises(SpikeError): decode(raw)

    def test_urls_are_fixture_only_and_do_not_leak(self):
        for url in ('http://127.0.0.1:443/x', 'https://example.com/x',
                'https://localhost:443/x', 'file:///tmp/x',
                'https://u:p@127.0.0.1:443/x', 'https://127.0.0.1:443/x#secret',
                'https://127.0.0.1:443/x\nsecret'):
            with self.assertRaises(SpikeError) as caught: origin(url)
            self.assertEqual(str(caught.exception), 'INVALID_URL')
        self.data['formats'][0]['url'] = 'https://127.0.0.1:444/secret'
        with self.assertRaises(SpikeError) as caught: normalize(self.data, origin(self.url))
        self.assertEqual(str(caught.exception), 'OUT_OF_SCOPE_URL')


@unittest.skipUnless(sys.platform.startswith('linux'),'Host reference lifecycle experiment requires Linux')
class LifecycleTests(unittest.TestCase):
    def command(self, code): return [sys.executable, '-I', '-c', code]

    def test_kernel_esrch_confirms_death_but_denied_or_empty_read_does_not(self):
        state=Path('/proc/123456/stat')
        for error in (FileNotFoundError(),ProcessLookupError()):
            with patch.object(Path,'read_text',side_effect=error):self.assertTrue(kernel_record_exited(state))
        for value in ('', '123456 (owned) R 0 0'):
            with patch.object(Path,'read_text',return_value=value):
                if not value:
                    with self.assertRaises(IndexError):kernel_record_exited(state)
                else:self.assertFalse(kernel_record_exited(state))
        with patch.object(Path,'read_text',side_effect=PermissionError()):
            with self.assertRaises(PermissionError):kernel_record_exited(state)

    def test_output_and_stderr_flood_are_bounded_without_secret_in_error(self):
        for channel in ('stdout', 'stderr'):
            with self.assertRaises(SpikeError) as caught:
                bounded_process(self.command(f'import sys; sys.{channel}.write("secret"*10000)'), max_output=1024)
            self.assertEqual(str(caught.exception), 'OUTPUT_LIMIT')

    def test_timeout_and_cancel_reap_child_session(self):
        for canceled in (False, True):
            with tempfile.TemporaryDirectory() as temp:
                pid_file = Path(temp) / 'child-pid'
                child = 'import signal,time; signal.signal(signal.SIGTERM,signal.SIG_IGN); time.sleep(60)'
                code = ('import subprocess,sys,time,pathlib; '
                    f'p=subprocess.Popen([sys.executable,"-I","-c",{child!r}]); '
                    f'pathlib.Path({str(pid_file)!r}).write_text(str(p.pid)); time.sleep(60)')
                event = threading.Event()
                def cancel_when_ready():
                    for _ in range(100):
                        if pid_file.exists(): break
                        time.sleep(0.02)
                    event.set()
                notifier = threading.Thread(target=cancel_when_ready, daemon=True)
                if canceled: notifier.start()
                started = time.monotonic()
                try:
                    with self.assertRaises(SpikeError) as caught:
                        bounded_process(self.command(code), cancel=event, timeout=6 if canceled else 2)
                    self.assertEqual(caught.exception.code, 'CANCELED' if canceled else 'TIMEOUT')
                    self.assertLess(time.monotonic() - started, 5)
                    self.assertTrue(pid_file.exists())
                    child_pid = int(pid_file.read_text())
                    # A killed grandchild may briefly remain a zombie until init reaps it.
                    state = Path(f'/proc/{child_pid}/stat')
                    def exited():
                        return kernel_record_exited(state)
                    for _ in range(50):
                        if exited(): break
                        time.sleep(0.01)
                    self.assertTrue(exited())
                finally:
                    if canceled: notifier.join(timeout=3)

    def test_pre_cancel_and_invalid_deadline_do_not_launch(self):
        event = threading.Event(); event.set()
        with patch('subprocess.Popen') as launch:
            with self.assertRaises(SpikeError): bounded_process(['unused'], cancel=event)
            for timeout in (float('nan'), float('inf'), -1, 0, 100):
                with self.assertRaises(SpikeError): bounded_process(['unused'], timeout=timeout)
            launch.assert_not_called()

    def test_worker_guard_refuses_external_execution_and_other_sockets(self):
        worker_dir = Path(__file__).parent.resolve()
        with tempfile.TemporaryDirectory() as temp:
            marker = Path(temp) / 'must-not-exist'
            child_code = f'open({str(marker)!r}, "w").write("bad")'
            for operation in (
                f'subprocess.run([sys.executable,"-c",{child_code!r}])',
                'socket.create_connection(("127.0.0.1",1))'):
                code = (f'import sys,subprocess,socket,json; sys.path.insert(0,{str(worker_dir)!r}); '
                    'from worker import install_guard; from spike import SpikeError; '
                    'install_guard(("https","127.0.0.1",443))\n'
                    f'try: {operation}\nexcept SpikeError as e: print(json.dumps({{"error":e.code}}))')
                status, raw = bounded_process(self.command(code))
                self.assertEqual(status, 0)
                self.assertIn(decode(raw)['error'], ('UNSUPPORTED_MEDIA', 'OUT_OF_SCOPE_URL'))
                self.assertFalse(marker.exists())


@unittest.skipUnless(sys.platform.startswith('linux'),'Host reference TLS/worker experiment requires Linux')
class OwnedHttpsTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        # Fail, rather than skip, if CI omitted the pinned real extractor.
        from yt_dlp.version import __version__
        if __version__ != '2026.10.26': raise RuntimeError('PINNED_YTDLP_REQUIRED')
        cls.temp = tempfile.TemporaryDirectory(); root = Path(cls.temp.name)
        cls.ca = root / 'owned-ca.pem'; key = root / 'owned-key.pem'
        subprocess.run(['openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes',
            '-keyout', str(key), '-out', str(cls.ca), '-days', '1', '-subj', '/CN=127.0.0.1',
            '-addext', 'subjectAltName=IP:127.0.0.1'], check=True,
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        cls.wrong_ca = root / 'unrelated-ca.pem'
        subprocess.run(['openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes',
            '-keyout', str(root / 'unrelated-key.pem'), '-out', str(cls.wrong_ca),
            '-days', '1', '-subj', '/CN=Unrelated fixture CA'], check=True,
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        cls.requests = []
        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *_): pass
            def do_HEAD(self): self.serve(True)
            def do_GET(self): self.serve(False)
            def serve(self, head):
                cls.requests.append((self.command, self.path))
                path = self.path.split('?')[0]
                if path in ('/redirect', '/escape', '/loop'):
                    self.send_response(302)
                    self.send_header('Location', {'/redirect': '/video', '/escape':
                        'https://127.0.0.1:1/never-contact?token=fixture-secret', '/loop': '/loop'}[path])
                    self.end_headers(); return
                if path in ('/403', '/404', '/429'):
                    self.send_response(int(path[1:])); self.end_headers(); return
                if path == '/slow': time.sleep(2)
                source = '<source src="/media.mp4?token=fixture-secret" type=\'video/mp4; codecs="avc1.42E01E, mp4a.40.2"\'>'
                body = ('<html><title>Owned fixture</title><video controls>' + source + '</video></html>').encode()
                if path == '/playlist': body += b'<video src="/second.mp4"></video>'
                if path in ('/large', '/unbounded'): body = b'x' * (600 * 1024)
                if path == '/unknown-codecs': body = body.replace(b'; codecs="avc1.42E01E, mp4a.40.2"', b'')
                self.send_response(200)
                if path == '/compressed': self.send_header('Content-Encoding', 'gzip')
                self.send_header('Content-Type', 'video/mp4' if path.endswith('.mp4') else 'text/html')
                if path != '/unbounded': self.send_header('Content-Length', str(len(body)))
                self.end_headers()
                if not head:
                    try: self.wfile.write(body)
                    except (BrokenPipeError, ConnectionResetError, ssl.SSLError): pass
        cls.server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        cls.server.daemon_threads = True
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER); context.load_cert_chain(cls.ca, key)
        cls.server.socket = context.wrap_socket(cls.server.socket, server_side=True)
        cls.base = f'https://127.0.0.1:{cls.server.server_port}'
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True); cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown(); cls.server.server_close(); cls.thread.join(); cls.temp.cleanup()

    def analyze(self, path, **kwargs): return analyze_fixture(self.base + path, self.ca, **kwargs)

    def assert_code(self, path, expected, **kwargs):
        with self.assertRaises(SpikeError) as caught: self.analyze(path, **kwargs)
        self.assertEqual(caught.exception.code, expected)
        self.assertNotIn('fixture-secret', str(caught.exception))

    def test_actual_extractor_formats_and_redirect_without_media_transfer(self):
        self.requests.clear()
        for path in ('/video', '/redirect'):
            result = self.analyze(path)
            self.assertEqual(len(result.candidates), 1)
            self.assertEqual(result.candidates[0].audio_codec, 'mp4a.40.2')
            # Real upstream HTML5 output leaves vcodec unknown despite the MIME declaration.
            self.assertIsNone(result.candidates[0].video_codec)
            self.assertFalse(result.candidates[0].transfer_eligible)
            self.assertEqual(result.candidates[0].blocked_reason, 'TRACKS_UNKNOWN')
            self.assertIsNone(result.candidates[0].byte_size)
            self.assertFalse(result.download_enabled)
            self.assertNotIn('fixture-secret', repr(result))
        self.assertFalse(any(method == 'GET' and '.mp4' in path for method, path in self.requests))

    def test_response_limit_with_and_without_content_length(self):
        for path in ('/large', '/unbounded'): self.assert_code(path, 'RESPONSE_LIMIT')

    def test_tls_verification_and_compressed_body_refusal(self):
        with self.assertRaises(SpikeError) as caught:
            analyze_fixture(self.base + '/video', self.wrong_ca)
        self.assertEqual(caught.exception.code, 'TLS_ERROR')
        self.assert_code('/compressed', 'UNSUPPORTED_MEDIA')

    def test_http_errors_and_cross_origin_redirect_fail_closed(self):
        for path, code in (('/403', 'HTTP_FORBIDDEN'), ('/404', 'REMOTE_MISSING'),
                ('/429', 'RATE_LIMITED'), ('/escape', 'OUT_OF_SCOPE_URL'), ('/loop', 'REQUEST_LIMIT')):
            self.assert_code(path, code)

    def test_real_worker_deadline_and_cancel(self):
        self.assert_code('/slow', 'TIMEOUT', timeout=0.3)
        event = threading.Event(); timer = threading.Timer(0.3, event.set); timer.start()
        try: self.assert_code('/slow', 'CANCELED', cancel=event)
        finally: timer.cancel()

    def test_playlist_and_unknown_tracks_are_not_downloadable(self):
        self.assert_code('/playlist', 'UNSUPPORTED_MEDIA')
        result = self.analyze('/unknown-codecs')
        self.assertFalse(result.download_enabled)
        self.assertTrue(all(not f.transfer_eligible for f in result.candidates))

    def test_environment_proxy_and_config_are_not_used(self):
        with patch.dict(os.environ, {'HTTPS_PROXY': 'http://127.0.0.1:1',
                'HTTP_PROXY': 'http://127.0.0.1:1', 'PYTHONPATH': '/does-not-exist'}):
            self.assertEqual(len(self.analyze('/video').candidates), 1)


if __name__ == '__main__': unittest.main()
