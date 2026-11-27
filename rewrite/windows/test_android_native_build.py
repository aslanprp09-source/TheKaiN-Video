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
from rewrite.experiments.android_native import test_attempt_report
from rewrite.experiments.android_native import test_diagnostic_report
from rewrite.experiments.android_native import test_completion_signal
from rewrite.experiments.android_native import test_network_policy
from rewrite.experiments.android_native import test_source_analysis
from rewrite.experiments.android_native import test_bridge_report
from rewrite.experiments.android_native import test_transfer_report
from rewrite.experiments.android_native import test_formats_report


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
    tests.addTests(loader.loadTestsFromModule(test_attempt_report))
    tests.addTests(loader.loadTestsFromModule(test_diagnostic_report))
    tests.addTests(loader.loadTestsFromModule(test_completion_signal))
    tests.addTests(loader.loadTestsFromModule(test_network_policy))
    tests.addTests(loader.loadTestsFromModule(test_source_analysis))
    tests.addTests(loader.loadTestsFromModule(test_bridge_report))
    tests.addTests(loader.loadTestsFromModule(test_transfer_report))
    tests.addTests(loader.loadTestsFromModule(test_formats_report))
    return tests
