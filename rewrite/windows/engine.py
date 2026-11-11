"""Windows single-job yt-dlp engine. No shell invocation."""
import re
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path
from threading import Lock
from urllib.parse import urlparse

FORMAT_CHOICES = ("Лучшее видео", "1080p", "720p", "480p", "MP3")
FORMAT_IDS = {
    "Лучшее видео": "bv*+ba/b",
    "1080p": "bv*[height<=1080]+ba/b[height<=1080]",
    "720p": "bv*[height<=720]+ba/b[height<=720]",
    "480p": "bv*[height<=480]+ba/b[height<=480]",
    "MP3": "ba/b",
}
PROGRESS = re.compile(r"\[download\]\s+(\d+(?:\.\d+)?)%")


@dataclass(frozen=True)
class Options:
    url: str
    folder: Path
    quality: str = "Лучшее видео"


def command_for(job):
    url = job.url.strip()
    parsed = urlparse(url)
    if parsed.scheme not in ("https", "http") or not parsed.hostname:
        raise ValueError("Введите корректную HTTP(S)-ссылку")
    if parsed.username or parsed.password or any(c in url for c in ("\n", "\r", "\x00")):
        raise ValueError("В ссылке есть недопустимые символы или учётные данные")
    if job.quality not in FORMAT_IDS:
        raise ValueError("Неизвестный формат")
    cmd = [sys.executable, "-m", "yt_dlp", "--newline", "--continue", "--no-playlist",
           "--no-mtime", "-P", str(job.folder), "-o", "%(title).150B [%(id)s].%(ext)s",
           "--print", "after_move:THEKAIN_FILE:%(filepath)s", "-f", FORMAT_IDS[job.quality]]
    if job.quality == "MP3":
        cmd += ["-x", "--audio-format", "mp3"]
    else:
        cmd += ["--merge-output-format", "mp4"]
    return cmd + [url]


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
                        callback("log", line[:300])
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
