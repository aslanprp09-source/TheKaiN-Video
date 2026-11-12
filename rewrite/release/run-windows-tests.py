"""Run the actual desktop suite and retain useful CI annotations on restricted hosts."""
from pathlib import Path
import sys
import unittest


class Result(unittest.TextTestResult):
    def addError(self, test, error):
        super().addError(test, error)
        self.annotate(test, self.errors[-1][1])

    def addFailure(self, test, error):
        super().addFailure(test, error)
        self.annotate(test, self.failures[-1][1])

    def annotate(self, test, details):
        value = (str(test) + '\n' + details)[-3000:]
        print('::error title=Native desktop test::' + value.replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A'))


root = Path(__file__).parents[1] / 'windows'
sys.path.insert(0, str(root))
suite = unittest.defaultTestLoader.discover(str(root), pattern='test_*.py')
result = unittest.TextTestRunner(verbosity=2, resultclass=Result).run(suite)
print(f'::notice title=Desktop test report::{result.testsRun} tests, {len(result.failures)} failures, {len(result.errors)} errors, {len(result.skipped)} skipped')
raise SystemExit(not result.wasSuccessful())
