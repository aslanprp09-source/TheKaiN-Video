from copy import deepcopy
import unittest
from rewrite.experiments.android_native import test_bridge_report
from rewrite.experiments.android_native.readiness_report import inspect


class ReadinessIntakeTest(unittest.TestCase):
    def fixture(self):
        _,main,_,source=test_bridge_report.BridgeIntakeTest().fixture();serial=0
        def row(name):
            nonlocal serial
            result=dict(case=name,source_commit=source,api=36,pages=16384,production_ready=False,proofs=[])
            for i in range(6):
                serial+=1;p=deepcopy(main['public']['runtime']['network'])
                p.update(id=f'{serial:08x}-0000-0000-0000-000000000000',instance=f'{serial:08x}-0000-0000-0000-000000000000',
                    ready_notification_seen=True,configure_returned=True,ready_published=True,configure_failure='NONE',transfer_only=False,
                    failure=4 if name=='crash' and i%2==0 else 0)
                result['proofs'].append(p)
            return result
        barrier=row('barrier');barrier.update(actual_binder_callback_held=True,actual_tls_after_return=True,no_early_network=True,fresh_after_each=True,held=[])
        for proof in barrier['proofs'][::2]:
            held=deepcopy(proof);held.update(configure_returned=False,ready_published=False,requests=0,terminated=False,handshake_phase='CONFIGURE_ACK',
                kernel_process_gone=False,binder_dead=False,lease_clean=False)
            barrier['held'].append(held)
        crash=row('crash');crash.update(actual_crashes=3,exact_worker_died=True,fresh_after_each=True)
        return barrier,crash,source

    def test_no_readiness_pass_without_real_pending_configure_crash_codes_and_cleanup(self):
        barrier,crash,source=self.fixture();self.assertEqual(inspect(barrier,crash,source,36,16384)['additional_tests'],2)
        for bad in ('source','pages','missing','not_held','early_ready','returned_while_held','early_request','stale_pending','not_returned',
                    'not_published','ipc_instead_of_death','fake_crash','repeated_proof','binder_only','lease','service','not_fresh','failed_configure','held_dead','held_binder_dead','held_lease_clean'):
            b,c=deepcopy(barrier),deepcopy(crash)
            if bad=='source':b['source_commit']='e'*40
            elif bad=='pages':c['pages']=4096
            elif bad=='missing':b['proofs'].pop()
            elif bad=='not_held':b['actual_binder_callback_held']=False
            elif bad=='early_ready':b['held'][0]['ready_published']=True
            elif bad=='returned_while_held':b['held'][0]['configure_returned']=True
            elif bad=='early_request':b['held'][0]['requests']=1
            elif bad=='stale_pending':b['held'][0]['id']='e'*36
            elif bad=='not_returned':b['proofs'][0]['configure_returned']=False
            elif bad=='not_published':b['proofs'][0]['ready_published']=False
            elif bad=='ipc_instead_of_death':c['proofs'][0]['failure']=5
            elif bad=='fake_crash':c['actual_crashes']=0
            elif bad=='repeated_proof':c['proofs'][1]=deepcopy(c['proofs'][0])
            elif bad=='binder_only':c['proofs'][0]['kernel_process_gone']=False
            elif bad=='lease':c['proofs'][0]['lease_clean']=False
            elif bad=='service':c['proofs'][0]['service_absent']=False
            elif bad=='not_fresh':c['fresh_after_each']=False
            elif bad=='failed_configure':b['proofs'][0]['configure_failure']='DEAD_OBJECT'
            elif bad=='held_dead':b['held'][0]['kernel_process_gone']=True
            elif bad=='held_binder_dead':b['held'][0]['binder_dead']=True
            elif bad=='held_lease_clean':b['held'][0]['lease_clean']=True
            with self.subTest(bad=bad),self.assertRaises((AssertionError,KeyError)):
                inspect(b,c,source,36,16384)
