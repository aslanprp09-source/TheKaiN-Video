"""GUI-independent contracts and localizable errors for source analysis."""
from dataclasses import dataclass
from enum import Enum


class ErrorCode(str, Enum):
    INVALID_URL = "INVALID_URL"
    UNSUPPORTED_SOURCE = "UNSUPPORTED_SOURCE"
    NO_FORMATS = "NO_FORMATS"
    AUTH_REQUIRED = "AUTH_REQUIRED"
    DRM_PROTECTED = "DRM_PROTECTED"
    GEO_RESTRICTED = "GEO_RESTRICTED"
    REMOTE_FORBIDDEN = "REMOTE_FORBIDDEN"
    REMOTE_MISSING = "REMOTE_MISSING"
    RATE_LIMITED = "RATE_LIMITED"
    TIMEOUT = "TIMEOUT"
    NETWORK_LOST = "NETWORK_LOST"
    TOOL_MISSING = "TOOL_MISSING"
    CANCELED = "CANCELED"
    UNKNOWN = "UNKNOWN"
    REMOTE_SERVER = "REMOTE_SERVER"
    NO_DISK_SPACE = "NO_DISK_SPACE"
    PERMISSION_DENIED = "PERMISSION_DENIED"
    RESUME_UNSUPPORTED = "RESUME_UNSUPPORTED"
    MERGE_FAILED = "MERGE_FAILED"
    VERIFY_FAILED = "VERIFY_FAILED"
    INTERRUPTED = "INTERRUPTED"


MESSAGES_RU = {
    ErrorCode.INVALID_URL: "Введите HTTP(S)-ссылку без учётных данных и управляющих символов.",
    ErrorCode.UNSUPPORTED_SOURCE: "Этот источник не поддерживается. Выберите ссылку на одно видео.",
    ErrorCode.NO_FORMATS: "Доступных форматов не найдено.",
    ErrorCode.AUTH_REQUIRED: "Источник требует доступа или входа. Закрытые материалы не поддерживаются.",
    ErrorCode.DRM_PROTECTED: "Материал защищён DRM и не поддерживается.",
    ErrorCode.GEO_RESTRICTED: "Материал недоступен в вашем регионе.",
    ErrorCode.REMOTE_FORBIDDEN: "Источник запретил доступ (403). Проверьте доступность материала.",
    ErrorCode.REMOTE_MISSING: "Материал удалён или не найден (404).",
    ErrorCode.RATE_LIMITED: "Источник ограничил запросы (429). Попробуйте позже.",
    ErrorCode.TIMEOUT: "Время ожидания истекло. Проверьте сеть и повторите анализ.",
    ErrorCode.NETWORK_LOST: "Не удалось связаться с источником. Проверьте подключение.",
    ErrorCode.TOOL_MISSING: "Не найден необходимый инструмент: yt-dlp, FFmpeg или FFprobe. Проверьте зависимости.",
    ErrorCode.CANCELED: "Анализ отменён.",
    ErrorCode.UNKNOWN: "Не удалось разобрать ответ источника. Повторите анализ или выберите другую ссылку.",
    ErrorCode.REMOTE_SERVER: "Ошибка сервера источника. Повторим запрос с задержкой.",
    ErrorCode.NO_DISK_SPACE: "Недостаточно места на диске. Освободите место и повторите задачу.",
    ErrorCode.PERMISSION_DENIED: "Нет доступа к папке или операция сохранения не поддерживается.",
    ErrorCode.RESUME_UNSUPPORTED: "Источник не подтвердил безопасное продолжение. Начните новую попытку.",
    ErrorCode.MERGE_FAILED: "Не удалось объединить дорожки. Проверьте FFmpeg и повторите задачу.",
    ErrorCode.VERIFY_FAILED: "Проверка файла не пройдена. Файл не отмечен сохранённым.",
    ErrorCode.INTERRUPTED: "Задача прервана. Проверьте источник и начните новую попытку.",
}


@dataclass(frozen=True)
class DownloadError:
    code: ErrorCode
    retryable: bool = False

    @property
    def message_key(self) -> str:
        return "error." + self.code.value.lower()

    @property
    def message(self) -> str:
        return MESSAGES_RU[self.code]


@dataclass(frozen=True)
class FormatVariant:
    extractor_format_id: str
    container: str
    final_extension: str
    video_width: int | None = None
    video_height: int | None = None
    fps: float | None = None
    video_codec: str | None = None
    audio_codec: str | None = None
    has_audio: bool = False
    byte_size_known: int | None = None
    byte_size_estimated: int | None = None
    requires_merge: bool = False
    audio_format_id: str | None = None
    subtitle_languages: tuple[str, ...] = ()

    @property
    def selector(self) -> str:
        if self.audio_format_id:
            return self.extractor_format_id + "+" + self.audio_format_id
        return self.extractor_format_id

    @property
    def label(self) -> str:
        kind = f"{self.video_height}p" if self.video_height else ("Видео" if self.video_codec else "Аудио")
        size = "—"
        amount = self.byte_size_known if self.byte_size_known is not None else self.byte_size_estimated
        if amount is not None:
            size = (f"{amount / 1048576:.1f} МиБ" if amount >= 1048576 else
                    f"{amount / 1024:.1f} КиБ" if amount >= 1024 else f"{amount} Б")
            if self.byte_size_known is None:
                size = "≈ " + size
        audio = " · без звука" if self.video_codec and not self.has_audio else ""
        merge = " · FFmpeg" if self.requires_merge else ""
        return f"{kind} · {self.final_extension} · {size}{audio}{merge} [{self.selector}]"


@dataclass(frozen=True)
class SourceMedia:
    source_id: str | None
    source_host: str
    display_title: str
    duration_seconds: float | None
    thumbnail_uri: str | None
    formats: tuple[FormatVariant, ...]
    playlist_count: int | None = None


@dataclass(frozen=True)
class AnalyzeResult:
    media: SourceMedia | None = None
    error: DownloadError | None = None

    def __post_init__(self):
        if (self.media is None) == (self.error is None):
            raise ValueError("Expected exactly one of media/error")
