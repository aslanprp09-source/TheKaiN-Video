"""Synthetic validator fixtures, not physical-device measurements."""
import copy
import unittest
from .isolated_phone_report import inspect


class IsolatedPhoneIntakeTest(unittest.TestCase):
    def test_synthetic_fixture_requires_review_and_rejects_wrong_build_uid_or_missing_recovery(self):
        rows=[]
        for i,(operation,mode,error) in enumerate(((1,0,0),(1,1,0),(4,2,1),(4,2,2),(5,0,4),(6,0,7),(1,4,6),(1,3,5),*((2,0,0),)*5)):
            rows.append(dict(id=str(i),operation=operation,mode=mode,error=error,expected_error=error,
                worker_pid=200+i,worker_uid=99000+i,parent_pid=100,parent_uid=10001,start_ticks=1000+i,
                instance=str(i),samples=4,peak_rss_bytes=200*1024*1024,observed_cpu_ms=80,
                virtual_before=2**32,rlimit_as=2**32+256*1024*1024,rlimit_cpu=20,
                death_confirmed=True,main_python_loaded=False,python_entered=mode not in (3,4),
                python_calls=1,security_denials=255 if mode==1 else 31,requests=1,payload_bytes=128,
                machine='aarch64',pointer_bits=64,pages=4096,openssl_handshake=True,retire_to_exit_ms=80,
                resource_input_bytes=22*1024*1024,cpu_ticks=8,clock_ticks_per_second=100))
        receipt=dict(repository_commit='a'*40,apk_sha256={'lab.apk':'b'*64},debug_certificate_sha256='c'*64)
        report=dict(schema=1,boundary='android_isolated_uid',source_commit='a'*40,production_ready=False,
            product_integration=False,physical_device_attested=False,qa89_review='BLOCKED_SEPARATE_TASK',
            rss_is_instantaneous=False,device=dict(apk_sha256='b'*64,certificate_sha256='c'*64,sdk=37,
                process_64bit=True,abis=['arm64-v8a'],hardware='synthetic-test-fixture',fingerprint='synthetic/test'),cases=rows)
        self.assertEqual(inspect(report,receipt)['status'],'OPERATOR_REPORT_REQUIRES_REVIEW')
        for change in ('source','uid','cleanup','reused','missing_recovery','emulator','main_python','unexpected_failure'):
            changed=copy.deepcopy(report)
            if change=='source':changed['source_commit']='d'*40
            elif change=='uid':changed['cases'][0]['worker_uid']=10001
            elif change=='cleanup':changed['cases'][0]['death_confirmed']=False
            elif change=='reused':changed['cases'][-1]['instance']='0'
            elif change=='missing_recovery':changed['cases'][-1]['operation']=1
            elif change=='emulator':changed['device']['hardware']='ranchu'
            elif change=='main_python':changed['cases'][0]['main_python_loaded']=True
            else:changed['cases'][0]['error']=4
            with self.subTest(change=change),self.assertRaises(AssertionError):inspect(changed,receipt)


if __name__=='__main__':unittest.main()
