"""Stage command construction only; DownloadQueue owns execution and success."""
import re
from dataclasses import dataclass, field
from pathlib import Path
from domain import FormatVariant
from extractor import validate_url
from extractor_runtime import extractor_command


@dataclass(frozen=True)
class Options:
    url: str = field(repr=False)
    folder: Path
    quality: FormatVariant | None = None


def command_for(job):
    url = validate_url(job.url)
    variant = job.quality
    if not isinstance(variant, FormatVariant):
        raise ValueError("Сначала определите доступные форматы")
    for format_id in (variant.extractor_format_id, variant.audio_format_id):
        if format_id is not None and not re.fullmatch(r"[A-Za-z0-9_.-]+", format_id):
            raise ValueError("Недопустимый идентификатор формата")
    if not re.fullmatch(r"[A-Za-z0-9]+", variant.final_extension):
        raise ValueError("Недопустимое расширение")
    cmd = extractor_command() + ["--ignore-config", "--newline", "--progress", "--no-quiet", "--no-simulate", "--continue", "--no-playlist",
           "--no-mtime", "-P", str(job.folder), "-o", "%(title).150B [%(id)s].%(ext)s",
           "--print", "after_move:THEKAIN_FILE:%(filepath)s", "-f", variant.selector]
    if variant.requires_merge:
        cmd += ["--merge-output-format", variant.final_extension]
    return cmd + ["--", url]
