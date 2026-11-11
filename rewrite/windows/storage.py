"""No-clobber, crash-recoverable publication, including cross-volume staging."""
import errno
import os
import re
import tempfile
from pathlib import Path
from domain import DownloadError, ErrorCode
from media_tools import hash_file
from process_runner import TaskFailure


def safe_filename(title):
    name = re.sub(r'[<>:"/\\|?*\x00-\x1f\x7f]', '_', title).strip(' .')[:100].rstrip(' .') or 'video'
    if name.split('.')[0].upper() in {'CON', 'PRN', 'AUX', 'NUL', *(f'COM{i}' for i in range(1, 10)), *(f'LPT{i}' for i in range(1, 10))}:
        name = '_' + name
    return name


def matches(path, size, digest, control):
    path = Path(path)
    return (not path.is_symlink() and path.is_file() and path.stat().st_size == size
            and hash_file(path, control) == digest)


class StoragePublisher:
    def publish(self, job, repository, control):
        folder = job.destination
        folder.mkdir(parents=True, exist_ok=True)
        source = job.artifact
        # Intent is persisted BEFORE the atomic filesystem operation. A restart can
        # recognize our exact bytes at the intended target and avoid duplication.
        if job.output and matches(job.output, job.verified_bytes, job.digest, control):
            return job.output
        if not source or not matches(source, job.verified_bytes, job.digest, control):
            raise TaskFailure(DownloadError(ErrorCode.VERIFY_FAILED))
        for index in range(10000):
            control.check()
            suffix = '' if index == 0 else f' ({index})'
            target = folder / f'{safe_filename(job.title)}{suffix}.{job.variant.final_extension}'
            if target.exists() or target.is_symlink():
                continue
            repository.update(job.id, output=target)
            try:
                os.link(source, target)
            except FileExistsError:
                continue
            except OSError as exc:
                if exc.errno != errno.EXDEV:
                    raise
                descriptor, temp_name = tempfile.mkstemp(dir=folder, prefix=f'.thekain-{job.id}-', suffix='.publishing')
                temporary = Path(temp_name)
                try:
                    with os.fdopen(descriptor, 'wb') as dst, source.open('rb') as src:
                        while block := src.read(1024 * 1024):
                            control.check()
                            dst.write(block)
                        dst.flush()
                        os.fsync(dst.fileno())
                    if not matches(temporary, job.verified_bytes, job.digest, control):
                        raise TaskFailure(DownloadError(ErrorCode.VERIFY_FAILED))
                    try:
                        os.link(temporary, target)
                    except FileExistsError:
                        continue
                finally:
                    temporary.unlink(missing_ok=True)
            # Persist file/directory metadata where supported. Never overwrite target.
            with target.open('r+b') as stream:
                os.fsync(stream.fileno())
            if os.name != 'nt':
                descriptor = os.open(folder, os.O_RDONLY)
                try:
                    os.fsync(descriptor)
                finally:
                    os.close(descriptor)
            return target
        raise TaskFailure(DownloadError(ErrorCode.PERMISSION_DENIED))
