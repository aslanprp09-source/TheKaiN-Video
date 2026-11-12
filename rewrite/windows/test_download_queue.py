"""Owned WAV fixtures and a loopback server: no live media providers."""
import errno
import hashlib
import http.server
import io
import json
import os
import sqlite3
import shutil
import tempfile
import threading
import time
import unittest
import wave
from collections import Counter
from dataclasses import replace
from pathlib import Path
from unittest.mock import patch

from domain import DownloadError, ErrorCode, FormatVariant
from download_queue import DownloadQueue
from engine import Options
from job_repository import JobRepository
from jobs import JobStatus as S, ResumeCapability as R
from media_tools import MediaVerifier, Verification
from process_runner import Control, TaskFailure, TaskStopped, run_process
from storage import StoragePublisher, safe_filename


def owned_wav():
    out = io.BytesIO()
    with wave.open(out, 'wb') as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(8000)
        w.writeframes(b'\0\0' * 160000)
    return out.getvalue()


MEDIA = owned_wav()
VARIANT = FormatVariant('audio', 'wav', 'wav', audio_codec='pcm_s16le', has_audio=True,
                        byte_size_known=len(MEDIA))


class FixtureHandler(http.server.BaseHTTPRequestHandler):
    counts = Counter()
    ranges = []
    guard = threading.Lock()

    def log_message(self, *args):
        pass

    def do_GET(self):
        path = self.path.split('?')[0]
        with self.guard:
            self.counts[path] += 1
            count = self.counts[path]
            self.ranges.append((path, self.headers.get('Range')))
        if path.startswith('/status/'):
            code = int(path.rsplit('/', 1)[-1])
            if count == 1 or code in (403, 404):
                self.send_response(code)
                self.send_header('Content-Length', '0')
                self.send_header('Retry-After', '0')
                self.end_headers()
                return
        content = b'not a media container' if path == '/corrupt' else MEDIA
        offset, end, status = 0, len(content) - 1, 200
        requested = self.headers.get('Range')
        if requested and path not in ('/no-range', '/truncated', '/corrupt'):
            start, stop = requested.removeprefix('bytes=').split('-')
            offset = int(start)
            end = int(stop) if stop else end
            status = 206
        self.send_response(status)
        self.send_header('Content-Length', str(end - offset + 1))
        self.send_header('ETag', '"fixture-v1"')
        if status == 206:
            self.send_header('Content-Range', f'bytes {offset}-{end}/{len(content)}')
        self.end_headers()
        if path == '/truncated':
            content = content[:200]
        try:
            for pos in range(offset, min(end + 1, len(content)), 4096):
                self.wfile.write(content[pos:min(pos + 4096, end + 1)])
                self.wfile.flush()
                if path == '/slow':
                    time.sleep(.005)
        except (BrokenPipeError, ConnectionResetError):
            pass


class FakeVerifier:
    def require_tools(self):
        pass

    def verify(self, path, variant, control):
        control.check()
        data = Path(path).read_bytes()
        if data != MEDIA:
            raise TaskFailure(DownloadError(ErrorCode.VERIFY_FAILED))
        return Verification(len(data), hashlib.sha256(data).hexdigest())


class QueueTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        missing = [name for name in ('ffmpeg', 'ffprobe') if shutil.which(name) is None]
        if missing:
            message = 'Missing verification tools on PATH: ' + ', '.join(missing)
            if os.environ.get('GITHUB_ACTIONS') == 'true':
                print('::error title=M2 integration prerequisites::' + message, flush=True)
            raise RuntimeError(message)
        cls.server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), FixtureHandler)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()
        cls.url = f'http://127.0.0.1:{cls.server.server_port}'

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join()

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.queues = []
        self.events = []
        self.changed = threading.Condition()
        FixtureHandler.counts.clear()
        FixtureHandler.ranges.clear()

    def fail(self, msg=None):
        if os.environ.get('GITHUB_ACTIONS') == 'true':
            message = str(msg).replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')
            print('::error title=M2 queue assertion::' + message, flush=True)
        super().fail(msg)

    def tearDown(self):
        for q in reversed(self.queues):
            q.close()
        self.temp.cleanup()

    def callback(self, job):
        with self.changed:
            self.events.append(job)
            self.changed.notify_all()

    def queue(self, start=True, **kwargs):
        q = DownloadQueue(self.root / 'data', callback=self.callback, backoff=.01, **kwargs)
        self.queues.append(q)
        if start:
            q.start()
        return q

    def add(self, q, path='/good', title='fixture', variant=VARIANT):
        return q.enqueue(Options(self.url + path, self.root / 'out', variant), title, backend='http')

    def wait(self, q, job, state, timeout=10):
        deadline = time.monotonic() + timeout
        with self.changed:
            while True:
                current = q.repository.get(job.id)
                if current.state == state:
                    return current
                if current.state in (S.COMPLETED, S.FAILED, S.CANCELED) and state not in (S.FAILED, S.CANCELED):
                    self.fail(f'Unexpected {current.state}: {current.error}')
                left = deadline - time.monotonic()
                if left <= 0:
                    self.fail(f'Timed out at {current.state}, expected {state}')
                self.changed.wait(min(left, .05))

    def test_success_real_ffprobe_decode_and_verified_publication(self):
        q = self.queue()
        job = self.add(q)
        result = self.wait(q, job, S.COMPLETED)
        self.assertEqual(result.output.read_bytes(), MEDIA)
        self.assertEqual(result.verified_bytes, len(MEDIA))
        self.assertEqual(result.percent, 100)
        states = [j.state for j in self.events if j.id == job.id]
        self.assertLess(states.index(S.VERIFYING), states.index(S.PUBLISHING))
        self.assertLess(states.index(S.PUBLISHING), states.index(S.COMPLETED))
        for snapshot in self.events:
            if snapshot.state != S.COMPLETED and snapshot.percent is not None:
                self.assertLess(snapshot.percent, 100)
        self.assertNotIn('?token=', repr(result))

    def test_corrupt_output_fails_after_full_transfer_without_final_file(self):
        q = self.queue()
        job = self.add(q, '/corrupt', variant=replace(VARIANT, byte_size_known=None))
        result = self.wait(q, job, S.FAILED)
        self.assertEqual(result.error.code, ErrorCode.VERIFY_FAILED)
        self.assertTrue(any(j.state == S.VERIFYING and j.progress_bytes == j.total_bytes for j in self.events))
        self.assertFalse((self.root / 'out').exists())
        self.assertNotEqual(result.percent, 100)

    def test_truncated_transfer_never_publishes(self):
        q = self.queue(max_attempts=1)
        result = self.wait(q, self.add(q, '/truncated'), S.FAILED)
        self.assertEqual(result.error.code, ErrorCode.NETWORK_LOST)
        self.assertFalse((self.root / 'out').exists())

    def test_429_and_server_failures_retry_with_backoff(self):
        for code in (429, 500, 503):
            with self.subTest(code=code):
                q = self.queue(verifier=FakeVerifier()) if not self.queues else self.queues[0]
                result = self.wait(q, self.add(q, f'/status/{code}'), S.COMPLETED)
                self.assertEqual(result.attempt, 2)
                waits = [j for j in self.events if j.id == result.id and j.state == S.RETRY_WAIT]
                self.assertTrue(waits)
                self.assertGreater(waits[0].next_retry, 0)
                self.assertIn(waits[0].error.code, (ErrorCode.RATE_LIMITED, ErrorCode.REMOTE_SERVER))

    def test_403_and_404_fail_without_retry(self):
        q = self.queue(verifier=FakeVerifier())
        for code, expected in ((403, ErrorCode.REMOTE_FORBIDDEN), (404, ErrorCode.REMOTE_MISSING)):
            result = self.wait(q, self.add(q, f'/status/{code}'), S.FAILED)
            self.assertEqual(result.error.code, expected)
            self.assertEqual(result.attempt, 1)

    def test_no_range_does_not_enable_pause_or_resume(self):
        q = self.queue(verifier=FakeVerifier())
        result = self.wait(q, self.add(q, '/no-range'), S.COMPLETED)
        self.assertEqual(result.resume, R.NO)
        self.assertFalse(q.pause(result.id))
        with self.assertRaises(TaskFailure):
            q.resume(result.id)

    def test_pause_resume_validates_range_and_preserves_partial(self):
        q = self.queue(verifier=FakeVerifier())
        job = self.add(q, '/slow')
        deadline = time.monotonic() + 5
        with self.changed:
            while q.repository.get(job.id).progress_bytes == 0 and time.monotonic() < deadline:
                self.changed.wait(.02)
        self.assertTrue(q.pause(job.id))
        paused = self.wait(q, job, S.PAUSED)
        size = (paused.stage / 'media.part').stat().st_size
        self.assertGreater(size, 0)
        q.resume(job.id)
        result = self.wait(q, job, S.COMPLETED)
        self.assertEqual(result.output.read_bytes(), MEDIA)
        self.assertIn(('/slow', f'bytes={size}-'), FixtureHandler.ranges)

    def test_resume_rejects_ignored_range_without_appending(self):
        q = self.queue(start=False, verifier=FakeVerifier())
        job = self.add(q, '/no-range')
        job.stage.mkdir(parents=True)
        (job.stage / 'media.part').write_bytes(MEDIA[:100])
        (job.stage / 'resume.json').write_text(json.dumps({'etag': '"fixture-v1"', 'total': len(MEDIA),
            'url_hash': hashlib.sha256(job.url.encode()).hexdigest()}))
        q.repository.update(job.id, S.DOWNLOADING)
        q.start()
        self.wait(q, job, S.PAUSED)
        q.resume(job.id)
        result = self.wait(q, job, S.FAILED)
        self.assertEqual(result.error.code, ErrorCode.RESUME_UNSUPPORTED)
        self.assertEqual((job.stage / 'media.part').stat().st_size, 100)

    def test_disk_full_is_typed_and_does_not_complete(self):
        q = self.queue(verifier=FakeVerifier())
        with patch('storage.os.link', side_effect=OSError(errno.ENOSPC, 'fixture disk full')):
            result = self.wait(q, self.add(q), S.FAILED)
        self.assertEqual(result.error.code, ErrorCode.NO_DISK_SPACE)
        self.assertNotEqual(result.percent, 100)

    def test_missing_ffmpeg_stops_before_network(self):
        q = self.queue(verifier=MediaVerifier(ffmpeg='thekain-no-such-ffmpeg'))
        result = self.wait(q, self.add(q), S.FAILED)
        self.assertEqual(result.error.code, ErrorCode.TOOL_MISSING)
        self.assertEqual(FixtureHandler.counts['/good'], 0)

    def test_duplicate_filename_preserves_existing_and_completed_files(self):
        q = self.queue(verifier=FakeVerifier())
        out = self.root / 'out'
        out.mkdir()
        (out / 'fixture.wav').write_bytes(b'user-owned existing file')
        first = self.wait(q, self.add(q), S.COMPLETED)
        second = self.wait(q, self.add(q), S.COMPLETED)
        self.assertNotEqual(first.output, second.output)
        self.assertEqual((out / 'fixture.wav').read_bytes(), b'user-owned existing file')
        with self.assertRaises(ValueError):
            q.retry(first.id)

    def test_cross_volume_copy_is_verified_and_never_clobbers(self):
        q = self.queue(verifier=FakeVerifier())
        link = os.link
        def cross_volume(source, target):
            if 'staging' in Path(source).parts:
                raise OSError(errno.EXDEV, 'fixture cross-volume')
            return link(source, target)
        with patch('storage.os.link', side_effect=cross_volume):
            result = self.wait(q, self.add(q), S.COMPLETED)
        self.assertEqual(result.output.read_bytes(), MEDIA)
        self.assertFalse(list(result.output.parent.glob('*.publishing')))

    def test_cancellation_retains_partial_but_never_publishes(self):
        q = self.queue(verifier=FakeVerifier())
        job = self.add(q, '/slow')
        deadline = time.monotonic() + 5
        with self.changed:
            while q.repository.get(job.id).progress_bytes == 0 and time.monotonic() < deadline:
                self.changed.wait(.02)
        self.assertTrue(q.cancel(job.id))
        self.wait(q, job, S.CANCELED)
        self.assertTrue((job.stage / 'media.part').exists())
        self.assertFalse((self.root / 'out').exists())

    def test_retry_budget_is_bounded(self):
        calls = []
        class AlwaysRateLimited:
            def transfer(inner, job, *args):
                calls.append(job.attempt)
                raise TaskFailure(DownloadError(ErrorCode.RATE_LIMITED, True))
        q = self.queue(verifier=FakeVerifier(), transport_factory=lambda job: AlwaysRateLimited())
        result = self.wait(q, self.add(q), S.FAILED)
        self.assertEqual(result.error.code, ErrorCode.RATE_LIMITED)
        self.assertEqual(calls, [1, 2, 3])

    def test_retry_after_is_honored_and_persisted(self):
        clock = [100.0]
        class LimitedOnce:
            def transfer(inner, job, *args):
                if job.attempt == 1:
                    failure = TaskFailure(DownloadError(ErrorCode.RATE_LIMITED, True))
                    failure.retry_after = 17
                    raise failure
                path = job.stage / 'media.wav'
                path.write_bytes(MEDIA)
                return path
        q = self.queue(verifier=FakeVerifier(), clock=lambda: clock[0], transport_factory=lambda job: LimitedOnce())
        job = self.add(q)
        waiting = self.wait(q, job, S.RETRY_WAIT)
        self.assertEqual(waiting.next_retry, 117)
        clock[0] = 117
        self.wait(q, job, S.COMPLETED)

    def test_resume_rejects_changed_etag_without_appending(self):
        q = self.queue(start=False, verifier=FakeVerifier())
        job = self.add(q)
        job.stage.mkdir(parents=True)
        (job.stage / 'media.part').write_bytes(MEDIA[:100])
        (job.stage / 'resume.json').write_text(json.dumps({'etag': '"outdated"', 'total': len(MEDIA),
            'url_hash': hashlib.sha256(job.url.encode()).hexdigest()}))
        q.repository.update(job.id, S.DOWNLOADING)
        q.start()
        self.wait(q, job, S.PAUSED)
        q.resume(job.id)
        result = self.wait(q, job, S.FAILED)
        self.assertEqual(result.error.code, ErrorCode.RESUME_UNSUPPORTED)
        self.assertEqual((job.stage / 'media.part').stat().st_size, 100)

    def test_restart_verifying_reuses_artifact_without_a_second_download(self):
        q = self.queue(start=False, verifier=FakeVerifier())
        job = self.add(q)
        job.stage.mkdir(parents=True)
        artifact = job.stage / 'media.wav'
        artifact.write_bytes(MEDIA)
        q.repository.update(job.id, S.DOWNLOADING)
        q.repository.update(job.id, S.VERIFYING, artifact=artifact)
        q.start()
        self.wait(q, job, S.COMPLETED)
        self.assertEqual(FixtureHandler.counts['/good'], 0)

    def test_restart_partial_requires_explicit_resume_and_revalidation(self):
        q = self.queue(start=False, verifier=FakeVerifier())
        job = self.add(q)
        job.stage.mkdir(parents=True)
        (job.stage / 'media.part').write_bytes(MEDIA[:100])
        (job.stage / 'resume.json').write_text(json.dumps({'etag': '"fixture-v1"', 'total': len(MEDIA),
            'url_hash': hashlib.sha256(job.url.encode()).hexdigest()}))
        q.repository.update(job.id, S.DOWNLOADING, resume=R.YES)
        q.start()
        paused = self.wait(q, job, S.PAUSED)
        self.assertEqual(paused.resume, R.UNKNOWN)
        q.resume(job.id)
        self.assertEqual(self.wait(q, job, S.COMPLETED).output.read_bytes(), MEDIA)

    def test_resume_fully_received_partial_verifies_without_appending(self):
        q = self.queue(start=False, verifier=FakeVerifier())
        job = self.add(q)
        job.stage.mkdir(parents=True)
        (job.stage / 'media.part').write_bytes(MEDIA)
        (job.stage / 'resume.json').write_text(json.dumps({'etag': '"fixture-v1"', 'total': len(MEDIA),
            'url_hash': hashlib.sha256(job.url.encode()).hexdigest()}))
        q.repository.update(job.id, S.DOWNLOADING)
        q.start()
        self.wait(q, job, S.PAUSED)
        q.resume(job.id)
        result = self.wait(q, job, S.COMPLETED)
        self.assertEqual(result.output.read_bytes(), MEDIA)
        self.assertEqual(FixtureHandler.counts['/good'], 0)

    def test_restart_unknown_backend_interruption_is_not_fake_pause(self):
        q = self.queue(start=False, verifier=FakeVerifier())
        job = q.enqueue(Options(self.url + '/good', self.root / 'out', VARIANT))
        job.stage.mkdir(parents=True)
        (job.stage / 'media.part').write_bytes(b'partial')
        q.repository.update(job.id, S.DOWNLOADING)
        q.start()
        failed = self.wait(q, job, S.FAILED)
        self.assertEqual(failed.error.code, ErrorCode.INTERRUPTED)
        self.assertFalse(q.pause(job.id))
        retry = q.retry(job.id)
        self.assertEqual(retry.attempt, 2)
        self.assertNotEqual(retry.stage, job.stage)
        q.cancel(retry.id)

    def test_restart_after_link_before_sql_commit_does_not_duplicate(self):
        q = self.queue(start=False, verifier=FakeVerifier())
        job = self.add(q)
        job.stage.mkdir(parents=True)
        artifact = job.stage / 'media.wav'
        artifact.write_bytes(MEDIA)
        q.repository.update(job.id, S.DOWNLOADING)
        q.repository.update(job.id, S.VERIFYING, artifact=artifact)
        out = self.root / 'out' / 'fixture.wav'
        out.parent.mkdir()
        q.repository.update(job.id, S.PUBLISHING, output=out, verified_bytes=len(MEDIA), digest=hashlib.sha256(MEDIA).hexdigest())
        os.link(artifact, out)
        artifact.unlink()  # Recovery can use verified published bytes alone.
        q.start()
        completed = self.wait(q, job, S.COMPLETED)
        self.assertEqual(completed.output, out)
        self.assertEqual(list(out.parent.iterdir()), [out])
        self.assertEqual(FixtureHandler.counts['/good'], 0)

    def test_two_concurrent_workers_and_max_three_configuration(self):
        release, both = threading.Event(), threading.Event()
        guard = threading.Lock()
        counters = {'active': 0, 'peak': 0}
        class Blocking:
            def transfer(inner, job, control, progress, capability, phase):
                with guard:
                    counters['active'] += 1
                    counters['peak'] = max(counters['peak'], counters['active'])
                    if counters['active'] == 2:
                        both.set()
                try:
                    while not release.wait(.01):
                        control.check()
                    path = job.stage / 'media.wav'
                    path.write_bytes(MEDIA)
                    return path
                finally:
                    with guard:
                        counters['active'] -= 1
        q = self.queue(verifier=FakeVerifier(), transport_factory=lambda job: Blocking())
        jobs = [self.add(q, title=str(i)) for i in range(4)]
        self.assertTrue(both.wait(5))
        self.assertEqual(counters['peak'], 2)
        release.set()
        for job in jobs:
            self.wait(q, job, S.COMPLETED)
        self.assertEqual(counters['peak'], 2)
        with self.assertRaises(ValueError):
            DownloadQueue(self.root / 'other', concurrency=4)

    def test_one_process_owns_queue_and_completed_state_is_immutable(self):
        q = self.queue(verifier=FakeVerifier())
        other = self.queue(start=False, verifier=FakeVerifier())
        with self.assertRaises(RuntimeError):
            other.start()
        result = self.wait(q, self.add(q), S.COMPLETED)
        with self.assertRaises(ValueError):
            q.repository.update(result.id, S.DOWNLOADING)


