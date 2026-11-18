"""A successful API35 report must retain all 41 tests and execute every one."""
from pathlib import Path
import subprocess
import os
import sys
import tempfile
import unittest

REPORTER = Path(__file__).resolve().parents[1] / 'android/tools/report-tests.py'


class AndroidInventoryTest(unittest.TestCase):
    @unittest.skipUnless(sys.platform.startswith('linux'), 'Hosted emulator collector is a Linux script')
    def test_system_focus_is_preserved_when_private_app_evidence_is_gone(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);adb=root/'adb'
            adb.write_text('''#!/usr/bin/env python3
import sys
if 'run-as' in sys.argv: raise SystemExit(1)
if 'window' in sys.argv: print('mCurrentFocus=Window{fixture android/system-dialog}\\nmFocusedApp=ActivityRecord{fixture}')
elif 'lastanr' in sys.argv: print('ANR in com.android.systemui: own collector fixture')
else: print('owned fixture output')
''')
            adb.chmod(0o755)
            script=REPORTER.with_name('collect-ui-failure-evidence.sh')
            result=subprocess.run(['bash',str(script),str(root/'evidence')],env={**os.environ,
                'PATH':str(root)+os.pathsep+os.environ['PATH'],'ANDROID_SERIAL':'emulator-test'},
                capture_output=True,text=True,timeout=10)
            self.assertEqual(result.returncode,0,result.stderr)
            self.assertIn('mCurrentFocus=Window{fixture android/system-dialog}',result.stdout)
            self.assertIn('ANR in com.android.systemui',result.stdout)
            self.assertTrue((root/'evidence/post-screen.png').is_file())

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
