"""Host-only negative intake/fence checks; not execution of Android ELF."""
from rewrite.experiments.android_native import test_build
from rewrite.experiments.android_native import test_process_report
from rewrite.experiments.android_native import test_phone_report
from rewrite.experiments.android_native import test_lifecycle_driver
from rewrite.experiments.android_native import test_isolated_report
from rewrite.experiments.android_native import test_isolated_phone_report
from rewrite.experiments.android_native import test_recovery_report
from rewrite.experiments.android_native import test_recovery_phone_report
from rewrite.experiments.android_native import test_emulator_ready


def load_tests(loader,tests,pattern):
    tests.addTests(loader.loadTestsFromModule(test_build))
    tests.addTests(loader.loadTestsFromModule(test_process_report))
    tests.addTests(loader.loadTestsFromModule(test_phone_report))
    tests.addTests(loader.loadTestsFromModule(test_lifecycle_driver))
    tests.addTests(loader.loadTestsFromModule(test_isolated_report))
    tests.addTests(loader.loadTestsFromModule(test_isolated_phone_report))
    tests.addTests(loader.loadTestsFromModule(test_recovery_report))
    tests.addTests(loader.loadTestsFromModule(test_recovery_phone_report))
    tests.addTests(loader.loadTestsFromModule(test_emulator_ready))
    return tests