class RepositoryAndToolsTest(unittest.TestCase):
    def test_migration_reopen_and_future_schema_preserved(self):
        with tempfile.TemporaryDirectory() as d:
            repo = JobRepository(d)
            job = repo.add('https://example.invalid/v?token=test', 'title', VARIANT, Path(d) / 'out')
            self.assertEqual(JobRepository(d).get(job.id), job)
            with self.assertRaises(ValueError):
                repo.update(job.id, S.COMPLETED)
            with repo.connect() as con:
                con.execute('PRAGMA user_version=99')
            with self.assertRaises(RuntimeError):
                JobRepository(d)
            with repo.connect() as con:
                self.assertEqual(con.execute('PRAGMA user_version').fetchone()[0], 99)

    def test_failed_queue_owner_can_acquire_after_original_closes(self):
        from download_queue import InstanceLock
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'queue.lock'
            first = InstanceLock(path)
            try:
                with self.assertRaises(RuntimeError):
                    InstanceLock(path)
            finally:
                first.close()
            second = InstanceLock(path)
            second.close()
            path.unlink()  # No failed acquisition may retain a Windows file handle.

    def test_sanitizes_windows_names(self):
        for name in ('../CON', 'NUL', 'LPT1.wav', 'a/b\\c:<>?*', '', '...'):
            with self.subTest(name=name):
                result = safe_filename(name)
                self.assertFalse(any(c in result for c in '/\\:<>?*'))
                self.assertTrue(result)
                self.assertNotIn(result.upper(), ('NUL', 'CON'))

    def test_real_verifier_rejects_truncation_and_wrong_tracks(self):
        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / 'media.wav'
            path.write_bytes(MEDIA[:100])
            with self.assertRaises(TaskFailure):
                MediaVerifier().verify(path, VARIANT, Control())
            path.write_bytes(MEDIA)
            with self.assertRaises(TaskFailure):
                MediaVerifier().verify(path, replace(VARIANT, video_codec='avc1'), Control())
            self.assertEqual(MediaVerifier().verify(path, VARIANT, Control()).size, len(MEDIA))

    def test_process_output_is_bounded_and_cancel_reaps_child(self):
        import sys
        with self.assertRaises(ValueError):
            run_process([sys.executable, '-c', 'print("x" * 10000)'], Control(), limit=100)
        control = Control()
        timer = threading.Timer(.1, control.stop)
        timer.start()
        try:
            with self.assertRaises(TaskStopped):
                run_process([sys.executable, '-c', 'import time; time.sleep(20)'], control)
        finally:
            timer.cancel()
