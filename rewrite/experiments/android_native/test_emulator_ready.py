"""Image readiness cannot silently pass an ongoing pressure burst."""
import unittest
from .emulator_ready import wait_quiet


class EmulatorReadyTest(unittest.TestCase):
    def test_new_pressure_restarts_quiet_window_and_permanent_pressure_fails(self):
        clock=[0]
        def now():return clock[0]
        def pause(seconds):clock[0]+=seconds
        def burst():return b'old' if clock[0]<4 else b'new kill'
        result=wait_quiet(burst,now,pause)
        self.assertEqual(result['wait_ms'],9000)
        self.assertEqual(result['quiet_ms'],5000)
        clock[0]=0
        with self.assertRaisesRegex(TimeoutError,'DID_NOT_SETTLE'):
            wait_quiet(lambda:str(clock[0]).encode(),now,pause)
        self.assertEqual(clock[0],120)
