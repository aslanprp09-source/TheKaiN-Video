"""Missing, skipped or aborted device results cannot pass the new additional gate."""
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('direct_report',
    Path(__file__).resolve().parents[1] / 'android/tools/report-direct-reliability.py')
report = importlib.util.module_from_spec(spec)
spec.loader.exec_module(report)


class DirectReliabilityReportTest(unittest.TestCase):
    def test_all_executed_tests_are_required(self):
        complete = 'INSTRUMENTATION_STATUS_CODE: 0\n' * 3 + '\nOK (3 tests)\n'
        self.assertTrue(report.verify(complete))
        for broken in ('', 'OK (3 tests)\n', complete.replace('3 tests', '2 tests'),
                       complete + 'FAILURES!!!', complete + 'shortMsg=Process crashed',
                       complete + 'INSTRUMENTATION_ABORTED',
                       complete.replace('STATUS_CODE: 0', 'STATUS_CODE: -3', 1)):
            with self.subTest(output=broken):
                self.assertFalse(report.verify(broken))
