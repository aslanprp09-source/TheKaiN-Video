"""Bounded reference protocol for owned loopback fixtures, NOT an Android runtime."""
from dataclasses import dataclass, field
import json
import math
import os
from pathlib import Path
import selectors
import signal
import subprocess
import sys
import time
from urllib.parse import urlsplit

VERSION = '2026.10.26'
MAX_OUTPUT = 1024 * 1024
ERRORS = frozenset(('INVALID_URL', 'OUT_OF_SCOPE_URL', 'TLS_ERROR', 'HTTP_FORBIDDEN',
    'REMOTE_MISSING', 'RATE_LIMITED', 'NETWORK_ERROR', 'TIMEOUT', 'CANCELED',
    'RESPONSE_LIMIT', 'OUTPUT_LIMIT', 'REQUEST_LIMIT', 'UNSUPPORTED_MEDIA',
    'INVALID_METADATA', 'INVALID_LIMIT', 'TOOL_MISSING', 'TOOL_VERSION', 'TOOL_FAILED', 'CPU_LIMIT'))


class SpikeError(RuntimeError):
    def __init__(self, code):
        self.code = code if code in ERRORS else 'TOOL_FAILED'
        super().__init__(self.code)  # Never include extractor output or signed URLs.


def origin(url):
    try:
        if not isinstance(url, str) or len(url) > 4096 or '\\' in url:
            raise ValueError()
        p = urlsplit(url)
        if (p.scheme != 'https' or p.hostname != '127.0.0.1' or not p.port
                or p.username is not None or p.password is not None or p.fragment
                or any(ord(c) < 33 or ord(c) == 127 for c in url)):
            raise ValueError()
        return p.scheme, p.hostname, p.port
    except (TypeError, ValueError):
        raise SpikeError('INVALID_URL') from None


def bounded_process(command, cancel=None, timeout=10, max_output=MAX_OUTPUT):
    """Trusted argument vector only. Linux host experiment, not a mobile launcher."""
    if os.name != 'posix':
        raise SpikeError('TOOL_FAILED')
    if (type(timeout) not in (int, float) or not math.isfinite(timeout) or not 0 < timeout <= 10
            or type(max_output) is not int or not 0 < max_output <= MAX_OUTPUT):
        raise SpikeError('INVALID_LIMIT')
    if cancel is not None and cancel.is_set():
        raise SpikeError('CANCELED')
    env = {k: v for k, v in os.environ.items() if k in
           ('PATH', 'SYSTEMROOT', 'LANG', 'LC_ALL', 'TMPDIR')}
    started = time.monotonic()
    try:
        process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                   stdin=subprocess.DEVNULL, env=env, start_new_session=True)
    except OSError:
        raise SpikeError('TOOL_MISSING') from None
    output = bytearray(); total = 0
    try:
        with selectors.DefaultSelector() as reader:
            reader.register(process.stdout, selectors.EVENT_READ, True)
            reader.register(process.stderr, selectors.EVENT_READ, False)
            while reader.get_map():
                if cancel is not None and cancel.is_set():
                    raise SpikeError('CANCELED')
                if time.monotonic() - started >= timeout:
                    raise SpikeError('TIMEOUT')
                for key, _ in reader.select(0.05):
                    chunk = os.read(key.fd, 16384)
                    if not chunk:
                        reader.unregister(key.fileobj)
                        continue
                    total += len(chunk)
                    if total > max_output:
                        raise SpikeError('OUTPUT_LIMIT')
                    if key.data:
                        output.extend(chunk)
            return process.wait(timeout=max(0.01, timeout - (time.monotonic() - started))), bytes(output)
    except subprocess.TimeoutExpired:
        raise SpikeError('TIMEOUT') from None
    finally:
        # Kill the whole owned session, including a child that inherited pipe FDs.
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        process.wait()
        process.stdout.close(); process.stderr.close()


