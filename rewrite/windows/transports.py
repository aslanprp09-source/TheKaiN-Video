"""Stage-only yt-dlp and direct HTTP transports with explicit resume evidence."""
import hashlib
import json
import re
import email.utils
import time
import urllib.error
import urllib.request
from pathlib import Path
from domain import DownloadError, ErrorCode
from engine import Options, command_for
from extractor import classify_error, validate_url
from extractor_runtime import extractor_command
from jobs import ResumeCapability as R
from process_runner import TaskFailure, run_process


class YtDlpTransport:
    def __init__(self, fixture_info=None):
        # Optional owned info-json is exclusively for offline integration tests.
        self.fixture_info = fixture_info

    def transfer(self, job, control, progress, capability, phase):
        capability(R.NO)  # Partial files alone do not establish remote resume support.
        cmd = command_for(Options(job.url, job.stage, job.variant))
        output_index = cmd.index('-o') + 1
        cmd[output_index] = 'media.%(ext)s'
        cmd.remove('--continue')
        offset = len(extractor_command())
        if self.fixture_info is not None:
            cmd[offset:offset] = ['--load-info-json', str(self.fixture_info)]
        cmd[offset:offset] = ['--no-continue', '--retries', '0', '--fragment-retries', '0',
                    '--extractor-retries', '0', '--progress-delta', '1', '--progress-template',
                    'download:THEKAIN_PROGRESS:%(info.format_id)s:%(progress.downloaded_bytes)s:%(progress.total_bytes)s',
                    '--print', 'post_process:THEKAIN_POSTPROCESS']
        files, amounts, totals = [], {}, {}
        expected_tracks = 2 if job.variant.requires_merge else 1

        def line(raw):
            if raw.startswith('THEKAIN_FILE:'):
                path = Path(raw.partition(':')[2].strip()).resolve()
                if not path.is_relative_to(job.stage.resolve()) or path.is_symlink():
                    raise TaskFailure(DownloadError(ErrorCode.VERIFY_FAILED))
                files.append(path)
            elif raw.startswith('THEKAIN_POSTPROCESS') and job.variant.requires_merge:
                phase()
            elif raw.startswith('THEKAIN_PROGRESS:'):
                parts = raw.strip().split(':')
                if len(parts) == 4 and parts[2].isdigit():
                    amounts[parts[1]] = int(parts[2])
                    if parts[3].isdigit():
                        totals[parts[1]] = int(parts[3])
                    total = sum(totals.values()) if len(totals) == expected_tracks else job.variant.byte_size_known
                    progress(sum(amounts.values()), total)

        result = run_process(cmd, control, timeout=24 * 3600, on_line=line)
        if result.returncode:
            error = classify_error(result.stderr)
            if error.code == ErrorCode.UNKNOWN and job.variant.requires_merge:
                error = DownloadError(ErrorCode.MERGE_FAILED)
            raise TaskFailure(error)
        if len(files) != 1:
            raise TaskFailure(DownloadError(ErrorCode.VERIFY_FAILED))
        return files[0]


class SafeRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        validate_url(newurl)
        if req.full_url.startswith('https:') and not newurl.startswith('https:'):
            raise TaskFailure(DownloadError(ErrorCode.REMOTE_FORBIDDEN))
        return super().redirect_request(req, fp, code, msg, headers, newurl)


