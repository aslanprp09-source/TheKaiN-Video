import copy
import unittest
from .isolated_report import validate


class IsolatedEvidenceTest(unittest.TestCase):
    def fixture(self):
        rows=[]
        for i,error in enumerate((0,1,2,4,5,6,7,8,9,10,11,14)):
            rows.append(dict(id=str(i),error=error,death_confirmed=True,owner_canary_intact=True,retire_to_exit_ms=80,
                cancel_to_exit_ms=80 if error==1 else -1,requests=1,payload_bytes=128,
                parent_pid=100,parent_uid=10001,worker_pid=200+i,worker_uid=99000+i,
                observer_pid=400+i,observer_uid=99000+i,observer_start_ticks=50+i,observer_peak_rss_bytes=40*1024*1024,
                observer_cpu_ticks=4,observer_python_loaded=False,observer_non_dumpable=True,child_protection=255,samples=8,peak_rss_bytes=200*1024*1024,rss_limit=(64 if error==7 else 384)*1024*1024,
                start_ticks=100+i,python_entered=error!=4,worker_exit_signal=31 if error==4 else 9,credential_denial_proof=3 if error==4 else 0,rlimit_as=2**32+256*1024*1024,
                virtual_before=2**32,rlimit_cpu=20,cpu_ticks=7,observed_cpu_ms=70,
                security_denials=31,mode=2 if error in (1,2) else 7 if error==4 else 0,instance=str(i),resource_input_bytes=22*1024*1024))
        return rows

    def test_incomplete_or_contradictory_isolation_proof_is_rejected(self):
        rows=self.fixture();self.assertFalse(validate(rows)['production_ready'])
        mutations=(('owner_canary_intact',False),('observer_uid',10001),('observer_uid',98000),('observer_non_dumpable',False),('child_protection',0),('observer_pid',200),('observer_python_loaded',True),('observer_peak_rss_bytes',0),('worker_uid',10001),('worker_uid',10002),('worker_pid',100),
            ('death_confirmed',False),('samples',0),('cpu_ticks',0),('rlimit_cpu',0),
            ('security_denials',0),('retire_to_exit_ms',9999),('payload_bytes',3*1024*1024))
        for field,value in mutations:
            changed=copy.deepcopy(rows);changed[0][field]=value
            with self.subTest(field=field,value=value),self.assertRaises(AssertionError):validate(changed)
        for subset in (rows[:1],rows[1:], [r for r in rows if r['mode']!=2]):
            with self.assertRaises(AssertionError):validate(subset)

    def test_unverified_resources_or_proc_identity_must_never_enter_python(self):
        for mode in (3,4):
            rows=self.fixture();rows[5]['mode']=mode
            with self.assertRaises(AssertionError):validate(rows)
            rows[5]['python_entered']=False;validate(rows)

    def test_credential_denial_requires_measured_proof_and_matching_outcome(self):
        for proof,error,signal in ((1,6,0),(2,4,0),(3,4,31)):
            rows=self.fixture();row=copy.deepcopy(rows[3]);row.update(id='credential-extra',instance='credential-extra',
                credential_denial_proof=proof,error=error,worker_exit_signal=signal)
            rows.append(row);validate(rows)
        for proof,error,signal in ((0,4,0),(0,4,31),(1,4,0),(2,6,0),(3,4,0)):
            rows=self.fixture();rows[3].update(credential_denial_proof=proof,error=error,worker_exit_signal=signal)
            with self.subTest(proof=proof,error=error,signal=signal),self.assertRaises(AssertionError):validate(rows)


if __name__=='__main__':unittest.main()
