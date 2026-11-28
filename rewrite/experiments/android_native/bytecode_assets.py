"""Build-time checked-hash bytecode from verified source; never runs on Android."""
import argparse
import importlib.util
import json
from pathlib import Path
import py_compile
import sys


def compile_tree(root, expected_version):
    if sys.implementation.name != 'cpython' or tuple(sys.version_info[:3]) != tuple(expected_version):
        raise RuntimeError('BYTECODE_COMPILER_VERSION')
    root = Path(root)
    if root.is_symlink() or not root.is_dir():
        raise RuntimeError('BYTECODE_ROOT')
    entries = sorted(root.rglob('*'))
    # Never adopt supplied bytecode, traverse a symlink or overwrite generated code.
    if any(p.is_symlink() or p.suffix == '.pyc' for p in entries):
        raise RuntimeError('BYTECODE_INPUT')
    sources = [p for p in entries if p.is_file() and p.suffix == '.py']
    if not sources:
        raise RuntimeError('BYTECODE_EMPTY')
    total = 0
    for source in sources:
        raw = source.read_bytes()
        if len(raw) > 32 * 1024 * 1024:
            raise RuntimeError('BYTECODE_SOURCE_LIMIT')
        target = source.with_suffix('.pyc')
        # Adjacent legacy names are required by zipimport, rather than __pycache__.
        # Canonical filenames contain no runner/build path. No optimization removes asserts.
        py_compile.compile(str(source), cfile=str(target),
            dfile='/thekain-sealed/' + source.relative_to(root).as_posix(),
            doraise=True, optimize=0, invalidation_mode=py_compile.PycInvalidationMode.CHECKED_HASH)
        code = target.read_bytes()
        if (code[:4] != importlib.util.MAGIC_NUMBER or code[4:8] != b'\x03\0\0\0'
                or code[8:16] != importlib.util.source_hash(raw)):
            raise RuntimeError('BYTECODE_HEADER')
        total += len(code)
    return dict(compiler='source-built-pinned-CPython', python=list(expected_version),
        magic=importlib.util.MAGIC_NUMBER.hex(), invalidation='CHECKED_HASH', optimize=0,
        source_files=len(sources), bytecode_files=len(sources), bytecode_bytes=total,
        source_retained=True, android_execution='NOT_RUN')


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--root', type=Path, required=True)
    p.add_argument('--python-version', required=True)
    p.add_argument('--receipt', type=Path, required=True)
    a = p.parse_args()
    version = tuple(map(int, a.python_version.split('.')))
    if len(version) != 3:
        raise RuntimeError('BYTECODE_COMPILER_VERSION')
    receipt = compile_tree(a.root, version)
    a.receipt.write_text(json.dumps(receipt, sort_keys=True) + '\n')


if __name__ == '__main__':
    main()
