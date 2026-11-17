"""A successful API35 report must retain all 41 tests and execute every one."""
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

REPORTER = Path(__file__).resolve().parents[1] / 'android/tools/report-tests.py'


class AndroidInventoryTest(unittest.TestCase):
    def run_report(self, tests=41, skipped=0, failures=0):
        with tempfile.TemporaryDirectory() as directory:
            Path(directory, 'TEST-owned.xml').write_text(
                f'<testsuite tests="{tests}" skipped="{skipped}" failures="{failures}" errors="0"/>')
            return subprocess.run([sys.executable, str(REPORTER), directory, '41'],
                                  capture_output=True, text=True, timeout=10)

    def test_complete_primary_suite_passes(self):
        result = self.run_report()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_removed_test_cannot_look_green(self):
        self.assertNotEqual(self.run_report(tests=40).returncode, 0)

    def test_skipped_test_cannot_look_green(self):
        self.assertNotEqual(self.run_report(skipped=1).returncode, 0)

    def test_failure_cannot_look_green(self):
        self.assertNotEqual(self.run_report(failures=1).returncode, 0)
