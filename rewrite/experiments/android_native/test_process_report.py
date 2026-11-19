import copy
import unittest
from rewrite.experiments.android_native.process_report import summarize


class ProcessEvidenceTest(unittest.TestCase):
    def test_incomplete_or_fabricated_containment_evidence_is_rejected(self):
        records=[]
        for index,error in enumerate((0,1,2,4,5,7,8,9,10,11,14)):
            records.append({'id':str(index),'error':error,'operation':6 if error==7 else 1,
                'parent_pid':100,'worker_pid':200+index,'start_ticks':900+index,'death_confirmed':True,
                'requests':0,'payload_bytes':0,'virtual_before':1000,'virtual_headroom':64*1024*1024,
                'rlimit_as':1000+64*1024*1024,'rlimit_cpu':5,'kernel_socket_denied':True,
                'samples':3,'peak_rss_bytes':10000,'rss_limit':20000,'elapsed_ms':6000,
                'parent_uid':1000,'worker_uid':1000,'clock_ticks_per_second':100,'cpu_ticks':20,'observed_cpu_ms':200,
                'retire_to_exit_ms':10,'cancel_to_exit_ms':10 if error==1 else -1})
        self.assertEqual(summarize(records)['operations'],11)
        for key,value in (('worker_pid',100),('death_confirmed',False),('samples',0),
                          ('rlimit_as',1),('requests',13),('payload_bytes',2*1024*1024+2),
                          ('kernel_socket_denied',False),('retire_to_exit_ms',-1),('worker_uid',999),
                          ('clock_ticks_per_second',0),('observed_cpu_ms',201)):
            with self.subTest(field=key):
                invalid=copy.deepcopy(records);invalid[0][key]=value
                with self.assertRaises(AssertionError):summarize(invalid)
        with self.assertRaises(AssertionError):summarize(records[:-1])
        invalid=copy.deepcopy(records);invalid[1]['cancel_to_exit_ms']=-1
        with self.assertRaises(AssertionError):summarize(invalid)
