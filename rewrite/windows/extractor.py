"""Bounded, cancelable yt-dlp metadata subprocess; no GUI or network in parsing."""
import json
import math
import re
import subprocess
import sys
import time
from threading import Event
from urllib.parse import urlsplit

from domain import AnalyzeResult, DownloadError, ErrorCode, FormatVariant, SourceMedia


def validate_url(value: str) -> str:
    if not isinstance(value, str) or any(ord(c) < 32 or ord(c) == 127 for c in value):
        raise ValueError(DownloadError(ErrorCode.INVALID_URL).message)
    url = value.strip()
    try:
        p = urlsplit(url)
        if (p.scheme not in ("http", "https") or not p.hostname or p.username is not None
                or p.password is not None or p.port == 0 or any(c.isspace() or ord(c) < 32 or ord(c) == 127 for c in url)
                or "\\" in url):
            raise ValueError
        p.hostname.encode("idna")
    except (ValueError, UnicodeError):
        raise ValueError(DownloadError(ErrorCode.INVALID_URL).message) from None
    return url


def classify_error(stderr: str) -> DownloadError:
    text = re.sub(r"https?://\S+", "[url]", stderr.lower())
    groups = (
        (ErrorCode.TOOL_MISSING, ("no module named yt_dlp", "no module named 'yt_dlp'")),
        (ErrorCode.DRM_PROTECTED, ("drm", "digital rights")),
        (ErrorCode.GEO_RESTRICTED, ("not available in your country", "geo restricted", "geo-restricted")),
        (ErrorCode.RATE_LIMITED, ("429", "too many requests")),
        (ErrorCode.AUTH_REQUIRED, ("private video", "sign in", "login required", "authentication required", "members-only", "premium-only")),
        (ErrorCode.REMOTE_MISSING, ("404", "410", "video has been removed", "video unavailable")),
        (ErrorCode.REMOTE_FORBIDDEN, ("403", "forbidden")),
        (ErrorCode.TIMEOUT, ("timed out", "timeout")),
        (ErrorCode.NETWORK_LOST, ("connection", "unable to download", "name resolution", "network is unreachable")),
        (ErrorCode.UNSUPPORTED_SOURCE, ("unsupported url", "no suitable extractor")),
        (ErrorCode.NO_FORMATS, ("no video formats", "requested format is not available")),
    )
    for code, fragments in groups:
        if any(fragment in text for fragment in fragments):
            return DownloadError(code, code in {ErrorCode.RATE_LIMITED, ErrorCode.TIMEOUT, ErrorCode.NETWORK_LOST})
    return DownloadError(ErrorCode.UNKNOWN)


def _number(value):
    return value if isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value) and value > 0 else None


def _codec(value):
    return value if isinstance(value, str) and value and value != "none" else None


def _usable(f):
    return (isinstance(f, dict) and isinstance(f.get("format_id"), str)
            and re.fullmatch(r"[A-Za-z0-9_.-]+", f["format_id"])
            and isinstance(f.get("ext"), str) and re.fullmatch(r"[a-zA-Z0-9]+", f["ext"])
            and f.get("has_drm") is not True and f.get("has_drm") != "maybe"
            and bool(_codec(f.get("vcodec")) or _codec(f.get("acodec"))))