class HttpTransport:
    """For explicit direct media URLs only. Strong ETag + actual 206 required."""
    def __init__(self, timeout=5):
        self.timeout = timeout
        self.opener = urllib.request.build_opener(SafeRedirect())

    def open(self, url, headers):
        request = urllib.request.Request(validate_url(url), headers={'Accept-Encoding': 'identity', **headers})
        try:
            return self.opener.open(request, timeout=self.timeout)
        except urllib.error.HTTPError as exc:
            codes = {403: ErrorCode.REMOTE_FORBIDDEN, 404: ErrorCode.REMOTE_MISSING,
                     410: ErrorCode.REMOTE_MISSING, 401: ErrorCode.AUTH_REQUIRED,
                     412: ErrorCode.RESUME_UNSUPPORTED, 416: ErrorCode.RESUME_UNSUPPORTED,
                     429: ErrorCode.RATE_LIMITED}
            code = codes.get(exc.code, ErrorCode.REMOTE_SERVER if exc.code >= 500 else ErrorCode.UNKNOWN)
            retry_after = exc.headers.get('Retry-After', '')
            exc.close()
            failure = TaskFailure(DownloadError(code, code in (ErrorCode.REMOTE_SERVER, ErrorCode.RATE_LIMITED)))
            if retry_after.isdigit():
                failure.retry_after = int(retry_after)
            else:
                try:
                    failure.retry_after = max(0, email.utils.parsedate_to_datetime(retry_after).timestamp() - time.time())
                except (ValueError, TypeError, OverflowError):
                    failure.retry_after = None
            raise failure from None

    def transfer(self, job, control, progress, capability, phase):
        path = job.stage / ('media.' + job.variant.final_extension)
        partial = job.stage / 'media.part'
        checkpoint = job.stage / 'resume.json'
        fingerprint = hashlib.sha256(job.url.encode()).hexdigest()
        offset = partial.stat().st_size if partial.exists() else 0
        response = None
        try:
            if offset:
                try:
                    saved = json.loads(checkpoint.read_text())
                    assert saved['url_hash'] == fingerprint and offset <= saved['total']
                except (OSError, ValueError, KeyError, AssertionError):
                    raise TaskFailure(DownloadError(ErrorCode.RESUME_UNSUPPORTED)) from None
                if offset == saved['total']:
                    control.check()
                    progress(offset, saved['total'])
                    partial.replace(path)
                    return path  # Complete staging still must pass full verification.
                response = self.open(job.url, {'Range': f'bytes={offset}-', 'If-Range': saved['etag']})
                content_range = response.headers.get('Content-Range', '')
                expected = f"bytes {offset}-{saved['total'] - 1}/{saved['total']}"
                if response.status != 206 or content_range != expected or response.headers.get('ETag') != saved['etag']:
                    raise TaskFailure(DownloadError(ErrorCode.RESUME_UNSUPPORTED))
                total = saved['total']
                capability(R.YES)
            else:
                response = self.open(job.url, {'Range': 'bytes=0-0'})
                etag = response.headers.get('ETag', '')
                match = re.fullmatch(r'bytes 0-0/(\d+)', response.headers.get('Content-Range', ''))
                resumable = response.status == 206 and match and etag.startswith('"') and etag.endswith('"')
                if resumable:
                    total = int(match[1])
                    response.close()
                    response = self.open(job.url, {'If-Match': etag})
                    if response.status != 200 or response.headers.get('ETag') != etag:
                        raise TaskFailure(DownloadError(ErrorCode.RESUME_UNSUPPORTED))
                    checkpoint.write_text(json.dumps({'url_hash': fingerprint, 'etag': etag, 'total': total}))
                    capability(R.YES)
                else:
                    capability(R.NO)
                    # A 206 without strong identity is still only one byte, not a file.
                    if response.status == 206:
                        response.close()
                        response = self.open(job.url, {})
                    length = response.headers.get('Content-Length', '')
                    total = int(length) if length.isdigit() else None
            progress(offset, total)
            with partial.open('ab' if offset else 'wb') as output:
                while True:
                    control.check()
                    block = response.read(65536)
                    if not block:
                        break
                    output.write(block)
                    offset += len(block)
                    progress(offset, total)
                output.flush()
                import os
                os.fsync(output.fileno())
            control.check()
            if total is not None and offset != total:
                raise TaskFailure(DownloadError(ErrorCode.NETWORK_LOST, True))
            partial.replace(path)
            return path
        except (urllib.error.URLError, TimeoutError, ConnectionError) as exc:
            raise TaskFailure(DownloadError(ErrorCode.NETWORK_LOST, True)) from None
        finally:
            if response is not None:
                response.close()
