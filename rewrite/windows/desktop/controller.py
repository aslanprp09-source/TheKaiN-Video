"""Async application adapter: all engine/database/thumbnail work stays off Qt's UI thread."""
import shutil
import threading
import time
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from PySide6.QtCore import QObject, Signal, QBuffer, QByteArray, QIODevice
from PySide6.QtGui import QImageReader
from desktop.strings import tr
from domain import AnalyzeResult, DownloadError, ErrorCode
from download_queue import DownloadQueue
from extractor import SourceAnalyzer, validate_url
from extractor_runtime import extractor_available
from jobs import JobStatus as S
from transports import SafeRedirect


class Controller(QObject):
    analysis = Signal(int, object)
    thumbnail = Signal(int, object)
    job = Signal(object)
    ready = Signal(object)
    error = Signal(str)
    queued = Signal(str)
    enqueue_failed = Signal(str)
    open_path = Signal(object)
    closed = Signal()

    def __init__(self, root, concurrency=2, queue_factory=DownloadQueue, analyzer=None):
        super().__init__()
        self.root, self.concurrency = Path(root), concurrency
        self.queue_factory, self.analyzer = queue_factory, analyzer or SourceAnalyzer()
        self.queue = None
        self.cancel = threading.Event()
        self.generation = 0
        self.commands = ThreadPoolExecutor(max_workers=1, thread_name_prefix='application')
        self.analyses = ThreadPoolExecutor(max_workers=2, thread_name_prefix='analysis')
        self.images = ThreadPoolExecutor(max_workers=1, thread_name_prefix='thumbnail')
        self.stopping = False
        QImageReader.setAllocationLimit(32)

    def start(self):
        self.commands.submit(self._start)

    def _start(self):
        try:
            self.queue = self.queue_factory(self.root, callback=self.job.emit, concurrency=self.concurrency)
            self.queue.start()
            self.ready.emit(self.tools())
        except Exception:
            self.error.emit(tr('error.queue_open'))

    @staticmethod
    def tools():
        return {'ffmpeg': bool(shutil.which('ffmpeg')), 'ffprobe': bool(shutil.which('ffprobe')),
                'yt-dlp': extractor_available()}

    def invalidate(self):
        self.cancel.set()
        self.cancel = threading.Event()
        self.generation += 1
        return self.generation

    def analyze(self, url):
        generation = self.invalidate()
        self.analyses.submit(self._analyze, generation, url, self.cancel)
        return generation

    def _analyze(self, generation, url, cancel):
        try:
            result = self.analyzer.analyze(url, cancel)
        except Exception:
            result = AnalyzeResult(error=DownloadError(ErrorCode.UNKNOWN))
        self.analysis.emit(generation, result)

    def load_thumbnail(self, generation, url):
        if url:
            self.images.submit(self._thumbnail, generation, url)

    def _thumbnail(self, generation, url):
        try:
            opener = urllib.request.build_opener(SafeRedirect())
            with opener.open(validate_url(url), timeout=5) as response:
                if response.headers.get_content_type() not in ('image/png', 'image/jpeg', 'image/webp'):
                    return
                data = bytearray()
                deadline = time.monotonic() + 8
                while not self.stopping and generation == self.generation:
                    block = response.read1(32768)
                    if not block:
                        break
                    data.extend(block)
                    if len(data) > 4 * 1024 * 1024 or time.monotonic() >= deadline:
                        return
                if generation != self.generation or self.stopping:
                    return
            buffer = QBuffer()
            buffer.setData(QByteArray(data))
            buffer.open(QIODevice.OpenModeFlag.ReadOnly)
            reader = QImageReader(buffer)
            size = reader.size()
            if size.width() > 4096 or size.height() > 4096:
                return
            image = reader.read()
            if not image.isNull():
                self.thumbnail.emit(generation, image)
        except Exception:
            pass  # A thumbnail is optional, never a download failure or raw diagnostic.

    def command(self, name, *args):
        if not self.stopping:
            self.commands.submit(self._command, name, args)

    def _command(self, name, args):
        try:
            if not self.queue:
                raise RuntimeError('Queue unavailable')
            if name == 'enqueue':
                options, title = args
                job = self.queue.enqueue(options, title)
                self.queued.emit(job.id)
            elif name == 'concurrency':
                with self.queue.condition:
                    self.queue.concurrency = max(1, min(3, int(args[0])))
                    self.queue.condition.notify_all()
            elif name == 'open':
                job = self.queue.repository.get(args[0])
                if job.state != S.COMPLETED or not job.output or not job.output.is_file():
                    self.error.emit(tr('error.file_missing'))
                    return
                self.open_path.emit(job.output.parent if args[1] else job.output)
            elif name == 'data_folder':
                self.open_path.emit(self.root)
            elif name == 'tools':
                self.ready.emit(self.tools())
            else:
                getattr(self.queue, name)(*args)
        except Exception:
            if name == 'enqueue':
                self.enqueue_failed.emit(tr('error.command'))
            else:
                self.error.emit(tr('error.command'))

    def shutdown(self):
        if self.stopping:
            return
        self.stopping = True
        self.cancel.set()
        self.commands.submit(self._shutdown)

    def _shutdown(self):
        if self.queue:
            while True:
                try:
                    self.queue.close(timeout=1)
                    break
                except RuntimeError:
                    # Publishing is an M2 commit boundary: wait off the UI thread
                    # instead of abandoning its worker or releasing ownership early.
                    time.sleep(.2)
                except Exception:
                    self.error.emit(tr('error.shutdown'))
                    return
        self.analyses.shutdown(wait=True, cancel_futures=True)
        self.images.shutdown(wait=True, cancel_futures=True)
        self.closed.emit()
        self.commands.shutdown(wait=False)
