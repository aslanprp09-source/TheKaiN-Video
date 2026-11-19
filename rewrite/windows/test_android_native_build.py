"""Host-only negative intake/fence checks; not execution of Android ELF."""
from rewrite.experiments.android_native import test_build
from rewrite.experiments.android_native import test_process_report
from rewrite.experiments.android_native import test_phone_report


def load_tests(loader,tests,pattern):
    tests.addTests(loader.loadTestsFromModule(test_build))
    tests.addTests(loader.loadTestsFromModule(test_process_report))
    tests.addTests(loader.loadTestsFromModule(test_phone_report))
    return tests
