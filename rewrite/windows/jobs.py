"""Immutable persisted job state; no GUI, transport or SQL dependencies."""
from dataclasses import dataclass, field
from enum import Enum
from pathlib import Path
import time
from domain import DownloadError, FormatVariant


class JobStatus(str, Enum):
    QUEUED = 'QUEUED'
    DOWNLOADING = 'DOWNLOADING'
    PAUSING = 'PAUSING'
    PAUSED = 'PAUSED'
    POSTPROCESSING = 'POSTPROCESSING'
    VERIFYING = 'VERIFYING'
    PUBLISHING = 'PUBLISHING'
    RETRY_WAIT = 'RETRY_WAIT'
    COMPLETED = 'COMPLETED'
    FAILED = 'FAILED'
    CANCELED = 'CANCELED'


class ResumeCapability(str, Enum):
    UNKNOWN = 'UNKNOWN'
    YES = 'YES'
    NO = 'NO'


S = JobStatus
TRANSITIONS = {
    S.QUEUED: {S.DOWNLOADING, S.VERIFYING, S.CANCELED, S.FAILED},
    S.DOWNLOADING: {S.POSTPROCESSING, S.VERIFYING, S.PAUSING, S.CANCELED, S.RETRY_WAIT, S.FAILED},
    S.PAUSING: {S.PAUSED, S.CANCELED, S.FAILED},
    S.PAUSED: {S.QUEUED, S.CANCELED, S.FAILED},
    S.POSTPROCESSING: {S.VERIFYING, S.CANCELED, S.FAILED},
    S.VERIFYING: {S.PUBLISHING, S.CANCELED, S.FAILED},
    S.PUBLISHING: {S.COMPLETED, S.FAILED},
    S.RETRY_WAIT: {S.QUEUED, S.CANCELED, S.FAILED},
    S.FAILED: {S.QUEUED},
    S.CANCELED: set(), S.COMPLETED: set(),
}


@dataclass(frozen=True)
class DownloadJob:
    id: str
    url: str = field(repr=False)
    title: str
    variant: FormatVariant
    destination: Path
    stage: Path
    backend: str = 'ytdlp'
    created_at: float = field(default_factory=time.time)
    updated_at: float = field(default_factory=time.time)
    state: JobStatus = S.QUEUED
    attempt: int = 1
    next_retry: float = 0
    progress_bytes: int = 0
    total_bytes: int | None = None
    resume: ResumeCapability = ResumeCapability.UNKNOWN
    error: DownloadError | None = None
    artifact: Path | None = None
    output: Path | None = None
    verified_bytes: int | None = None
    digest: str | None = None

    @property
    def percent(self):
        if self.state == S.COMPLETED:
            return 100.0
        if not self.total_bytes:
            return None
        return min(99.0, 100 * self.progress_bytes / self.total_bytes)


STATUS_RU = {
    S.QUEUED: 'В очереди', S.DOWNLOADING: 'Загрузка', S.PAUSING: 'Останавливаем для паузы',
    S.PAUSED: 'Приостановлено', S.POSTPROCESSING: 'Обработка файла',
    S.VERIFYING: 'Проверяем сохранённый файл', S.PUBLISHING: 'Сохраняем проверенный файл',
    S.COMPLETED: 'Файл сохранён', S.FAILED: 'Ошибка', S.CANCELED: 'Отменено',
    S.RETRY_WAIT: 'Повтор с задержкой',
}
