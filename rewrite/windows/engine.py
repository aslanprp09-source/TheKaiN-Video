"""Windows single-job yt-dlp engine. No shell invocation."""
import re
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path
from threading import Lock
from domain import FormatVariant
from extractor import classify_error, validate_url

PROGRESS = re.compile(r"\[download\]\s+(\d+(?:\.\d+)?)%")


@dataclass(frozen=True)
class Options:
    url: str
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
    cmd = [sys.executable, "-m", "yt_dlp", "--ignore-config", "--newline", "--progress", "--no-quiet", "--no-simulate", "--continue", "--no-playlist",
           "--no-mtime", "-P", str(job.folder), "-o", "%(title).150B [%(id)s].%(ext)s",
           "--print", "after_move:THEKAIN_FILE:%(filepath)s", "-f", variant.selector]
    if variant.requires_merge:
        cmd += ["--merge-output-format", variant.final_extension]
    return cmd + ["--", url]


class Engine:
    def __init__(self):
        self.lock = Lock()
        self.proc = None
        self.stop_reason = None

    def stop(self, reason="paused"):
        with self.lock:
            if self.proc and self.proc.poll() is None:
                self.stop_reason = reason
                self.proc.terminate()
                return True
        return False

    def run(self, job, callback):
        folder = job.folder.expanduser().resolve()
        folder.mkdir(parents=True, exist_ok=True)
        job = Options(job.url, folder, job.quality)
        cmd = command_for(job)
        with self.lock:
            if self.proc:
                raise RuntimeError("Загрузка уже выполняется")
            self.stop_reason = None
            proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                    encoding="utf-8", errors="replace", text=True, bufsize=1)
            self.proc = proc
        files = []
        try:
            for raw in proc.stdout:
                line = raw.strip()
                if line.startswith("THEKAIN_FILE:"):
                    candidate = Path(line.partition(":")[2]).expanduser().resolve()
                    if candidate.is_relative_to(folder):
                        files.append(candidate)
                else:
                    match = PROGRESS.search(line)
                    if match:
                        callback("progress", min(99.0, float(match.group(1))))
                    elif "ERROR" in line or "WARNING" in line:
                        # Upstream diagnostics can contain signed URLs/credentials.
                        # Expose only a localized classification, never raw stderr.
                        callback("log", classify_error(line).message)
            code = proc.wait()
            with self.lock:
                stop_reason = self.stop_reason
            if stop_reason:
                return stop_reason
            if code:
                callback("log", "Загрузчик завершился с кодом " + str(code))
                return "failed"
            verified = [p for p in files if p.is_file() and p.stat().st_size > 0]
            if not verified:
                callback("log", "Нет подтверждённого непустого файла")
                return "failed"
            callback("result", ", ".join(str(p) for p in verified))
            return "completed"
        finally:
            with self.lock:
                self.proc = None
