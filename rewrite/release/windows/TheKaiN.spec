# Two executables share replaceable Qt/Python DLLs in _internal (no one-file bundle).
from pathlib import Path
from PyInstaller.utils.hooks import collect_submodules, collect_data_files, copy_metadata

release = Path(SPECPATH).parent
root = release.parent / 'windows'
generated = release / 'build'
gui = Analysis([str(root / 'app.py')], pathex=[str(root)],
    datas=[(str(root / 'desktop' / 'assets'), 'desktop/assets'),
           (str(generated / 'notices'), 'notices'),
           (str(generated / 'build-info.json'), '.')],
    hiddenimports=['release_smoke'], excludes=['yt_dlp', 'PySide6.QtTest'])
worker = Analysis([str(root / 'extractor_worker.py')], pathex=[str(root)],
    hiddenimports=collect_submodules('yt_dlp'),
    datas=collect_data_files('yt_dlp') + copy_metadata('yt-dlp'), excludes=['PySide6'])
gui_exe = EXE(PYZ(gui.pure), gui.scripts, [], exclude_binaries=True,
    name='TheKaiNVideo', console=False, upx=False, icon=str(generated / 'thekain.ico'))
worker_exe = EXE(PYZ(worker.pure), worker.scripts, [], exclude_binaries=True,
    name='TheKaiNExtractor', console=True, upx=False, icon=str(generated / 'thekain.ico'))
collection = COLLECT(gui_exe, worker_exe, gui.binaries, gui.datas,
    worker.binaries, worker.datas, name='TheKaiNVideo', upx=False)
