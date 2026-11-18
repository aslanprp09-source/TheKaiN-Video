"""Host-only negative intake/fence checks; not execution of Android ELF."""
from rewrite.experiments.android_native import test_build


def load_tests(loader,tests,pattern):
    return loader.loadTestsFromModule(test_build)
