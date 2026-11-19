"""The real file entrypoint must load package-based lab suites on native Windows."""
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


class NativeRunnerTest(unittest.TestCase):
    def test_entrypoint_imports_experiment_suites_without_cwd_or_pythonpath(self):
        script=Path(__file__).resolve().parents[1]/'release'/'run-windows-tests.py'
        # Use the actual runner and actual validator tests; narrow discovery avoids
        # recursively launching this regression as part of the child runner.
        code='''import importlib,runpy,unittest
def discover(*args,**kwargs):
    suite=unittest.TestSuite()
    for name in ("rewrite.experiments.android_native.test_isolated_report",
                 "rewrite.experiments.android_native.test_isolated_phone_report"):
        suite.addTests(unittest.defaultTestLoader.loadTestsFromModule(importlib.import_module(name)))
    return suite
unittest.defaultTestLoader.discover=discover
runpy.run_path(SCRIPT,run_name="__main__")
'''.replace('SCRIPT',repr(str(script)))
        with tempfile.TemporaryDirectory() as directory:
            out=subprocess.run([sys.executable,'-I','-c',code],cwd=directory,capture_output=True,text=True,timeout=30)
        self.assertEqual(out.returncode,0,out.stdout+out.stderr)
        self.assertIn('3 tests, 0 failures, 0 errors, 0 skipped',out.stdout)


if __name__=='__main__':unittest.main()
