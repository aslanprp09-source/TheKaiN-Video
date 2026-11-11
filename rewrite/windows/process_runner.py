"""Cancelable process trees with bounded UTF-8 output; never run a shell."""
import os
import signal
import subprocess
import threading
import time
from dataclasses import dataclass
from domain import DownloadError, ErrorCode


class TaskFailure(Exception):
    def __init__(self, error):
        self.error = error
        super().__init__(error.message)


class TaskStopped(Exception):
    def __init__(self, reason):
        self.reason = reason
        super().__init__(reason)


class Control:
    def __init__(self):
        self.event = threading.Event()
        self.lock = threading.Lock()
        self.reason = 'canceled'

    def stop(self, reason='canceled'):
        with self.lock:
            if not self.event.is_set() or reason == 'canceled':
                self.reason = reason
                self.event.set()

    def check(self):
        if self.event.is_set():
            raise TaskStopped(self.reason)


@dataclass(frozen=True)
class ProcessResult:
    returncode: int
    stdout: str
    stderr: str


def terminate_tree(proc):
    if os.name == 'nt':
        tool = os.path.join(os.environ.get('SystemRoot', r'C:\Windows'), 'System32', 'taskkill.exe')
        subprocess.run([tool, '/PID', str(proc.pid), '/T', '/F'], stdout=subprocess.DEVNULL,
                       stderr=subprocess.DEVNULL, timeout=5, check=False)
    else:
        try:
            os.killpg(proc.pid, signal.SIGTERM)
        except ProcessLookupError:
            pass
    try:
        proc.wait(timeout=1)
    except subprocess.TimeoutExpired:
        if os.name != 'nt':
            try:
                os.killpg(proc.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
        else:
            proc.kill()
        proc.wait()
    if os.name != 'nt':
        # The parent may have exited while a descendant still owns a pipe.
        try:
            os.killpg(proc.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass


def run_process(args, control, timeout=300, on_line=None, limit=2 * 1024 * 1024):
    control.check()
    flags = {'start_new_session': True} if os.name != 'nt' else {'creationflags': subprocess.CREATE_NO_WINDOW}
    try:
        proc = subprocess.Popen(list(args), stdout=subprocess.PIPE, stderr=subprocess.PIPE, shell=False, **flags)
    except FileNotFoundError:
        raise TaskFailure(DownloadError(ErrorCode.TOOL_MISSING)) from None
    errors, buffers = [], [bytearray(), bytearray()]

    def reader(stream, index):
        pending = bytearray()
        try:
            while chunk := stream.read1(4096):
                if index == 0 and on_line:
                    pending.extend(chunk)
                    if len(pending) > 65536:
                        raise ValueError('Oversized subprocess line')
                    while b'\n' in pending:
                        line, _, rest = pending.partition(b'\n')
                        pending[:] = rest
                        on_line(line.decode('utf-8', 'replace'))
                else:
                    if len(buffers[index]) + len(chunk) > (limit if index == 0 else 65536):
                        raise ValueError('Oversized subprocess output')
                    buffers[index].extend(chunk)
            if pending and on_line:
                on_line(pending.decode('utf-8', 'replace'))
        except Exception as exc:
            errors.append(exc)

    readers = [threading.Thread(target=reader, args=(stream, i), daemon=True)
               for i, stream in enumerate((proc.stdout, proc.stderr))]
    for thread in readers:
        thread.start()
    deadline = time.monotonic() + timeout
    try:
        while proc.poll() is None:
            control.check()
            if errors:
                raise errors[0]
            if time.monotonic() >= deadline:
                raise TaskFailure(DownloadError(ErrorCode.TIMEOUT, True))
            control.event.wait(.025)
        for thread in readers:
            thread.join(timeout=2)
        control.check()
        if errors:
            raise errors[0]
        if any(thread.is_alive() for thread in readers):
            raise TaskFailure(DownloadError(ErrorCode.UNKNOWN))
        return ProcessResult(proc.returncode, *(bytes(b).decode('utf-8', 'replace') for b in buffers))
    finally:
        if proc.poll() is None or any(t.is_alive() for t in readers):
            terminate_tree(proc)
        for thread in readers:
            thread.join(timeout=2)
        proc.stdout.close()
        proc.stderr.close()
