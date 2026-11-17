"""CI entry point only; the desktop and Android products do not import the lab."""
from rewrite.experiments.android_ytdlp import test_spike


def load_tests(loader, tests, pattern):
    return loader.loadTestsFromModule(test_spike)
