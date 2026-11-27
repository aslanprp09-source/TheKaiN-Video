from copy import deepcopy
import unittest
from rewrite.experiments.android_native import test_bridge_report
from rewrite.experiments.android_native.transfer_report import inspect


class TransferIntakeTest(unittest.TestCase):
    def fixture(self):
        _,original,_,source=test_bridge_report.BridgeIntakeTest().fixture()
        serial=0
        def process():
            nonlocal serial
            serial+=1
            p=deepcopy(original['public']['runtime']['network'])
            p.update(id=f'{serial:08x}-0000-0000-0000-000000000000',instance=f'{serial:08x}-0000-0000-0000-000000000000',transfer_only=True,transfer_receipt=True)
            return p
        def row(name,count,flags):
            return dict(case=name,source_commit=source,api=36,pages=16384,production_ready=False,proofs=[process() for _ in range(count)],**{f:True for f in flags})
        remote=dict(stream=row('stream',2,('exact_bytes','bounded_pipe','fresh','history_immutable')),
            security=row('security',20,('scope_redirect','framing','size','truncation','http_errors','tls','fresh_after_each')),
            lifecycle=row('lifecycle',4,('actual_stuck_dns','cancel','deadline','explicit_failure','fresh_after_each')),
            boundary=row('boundary',2,('capability','file_fd_refused','analysis_grant_refused')))
        remote['stream']['bytes']=2*1024*1024+17;remote['lifecycle']['proofs'][2]['failure']=2
        main=dict(verification=row('verification',2,('corrupt_refused','receipt_not_completion','retry_fresh')),
            lifecycle=row('lifecycle',4,('cancel','deadline','actual_http','no_late_writes','fresh_after_each')),
            storage=row('storage',2,('no_space','callback_storage_error','scope_refused','direct_downgrade_refused','fixture_alias_refused')),
            migration=row('migration',0,('v1_history_preserved','old_backend_direct','new_backend_durable')),
            ui=row('ui',1,('actual_main_ui','playback','durable_history','open_action')))
        main['verification']['verified_bytes']=2428;main['lifecycle']['proofs'][2]['failure']=2
        main['ui'].update(verified_bytes=2428,sha256='dd201a3b814b113ca98749902195b996a1e2f608247bdd22d2044b4c0dfd5cb2',isolated_python=original['public']['runtime'])
        return remote,main,source

    def test_no_pipeline_pass_without_real_receipt_playback_migration_and_death(self):
        remote,main,source=self.fixture();self.assertEqual(inspect(remote,main,source,36,16384)['additional_tests'],9)
        for bad in ('missing','source','pages','pipe','binder_only','stale','history','not_transfer','no_deadline','no_death','hash','not_played','not_ui','receipt','migration','downgrade','alias','callback_storage','corrupt_success','python_uid','http','truncated'):
            r,m=deepcopy(remote),deepcopy(main)
            if bad=='missing':del m['ui']
            elif bad=='source':m['ui']['source_commit']='e'*40
            elif bad=='pages':r['stream']['pages']=4096
            elif bad=='pipe':r['stream']['bounded_pipe']=False
            elif bad=='binder_only':r['stream']['proofs'][0]['kernel_process_gone']=False
            elif bad=='stale':r['stream']['proofs'][1]=deepcopy(r['stream']['proofs'][0])
            elif bad=='history':r['stream']['history_immutable']=False
            elif bad=='not_transfer':m['ui']['proofs'][0]['transfer_only']=False
            elif bad=='no_deadline':m['lifecycle']['proofs'][2]['failure']=0
            elif bad=='no_death':m['lifecycle']['proofs'][2]['lease_clean']=False
            elif bad=='hash':m['ui']['sha256']='0'*64
            elif bad=='not_played':m['ui']['playback']=False
            elif bad=='not_ui':m['ui']['actual_main_ui']=False
            elif bad=='receipt':m['ui']['proofs'][0]['transfer_receipt']=False
            elif bad=='migration':m['migration']['v1_history_preserved']=False
            elif bad=='downgrade':m['storage']['direct_downgrade_refused']=False
            elif bad=='alias':m['storage']['fixture_alias_refused']=False
            elif bad=='callback_storage':m['storage']['callback_storage_error']=False
            elif bad=='corrupt_success':m['verification']['receipt_not_completion']=False
            elif bad=='python_uid':m['ui']['isolated_python']['worker_uid']=10001
            elif bad=='http':r['security']['http_errors']=False
            elif bad=='truncated':r['security']['proofs'].pop()
            with self.subTest(bad=bad),self.assertRaises((AssertionError,KeyError)):inspect(r,m,source,36,16384)
