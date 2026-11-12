"""Persistent bounded scheduler; owns the transfer -> verify -> publish pipeline."""
import errno
import os
import threading
import time
from pathlib import Path
from domain import DownloadError, ErrorCode
from extractor import validate_url
from job_repository import JobRepository
from jobs import JobStatus as S, ResumeCapability as R
from media_tools import MediaVerifier
from process_runner import Control, TaskFailure, TaskStopped
from storage import StoragePublisher
from transports import HttpTransport, YtDlpTransport


class InstanceLock:
    def __init__(self, path):
        self.file = Path(path).open('a+b')
        try:
            self.file.seek(0)
            if os.name == 'nt':
                import msvcrt
                msvcrt.locking(self.file.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl
                fcntl.flock(self.file, fcntl.LOCK_EX | fcntl.LOCK_NB)
            # Lock before touching the byte: Windows denies reading a region owned
            # by another process, and locking a byte beyond EOF is supported.
            if os.fstat(self.file.fileno()).st_size == 0:
                self.file.write(b'0')
                self.file.flush()
        except OSError:
            self.file.close()
            raise RuntimeError('Queue is already open in another process') from None

    def close(self):
        self.file.close()


def default_data_root():
    override = os.environ.get('THEKAIN_DATA_DIR')
    if override:
        return Path(override)
    if os.name == 'nt':
        return Path(os.environ.get('LOCALAPPDATA', Path.home() / 'AppData/Local')) / 'TheKaiNVideo'
    return Path(os.environ.get('XDG_DATA_HOME', Path.home() / '.local/share')) / 'thekain-video'


class DownloadQueue:
    def __init__(self, root, callback=lambda job: None, concurrency=2, verifier=None,
                 publisher=None, transport_factory=None, backoff=2, max_attempts=3, clock=time.time):
        if not 1 <= concurrency <= 3:
            raise ValueError('Concurrency must be between 1 and 3')
        self.repository = JobRepository(root)
        self.callback = callback
        self.concurrency = concurrency
        self.verifier = verifier or MediaVerifier()
        self.publisher = publisher or StoragePublisher()
        self.transport_factory = transport_factory or (lambda job: HttpTransport() if job.backend == 'http' else YtDlpTransport())
        self.backoff, self.max_attempts, self.clock = backoff, max_attempts, clock
        self.lock = threading.RLock()
        self.condition = threading.Condition(self.lock)
        self.active = {}
        self.stopping = False
        self.lease = None
        self.dispatcher = None

    def notify(self, job):
        # A presentation error cannot change a persisted transfer outcome.
        try:
            self.callback(job)
        except Exception:
            pass
        return job

    def start(self):
        with self.lock:
            if self.dispatcher:
                return
            self.lease = InstanceLock(self.repository.root / 'queue.lock')
            try:
                self.reconcile()
                self.dispatcher = threading.Thread(target=self.dispatch, daemon=True)
                self.dispatcher.start()
            except Exception:
                self.lease.close()
                self.lease = None
                raise

    def reconcile(self):
        for job in self.repository.list():
            if job.state in (S.DOWNLOADING, S.PAUSING, S.POSTPROCESSING, S.VERIFYING):
                partial = job.stage / 'media.part'
                paused = (job.backend == 'http' and job.state in (S.DOWNLOADING, S.PAUSING)
                          and (job.stage / 'resume.json').is_file() and partial.is_file() and partial.stat().st_size > 0)
                if paused:
                    if job.state == S.DOWNLOADING:
                        job = self.repository.update(job.id, S.PAUSING)
                    job = self.repository.update(job.id, S.PAUSED, resume=R.UNKNOWN)
                else:
                    old = job
                    job = self.repository.update(job.id, S.FAILED, error=DownloadError(ErrorCode.INTERRUPTED))
                    if old.state == S.VERIFYING and old.artifact:
                        job = self.repository.update(job.id, S.QUEUED, error=None)
            self.notify(job)

    def enqueue(self, options, title='video', backend='ytdlp'):
        from engine import command_for
        command_for(options)
        if backend not in ('http', 'ytdlp') or backend == 'http' and options.quality.requires_merge:
            raise ValueError('Direct HTTP requires one media file')
        with self.condition:
            if self.stopping:
                raise RuntimeError('Queue is shutting down')
            job = self.repository.add(validate_url(options.url), title, options.quality, options.folder, backend)
            self.notify(job)
            self.condition.notify_all()
            return job

    def retry(self, job_id):
        with self.condition:
            job = self.repository.get(job_id)
            if job.state != S.FAILED:
                raise ValueError('Only a failed job can begin a new attempt')
            old = job
            job = self.new_attempt(job)
            if old.output and old.verified_bytes and old.digest:
                job = self.repository.update(job.id, artifact=old.artifact or old.output,
                    output=old.output, verified_bytes=old.verified_bytes, digest=old.digest)
            elif old.error and old.error.code == ErrorCode.INTERRUPTED and old.artifact:
                job = self.repository.update(job.id, artifact=old.artifact)
            self.condition.notify_all()
            return self.notify(job)

    def new_attempt(self, job):
        attempt = job.attempt + 1
        return self.repository.update(job.id, S.QUEUED, attempt=attempt,
            stage=self.repository.root / 'staging' / job.id / str(attempt),
            progress_bytes=0, total_bytes=None, resume=R.UNKNOWN, error=None,
            artifact=None, output=None, digest=None, verified_bytes=None, next_retry=0)

    def pause(self, job_id):
        with self.condition:
            job = self.repository.get(job_id)
            if job.state != S.DOWNLOADING or job.resume != R.YES or job_id not in self.active:
                return False
            self.notify(self.repository.update(job_id, S.PAUSING))
            self.active[job_id][0].stop('paused')
            return True

    def resume(self, job_id):
        with self.condition:
            job = self.repository.get(job_id)
            if job.state != S.PAUSED or job.backend != 'http':
                raise TaskFailure(DownloadError(ErrorCode.RESUME_UNSUPPORTED))
            job = self.repository.update(job_id, S.QUEUED, resume=R.UNKNOWN)
            self.condition.notify_all()
            return self.notify(job)

    def cancel(self, job_id):
        with self.condition:
            job = self.repository.get(job_id)
            if job.state in (S.COMPLETED, S.CANCELED, S.PUBLISHING, S.FAILED):
                return False
            if job_id in self.active and job.state not in (S.PAUSED, S.RETRY_WAIT):
                self.active[job_id][0].stop('canceled')
            else:
                self.notify(self.repository.update(job_id, S.CANCELED))
            self.condition.notify_all()
            return True

    def dispatch(self):
        with self.condition:
            while not self.stopping:
                for job in self.repository.list():
                    if job.state == S.RETRY_WAIT and job.next_retry <= self.clock():
                        job = self.new_attempt(job)
                        self.notify(job)
                    if len(self.active) >= self.concurrency:
                        break
                    if job.id in self.active or job.state not in (S.QUEUED, S.PUBLISHING):
                        continue
                    control = Control()
                    worker = threading.Thread(target=self.execute, args=(job, control), daemon=True)
                    self.active[job.id] = (control, worker)
                    worker.start()
                self.condition.wait(.1)

    def phase(self, job_id, control, state, **fields):
        with self.lock:
            control.check()
            return self.notify(self.repository.update(job_id, state, **fields))

    def execute(self, initial, control):
        job_id = initial.id
        try:
            job = self.repository.get(job_id)
            if job.state != S.PUBLISHING:
                self.verifier.require_tools()
                job.stage.mkdir(parents=True, exist_ok=True, mode=0o700)
                if job.artifact is None:
                    job = self.phase(job_id, control, S.DOWNLOADING)

                    def progress(amount, total):
                        with self.lock:
                            control.check()
                            self.notify(self.repository.update(job_id, progress_bytes=amount, total_bytes=total))

                    def capability(value):
                        with self.lock:
                            control.check()
                            self.notify(self.repository.update(job_id, resume=value))

                    def postprocess():
                        if self.repository.get(job_id).state == S.DOWNLOADING:
                            self.phase(job_id, control, S.POSTPROCESSING)

                    artifact = self.transport_factory(job).transfer(job, control, progress, capability, postprocess)
                    artifact = Path(artifact)
                    if not artifact.resolve().is_relative_to(job.stage.resolve()):
                        raise TaskFailure(DownloadError(ErrorCode.VERIFY_FAILED))
                    if job.variant.requires_merge:
                        postprocess()
                    job = self.phase(job_id, control, S.VERIFYING, artifact=artifact)
                else:
                    job = self.phase(job_id, control, S.VERIFYING)
                verification = self.verifier.verify(job.artifact, job.variant, control)
                if job.digest and verification.digest != job.digest:
                    raise TaskFailure(DownloadError(ErrorCode.VERIFY_FAILED))
                job = self.phase(job_id, control, S.PUBLISHING, verified_bytes=verification.size, digest=verification.digest)
            # Publication is a commit boundary: once intent is persisted, finish it
            # or recover it next startup, rather than reporting a canceled saved file.
            publish_control = Control()
            target = self.publisher.publish(job, self.repository, publish_control)
            self.notify(self.repository.update(job_id, S.COMPLETED, output=target, error=None))
        except TaskStopped as exc:
            with self.lock:
                job = self.repository.get(job_id)
                if job.state in (S.DOWNLOADING, S.PAUSING) and (exc.reason == 'paused' or exc.reason == 'interrupted' and job.resume == R.YES):
                    if job.state == S.DOWNLOADING:
                        job = self.repository.update(job_id, S.PAUSING)
                    result = self.repository.update(job_id, S.PAUSED)
                elif exc.reason == 'canceled':
                    result = self.repository.update(job_id, S.CANCELED)
                else:
                    result = self.repository.update(job_id, S.FAILED, error=DownloadError(ErrorCode.INTERRUPTED))
                self.notify(result)
        except Exception as exc:
            with self.lock:
                job = self.repository.get(job_id)
                if isinstance(exc, TaskFailure):
                    error = exc.error
                elif isinstance(exc, OSError):
                    error = DownloadError(ErrorCode.NO_DISK_SPACE if exc.errno == errno.ENOSPC else ErrorCode.PERMISSION_DENIED)
                else:
                    error = DownloadError(ErrorCode.UNKNOWN)
                if error.retryable and job.state == S.DOWNLOADING and job.attempt < self.max_attempts and not self.stopping:
                    delay = max(self.backoff * 2 ** (job.attempt - 1), getattr(exc, 'retry_after', None) or 0)
                    result = self.repository.update(job_id, S.RETRY_WAIT, error=error, next_retry=self.clock() + delay)
                else:
                    result = self.repository.update(job_id, S.FAILED, error=error)
                self.notify(result)
        finally:
            with self.condition:
                self.active.pop(job_id, None)
                self.condition.notify_all()

    def close(self, timeout=30):
        with self.condition:
            self.stopping = True
            for control, _ in self.active.values():
                control.stop('interrupted')
            workers = [worker for _, worker in self.active.values()]
            self.condition.notify_all()
        for worker in workers:
            worker.join(timeout)
        if self.dispatcher:
            self.dispatcher.join(timeout)
        if any(worker.is_alive() for worker in workers):
            raise RuntimeError('Workers still stopping; queue ownership retained')
        with self.lock:
            if self.lease:
                self.lease.close()
                self.lease = None