def parse_metadata(data: dict, url: str) -> AnalyzeResult:
    """No guessed resolutions/conversions. Merge only actual video+audio IDs."""
    url = validate_url(url)
    if not isinstance(data, dict):
        return AnalyzeResult(error=DownloadError(ErrorCode.UNKNOWN))
    if data.get("has_drm") is True:
        return AnalyzeResult(error=DownloadError(ErrorCode.DRM_PROTECTED))
    if data.get("availability") in ("private", "premium_only", "subscriber_only", "needs_auth"):
        return AnalyzeResult(error=DownloadError(ErrorCode.AUTH_REQUIRED))
    if data.get("_type") in ("playlist", "multi_video") or "entries" in data:
        return AnalyzeResult(error=DownloadError(ErrorCode.UNSUPPORTED_SOURCE))
    raw = data.get("formats", [])
    if not isinstance(raw, list):
        return AnalyzeResult(error=DownloadError(ErrorCode.UNKNOWN))
    formats = [f for f in raw if _usable(f)]
    audio = [f for f in formats if not _codec(f.get("vcodec")) and _codec(f.get("acodec"))]
    languages = tuple(sorted(k for k in (data.get("subtitles") or {}) if isinstance(k, str))) if isinstance(data.get("subtitles", {}), dict) else ()
    variants = []
    seen = set()
    for f in formats:
        if f["format_id"] in seen:
            continue
        seen.add(f["format_id"])
        vc, ac = _codec(f.get("vcodec")), _codec(f.get("acodec"))
        partner = None
        ext = f["ext"]
        if vc and not ac and audio:
            compatible = [a for a in audio if (ext == "mp4" and a["ext"] in ("m4a", "mp4", "aac")) or (ext == "webm" and a["ext"] == "webm")]
            partner = max(compatible or audio, key=lambda a: (_number(a.get("abr")) or 0, a["format_id"]))
            ext = "mp4" if ext == "mp4" and partner["ext"] in ("m4a", "mp4", "aac") else ("webm" if ext == partner["ext"] == "webm" else "mkv")
            ac = _codec(partner.get("acodec"))
        parts = [f, partner] if partner else [f]
        exact = [_number(p.get("filesize")) for p in parts]
        estimates = [_number(p.get("filesize")) or _number(p.get("filesize_approx")) for p in parts]
        known = int(sum(exact)) if all(x is not None for x in exact) else None
        estimated = int(sum(estimates)) if known is None and all(x is not None for x in estimates) else None
        variants.append(FormatVariant(
            extractor_format_id=f["format_id"], container=f.get("container") or f["ext"], final_extension=ext,
            video_width=int(_number(f.get("width"))) if _number(f.get("width")) else None,
            video_height=int(_number(f.get("height"))) if _number(f.get("height")) else None,
            fps=_number(f.get("fps")), video_codec=vc, audio_codec=ac, has_audio=ac is not None,
            byte_size_known=known, byte_size_estimated=estimated, requires_merge=partner is not None,
            audio_format_id=partner["format_id"] if partner else None, subtitle_languages=languages))
    variants.sort(key=lambda v: (v.video_codec is not None, v.video_height or 0, v.fps or 0, v.selector), reverse=True)
    if not variants:
        drm = any(isinstance(f, dict) and f.get("has_drm") for f in raw)
        return AnalyzeResult(error=DownloadError(ErrorCode.DRM_PROTECTED if drm else ErrorCode.NO_FORMATS))
    thumbnail = data.get("thumbnail")
    try:
        thumbnail = validate_url(thumbnail) if isinstance(thumbnail, str) else None
    except ValueError:
        thumbnail = None
    return AnalyzeResult(media=SourceMedia(
        source_id=str(data["id"]) if data.get("id") is not None else None,
        source_host=urlsplit(url).hostname, display_title=str(data.get("title") or "Без названия"),
        duration_seconds=_number(data.get("duration")), thumbnail_uri=thumbnail, formats=tuple(variants)))


class SourceAnalyzer:
    def __init__(self, command=None):
        self.command = list(command) if command is not None else [sys.executable, "-m", "yt_dlp"]

    def analyze(self, url: str, cancel: Event | None = None, timeout: float = 30) -> AnalyzeResult:
        try:
            url = validate_url(url)
        except (ValueError, AttributeError):
            return AnalyzeResult(error=DownloadError(ErrorCode.INVALID_URL))
        cancel = cancel if cancel is not None else Event()
        if cancel.is_set():
            return AnalyzeResult(error=DownloadError(ErrorCode.CANCELED))
        cmd = self.command + ["--ignore-config", "--dump-single-json", "--skip-download", "--no-playlist", "--no-warnings", "--", url]
        try:
            proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                    text=True, encoding="utf-8", errors="replace", shell=False)
        except FileNotFoundError:
            return AnalyzeResult(error=DownloadError(ErrorCode.TOOL_MISSING))
        except OSError:
            return AnalyzeResult(error=DownloadError(ErrorCode.UNKNOWN))
        deadline = time.monotonic() + timeout
        try:
            while True:
                if cancel.is_set() or time.monotonic() >= deadline:
                    code = ErrorCode.CANCELED if cancel.is_set() else ErrorCode.TIMEOUT
                    return AnalyzeResult(error=DownloadError(code, code == ErrorCode.TIMEOUT))
                try:
                    stdout, stderr = proc.communicate(timeout=min(0.1, max(0.001, deadline - time.monotonic())))
                    break
                except subprocess.TimeoutExpired:
                    continue
            if cancel.is_set():
                return AnalyzeResult(error=DownloadError(ErrorCode.CANCELED))
            if proc.returncode:
                return AnalyzeResult(error=classify_error(stderr))
            try:
                return parse_metadata(json.loads(stdout), url)
            except (ValueError, TypeError):
                return AnalyzeResult(error=DownloadError(ErrorCode.UNKNOWN))
        finally:
            if proc.poll() is None:
                proc.terminate()
                try:
                    proc.communicate(timeout=1)
                except subprocess.TimeoutExpired:
                    proc.kill()
                    proc.communicate()
