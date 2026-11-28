"""Load only source-built stdlib extensions from Android's installed native dir.

Android's package manager owns this directory. No dlopen from writable app data.
The libpyext_ prefix satisfies APK native library naming without changing PyInit_*.
"""
import sys
import posix
from importlib.machinery import ExtensionFileLoader, EXTENSION_SUFFIXES
from importlib.util import spec_from_file_location


class InstalledExtensions:
    def __init__(self, directory):
        self.directory = directory

    def find_spec(self, fullname, path=None, target=None):
        if not fullname.isidentifier():
            return None
        for suffix in EXTENSION_SUFFIXES:
            filename = self.directory + '/libpyext_' + fullname + suffix
            try:
                posix.stat(filename)
            except FileNotFoundError:
                continue
            return spec_from_file_location(fullname, filename,
                loader=ExtensionFileLoader(fullname, filename))
        return None


def install(directory):
    # Isolated execution must actually use the generated sealed bytecode. Keep
    # the original filesystem-based JNI laboratory path separately unchanged.
    if __file__.startswith('/proc/self/fd/3/') and not __file__.endswith('.pyc'):
        raise RuntimeError('SEALED_BYTECODE_REQUIRED')
    if not directory.startswith('/') or '/..' in directory:
        raise RuntimeError('INVALID_INSTALLED_NATIVE_DIRECTORY')
    sys._thekain_native_directory = directory
    sys.meta_path.insert(0, InstalledExtensions(directory))
