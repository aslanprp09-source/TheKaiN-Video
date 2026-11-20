"""Synthetic negative intake cases, not Android/device proof."""
import copy
import json
from pathlib import Path
import tempfile
import unittest
from .recovery_report import validate

class RecoveryReportTest(unittest.TestCase):
    def test_missing_exclusive_or_old_failure_or_owner_identity_never_passes(self):
        marker=dict(schema=2,phase='ARMED',observed_started_worker_pid=200,id='owner',owner_pid=100,owner_start=4,device=8,inode=9)
        proofs=[dict(status=1,operation_id=str(i),kernel_lock_exclusive=True,old_operation_not_rewritten=True,production_ready=False,
            device=8,inode=9,elapsed_ms=5,previous_owner_pid=100,owner_pid=101) for i in range(4)]
        proofs.append(dict(proofs[0],operation_id='owner'))
        rows=[dict(id=str(i),error=13,death_confirmed=False,operation=4) for i in range(4)]
        rows.extend(dict(id='fresh'+str(i),operation=2,error=0,death_confirmed=True,owner_canary_intact=True,child_protection=255) for i in range(5))
        variants=[None,('kernel_lock_exclusive',False),('old_operation_not_rewritten',False),('inode',0),('operation_id','unknown'),('owner_pid',100)]
        for changed in variants:
            with self.subTest(changed=changed),tempfile.TemporaryDirectory() as temp:
                folder=Path(temp);p=copy.deepcopy(proofs);r=copy.deepcopy(rows)
                if changed:p[-1][changed[0]]=changed[1]
                (folder/'lease-recovery-instrumentation.txt').write_text('OK (4 tests)\n')
                (folder/'owner-guard-recovery-instrumentation.txt').write_text('OK (1 test)\n')
                (folder/'guard-owner-before-kill.json').write_text(json.dumps(marker))
                (folder/'kernel-guard-recovery.jsonl').write_text('\n'.join(map(json.dumps,p)))
                (folder/'recovery-operation-evidence.jsonl').write_text('\n'.join(map(json.dumps,r)))
                if changed:
                    with self.assertRaises(AssertionError):validate(folder)
                else:self.assertFalse(validate(folder)['production_ready'])
