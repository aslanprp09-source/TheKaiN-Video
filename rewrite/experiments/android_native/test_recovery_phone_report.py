"""Synthetic recovery-field intake cases only."""
import copy
import unittest
from .recovery_phone_report import inspect_trials

class RecoveryPhoneTest(unittest.TestCase):
    def test_missing_actual_exclusive_or_recovery_or_cleanup_is_rejected(self):
        fresh=dict(id='new',error=0,death_confirmed=True,owner_canary_intact=True,python_calls=1,formats=1,guard_files=0,
            main_python_loaded=False,worker_uid=99000,observer_uid=99000,worker_pid=300,observer_pid=200)
        first=dict(trigger='supervisor_loss',status=1,kernel_lock_exclusive=True,old_operation_not_rewritten=True,production_ready=False,
            operation_id='old1',inode=7,device=8,elapsed_ms=9,previous_owner_pid=100,owner_pid=100,parent_uid=10001,
            original_error=13,original_death_confirmed=False,fresh_analysis=fresh)
        second=dict(first,trigger='owner_restart',operation_id='old2',previous_owner_pid=99)
        report=dict(lost_owner_recovery='EXPLICIT_KERNEL_GUARD_RECOVERY_OR_FAIL_CLOSED',recovery_trials=[first,second])
        self.assertFalse(inspect_trials(report)['production_ready'])
        for key,value in [('kernel_lock_exclusive',False),('old_operation_not_rewritten',False),('original_death_confirmed',True),('inode',0)]:
            changed=copy.deepcopy(report);changed['recovery_trials'][0][key]=value
            with self.subTest(key=key),self.assertRaises(AssertionError):inspect_trials(changed)
        changed=copy.deepcopy(report);changed['recovery_trials'][1]['previous_owner_pid']=100
        with self.assertRaises(AssertionError):inspect_trials(changed)
        changed=copy.deepcopy(report);changed['recovery_trials'][1]['fresh_analysis']['guard_files']=1
        with self.assertRaises(AssertionError):inspect_trials(changed)
