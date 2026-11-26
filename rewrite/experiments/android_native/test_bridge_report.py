from copy import deepcopy
import unittest
from rewrite.experiments.android_native.bridge_report import inspect


class BridgeIntakeTest(unittest.TestCase):
    def fixture(self):
        source='d'*40;serial=0
        def process(error=0):
            nonlocal serial
            serial+=1;identifier=f'{serial:08x}-0000-0000-0000-000000000000'
            return dict(id=identifier,pid=400+serial,owner_pid=100,start_ticks=100+serial,instance=identifier,
                kernel_process_gone=True,binder_dead=True,lease_clean=True,terminated=True,service_absent=True,
                owner_python_loaded=False,ordinary_uid=10001,production_ready=False,retire_ms=100,peak_rss_bytes=32*1024*1024,observed_cpu_ms=1100,failure=error,
                rss_source='kernel_VmRSS_kB_x_1024',requests=1)
        def row(error=0):
            network=process()
            return dict(id=network['id'],source_commit=source,death_confirmed=True,canary_intact=True,worker_pid=network['pid']+1000,
                worker_uid=99001,owner_uid=10001,child_protection=255,observer_python_loaded=False,download_enabled=False,production_ready=False,
                peak_rss_bytes=64*1024*1024,observed_cpu_ms=100,network=network,error=error,formats=1,python_calls=1,machine='x86_64',pages=16384)
        remote={name:dict(case=name,source_commit=source,api=36,page_size=16384,production_ready=False,proofs=[]) for name in ('ipc','dns','resources','public','schema')}
        remote['ipc'].update(proofs=[process(),process()],chunk_size=32768,capability_refused=True,replay_refused=True,redirect_refused=True,body_limit=True)
        remote['dns'].update(proofs=[process(code) for code in (0,0,2,0)],actual_stuck_dns=True,cancel=True,deadline=True,fresh_after_each=True)
        remote['resources'].update(proofs=[process(code) for code in (4,0,15,0,7,0)],crash=True,cpu_measured=True,rss_measured=True,fresh_after_each=True)
        remote['public'].update(proofs=[process()],system_tls=True,fixture_only=False,bytes=2428,sha256='dd201a3b814b113ca98749902195b996a1e2f608247bdd22d2044b4c0dfd5cb2')
        remote['schema'].update(oversize_rejected=True,typed_metadata=True,non_exported=True)
        main={name:dict(case=name,source_commit=source,api=36,pages=16384,production_ready=False) for name in ('public','cancel','security','early','ui')}
        main['public'].update(actual_graph=True,typed_formats=True,unknowns_preserved=True,download_blocked=True,direct_preserved=True,runtime=row())
        main['cancel'].update(ui_cancel=True,ui_deadline=True,actual_results=True,fresh_after_each=True,submission_deadline_5s=True,default_https_deadline=True,
            network_request_observed=True,short_budget_ms=5000,short_elapsed_ms=5001,network_budget_ms=30000,network_elapsed_ms=30001,
            proofs=[row(code) for code in (1,0,2,0,2,0)])
        main['security'].update(scope_refused=True,tls_refused=True,fresh=True,tls_runtime=row(8),runtime=row())
        main['early'].update(verified_asset_cancel=True,temporary_files=0,actual_error=1,python_entered=False,death_confirmed=True,fresh=row())
        main['ui'].update(actual_main_ui=True,download_disabled=True,runtime=row())
        recovery=process();recovery.update(source_commit=source,recovered=True,old_result_not_fabricated=True,old_network_pid=300,old_owner_pid=99)
        return remote,main,recovery,source

    def test_no_main_success_without_current_source_actual_death_distinct_uids_and_real_outcomes(self):
        remote,main,recovery,source=self.fixture();self.assertEqual(inspect(remote,main,recovery,source,36,16384)['main_tests'],5)
        for change in ('missing','source','pages','only_binder','lease','service','rss_unit','same_pid','same_uid','network_id','cached_process','no_deadline_result','wrong_deadline','short_budget','network_budget','network_not_observed','early_network_timeout','over_short_budget','over_network_budget','enabled','tls','recovery','not_actual_ui'):
            r,m,c=deepcopy(remote),deepcopy(main),deepcopy(recovery)
            if change=='missing':del m['ui']
            elif change=='source':m['public']['source_commit']='e'*40
            elif change=='pages':m['public']['pages']=4096
            elif change=='only_binder':m['public']['runtime']['network']['kernel_process_gone']=False
            elif change=='lease':m['public']['runtime']['network']['lease_clean']=False
            elif change=='service':m['public']['runtime']['network']['service_absent']=False
            elif change=='rss_unit':m['public']['runtime']['network']['rss_source']='assumed_page_size'
            elif change=='same_pid':m['public']['runtime']['network']['pid']=m['public']['runtime']['network']['owner_pid']
            elif change=='same_uid':m['public']['runtime']['worker_uid']=m['public']['runtime']['owner_uid']
            elif change=='network_id':m['public']['runtime']['network']['id']='a'*36
            elif change=='cached_process':r['dns']['proofs'][1]=deepcopy(r['dns']['proofs'][0])
            elif change=='no_deadline_result':m['cancel']['proofs'].pop(2)
            elif change=='wrong_deadline':m['cancel']['proofs'][2]['error']=4
            elif change=='short_budget':m['cancel']['short_budget_ms']=10000
            elif change=='network_budget':m['cancel']['network_budget_ms']=60000
            elif change=='network_not_observed':m['cancel']['network_request_observed']=False
            elif change=='early_network_timeout':m['cancel']['network_elapsed_ms']=3000
            elif change=='over_short_budget':m['cancel']['short_elapsed_ms']=10000
            elif change=='over_network_budget':m['cancel']['network_elapsed_ms']=60000
            elif change=='enabled':m['public']['runtime']['download_enabled']=True
            elif change=='tls':m['security']['tls_runtime']['error']=0
            elif change=='recovery':c['old_result_not_fabricated']=False
            elif change=='not_actual_ui':m['ui']['actual_main_ui']=False
            with self.subTest(change=change),self.assertRaises((AssertionError,KeyError)):inspect(r,m,c,source,36,16384)
