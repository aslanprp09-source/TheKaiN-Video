"""Regression tests for actual API26 /proc races; no Android execution is simulated as proof."""
import importlib.util
from pathlib import Path
import subprocess
from types import SimpleNamespace
import unittest
from unittest.mock import patch

spec=importlib.util.spec_from_file_location('native_lifecycle_driver',Path(__file__).with_name('lifecycle-smoke.py'))
driver=importlib.util.module_from_spec(spec)
spec.loader.exec_module(driver)


class ProcessReadRaceTest(unittest.TestCase):
    def test_empty_or_denied_proc_read_never_counts_as_death_when_pid_still_exists(self):
        for read in (b'',subprocess.CalledProcessError(1,['adb'])):
            with self.subTest(read=type(read).__name__):
                args={'side_effect':read} if isinstance(read,Exception) else {'return_value':read}
                with patch.object(driver,'adb',**args),patch.object(driver.subprocess,'run',return_value=SimpleNamespace(returncode=0,stdout=b'/proc/200\n',stderr=b'')):
                    self.assertFalse(driver.dead(driver.proc_identity(200),1000))
                with patch.object(driver,'adb',**args),patch.object(driver.subprocess,'run',return_value=SimpleNamespace(returncode=1,stdout=b'',stderr=b'ls: /proc/200: No such file or directory')):
                    self.assertTrue(driver.dead(driver.proc_identity(200),1000))
        for code,error in ((2,b'Unknown command'),(1,b'ls: /proc/200: Permission denied')):
            with patch.object(driver,'adb',return_value=b''),patch.object(driver.subprocess,'run',return_value=SimpleNamespace(returncode=code,stdout=b'',stderr=error)):
                with self.assertRaises(AssertionError):driver.proc_identity(200)

    def test_valid_kernel_identity_distinguishes_live_zombie_and_reused_pid(self):
        for state,expected in (('S',False),('Z',True)):
            fields=[state]+['0']*18+['1000']
            with patch.object(driver,'adb',return_value=('200 (worker name) '+' '.join(fields)).encode()):
                value=driver.proc_identity(200);self.assertEqual(driver.dead(value,1000),expected)
                self.assertTrue(driver.dead(value,999))
        with patch.object(driver,'adb',return_value=b'200 (worker) S'):
            with self.assertRaises(AssertionError):driver.proc_identity(200)
        with self.assertRaises(AssertionError):driver.proc_identity('200; kill')

    def test_window_listing_and_focused_app_without_input_focus_cannot_enable_taps(self):
        own='com.thekain.runtime.lab/.LabQaActivity'
        self.assertFalse(driver.window_focused('Window{123 '+own+'}'))
        self.assertFalse(driver.window_focused('mFocusedApp=ActivityRecord{'+own+'}'))
        self.assertFalse(driver.window_focused('mFocusedApp=ActivityRecord{'+own+'}\nmCurrentFocus=Window{android/system}'))
        self.assertFalse(driver.window_focused('mFocusedApp=ActivityRecord{'+own+'}\nmCurrentFocus=Window{com.thekain.runtime.lab.test/.EmptyActivity}'))
        self.assertTrue(driver.window_focused('mFocusedApp=ActivityRecord{'+own+'}\nmCurrentFocus=Window{'+own+'}'))