def unique_pairs(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise SpikeError('INVALID_METADATA')
        result[key] = value
    return result


def decode(raw):
    try:
        return json.loads(raw.decode('utf-8'), object_pairs_hook=unique_pairs,
                          parse_constant=lambda _: (_ for _ in ()).throw(ValueError()))
    except (ValueError, UnicodeError, RecursionError):
        raise SpikeError('INVALID_METADATA') from None


@dataclass(frozen=True)
class Candidate:
    format_id: str = field(repr=False)
    url: str = field(repr=False)
    container: str = 'mp4'
    byte_size: int | None = None
    # These are extractor declarations, never proof of verified media tracks.
    video_codec: str | None = field(default=None, repr=False)
    audio_codec: str | None = field(default=None, repr=False)
    transfer_eligible: bool = False
    blocked_reason: str | None = 'TRACKS_UNKNOWN'


@dataclass(frozen=True)
class Analysis:
    candidates: tuple[Candidate, ...]
    download_enabled: bool = False


def normalize(info, fixture_origin):
    if not isinstance(info, dict):
        raise SpikeError('INVALID_METADATA')
    if (info.get('_type', 'video') != 'video' or 'entries' in info
            or info.get('is_live') or info.get('has_drm')):
        raise SpikeError('UNSUPPORTED_MEDIA')
    formats = info.get('formats')
    if not isinstance(formats, list) or len(formats) > 128:
        raise SpikeError('INVALID_METADATA')
    candidates = []; ids = set()
    for f in formats:
        if not isinstance(f, dict):
            raise SpikeError('INVALID_METADATA')
        # Never infer audio from resolution/container or treat HLS/DASH as a file.
        if (f.get('has_drm') or f.get('protocol') != 'https' or f.get('ext') != 'mp4'
                or f.get('fragments') or f.get('manifest_url')
                or any(f.get(k) == 'none' for k in ('vcodec', 'acodec'))):
            continue
        url = f.get('url')
        if origin(url) != fixture_origin:
            raise SpikeError('OUT_OF_SCOPE_URL')
        headers = f.get('http_headers', {})
        if (not isinstance(headers, dict) or any(not isinstance(k, str) or k.lower() not in
                ('user-agent', 'accept', 'accept-language', 'sec-fetch-mode', 'referer')
                for k in headers)):
            raise SpikeError('UNSUPPORTED_MEDIA')
        identifier = f.get('format_id')
        if not isinstance(identifier, str) or not 0 < len(identifier) <= 128 or identifier in ids:
            raise SpikeError('INVALID_METADATA')
        ids.add(identifier)
        size = f.get('filesize')
        if size is not None and (type(size) is not int or not 0 < size <= 2**63 - 1):
            raise SpikeError('INVALID_METADATA')
        codecs = [f.get(k) if isinstance(f.get(k), str) and f[k] not in ('', 'unknown')
                  else None for k in ('vcodec', 'acodec')]
        eligible = all(codecs)
        candidates.append(Candidate(identifier, url, byte_size=size,
            video_codec=codecs[0], audio_codec=codecs[1], transfer_eligible=eligible,
            blocked_reason=None if eligible else 'TRACKS_UNKNOWN'))
    if not candidates:
        raise SpikeError('UNSUPPORTED_MEDIA')
    return Analysis(tuple(candidates))


def analyze_fixture(url, ca_file, cancel=None, timeout=10):
    fixture_origin = origin(url)
    ca = Path(ca_file).resolve(strict=True)
    worker = Path(__file__).with_name('worker.py')
    code, raw = bounded_process([sys.executable, '-I', str(worker), url, str(ca)],
                                cancel=cancel, timeout=timeout)
    data = decode(raw)
    if code:
        raise SpikeError(data.get('error_code', 'TOOL_FAILED') if isinstance(data, dict) else 'TOOL_FAILED')
    return normalize(data, fixture_origin)
