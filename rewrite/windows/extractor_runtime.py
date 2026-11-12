"""The frozen GUI launches a console worker with redirected pipes, never itself."""
import importlib.util
import sys
from pathlib import Path


def extractor_command():
    if getattr(sys, 'frozen', False):
        command = [str(Path(sys.executable).with_name('TheKaiNExtractor.exe'))]
    else:
        command = [sys.executable, '-m', 'yt_dlp']
    return command + ['--encoding', 'utf-8']  # Machine protocol is independent of Windows ANSI locale.


def extractor_available():
    if getattr(sys, 'frozen', False):
        return Path(extractor_command()[0]).is_file()
    return importlib.util.find_spec('yt_dlp') is not None
