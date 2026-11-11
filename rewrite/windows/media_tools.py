"""FFmpeg/FFprobe validation and SHA-256 evidence before publication."""
import hashlib
import json
import shutil
from dataclasses import dataclass
from pathlib import Path
from domain import DownloadError, ErrorCode
from process_runner import TaskFailure, run_process


@dataclass(frozen=True)
class Verification:
    size: int
    digest: str


def hash_file(path, control):
    digest = hashlib.sha256()
    with Path(path).open('rb') as stream:
        while block := stream.read(1024 * 1024):
            control.check()
            digest.update(block)
    return digest.hexdigest()


class MediaVerifier:
    def __init__(self, ffprobe='ffprobe', ffmpeg='ffmpeg'):
        self.ffprobe, self.ffmpeg = ffprobe, ffmpeg

    def require_tools(self):
        if not shutil.which(self.ffprobe) or not shutil.which(self.ffmpeg):
            raise TaskFailure(DownloadError(ErrorCode.TOOL_MISSING))

    def verify(self, path, variant, control):
        self.require_tools()
        path = Path(path)
        if path.is_symlink() or not path.is_file() or path.stat().st_size == 0 or path.suffix.lower() != '.' + variant.final_extension.lower():
            raise TaskFailure(DownloadError(ErrorCode.VERIFY_FAILED))
        if not variant.requires_merge and variant.byte_size_known and path.stat().st_size != variant.byte_size_known:
            raise TaskFailure(DownloadError(ErrorCode.VERIFY_FAILED))
        result = run_process([self.ffprobe, '-v', 'error', '-show_format', '-show_streams', '-of', 'json', str(path)], control, timeout=60)
        try:
            data = json.loads(result.stdout)
            kinds = {s.get('codec_type') for s in data['streams']}
            containers = set(data['format']['format_name'].split(','))
        except (ValueError, TypeError, KeyError):
            raise TaskFailure(DownloadError(ErrorCode.VERIFY_FAILED)) from None
        expected = {'video'} if variant.video_codec else set()
        if variant.has_audio:
            expected.add('audio')
        container_map = {'mp4': {'mp4', 'mov'}, 'm4a': {'m4a', 'mp4', 'mov'},
                         'mkv': {'matroska'}, 'webm': {'webm', 'matroska'}, 'mp3': {'mp3'},
                         'ogg': {'ogg'}, 'opus': {'ogg'}, 'aac': {'aac'}, 'wav': {'wav'},
                         'flac': {'flac'}, 'mov': {'mov'}, 'ts': {'mpegts'}}
        allowed = container_map.get(variant.final_extension, {variant.final_extension})
        if result.returncode or result.stderr.strip() or not expected or not expected <= kinds or not containers & allowed:
            raise TaskFailure(DownloadError(ErrorCode.VERIFY_FAILED))
        # Probe alone can accept a truncated container. Decode all media packets too.
        decode = run_process([self.ffmpeg, '-nostdin', '-v', 'error', '-xerror', '-threads', '2', '-i', str(path),
                              '-map', '0:v?', '-map', '0:a?', '-f', 'null', '-'], control, timeout=3600)
        if decode.returncode or decode.stderr.strip():
            raise TaskFailure(DownloadError(ErrorCode.VERIFY_FAILED))
        control.check()
        return Verification(path.stat().st_size, hash_file(path, control))
