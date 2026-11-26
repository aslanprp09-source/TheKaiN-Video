"""Fail-closed intake for remote-network and real main-APK integration; never substitutes for original73 gates."""
import argparse
import json
from pathlib import Path
import re

REMOTE=('ipc','dns','resources','public','schema')
MAIN=('public','cancel','security','early','ui')


def process(proof,source):
    assert re.fullmatch('[0-9a-f-]{36}',proof['id']) and proof['pid']>0 and proof['pid']!=proof['owner_pid'] and proof['start_ticks']>0 and re.fullmatch('[0-9a-f-]{36}',proof['instance'])
    assert proof['kernel_process_gone'] is True and proof['binder_dead'] is True and proof['lease_clean'] is True and proof['terminated'] is True
    assert proof['owner_python_loaded'] is False and proof['production_ready'] is False
    assert proof['service_absent'] is True
    assert 0<=proof['retire_ms']<=2500 and proof['peak_rss_bytes']>0 and proof['observed_cpu_ms']>=0


def runtime(row,source):
    assert row['source_commit']==source and row['death_confirmed'] is True and row['canary_intact'] is True
    assert row['worker_pid']>0 and row['worker_uid']!=row['owner_uid'] and row['child_protection']==255
    assert row['observer_python_loaded'] is False and row['download_enabled'] is False and row['production_ready'] is False
    assert row['peak_rss_bytes']>0 and row['observed_cpu_ms']>=0
    network=row['network'];process(network,source);assert network['id']==row['id'] and network['pid']!=row['worker_pid']
    assert network['ordinary_uid']==row['owner_uid']


def inspect(remote,main,recovery,source,api,pages):
    assert re.fullmatch('[0-9a-f]{40}',source) and set(remote)==set(REMOTE) and set(main)==set(MAIN)
    for name,data in remote.items():
        assert data['case']==name and data['source_commit']==source and data['api']==api and data['page_size']==pages and data['production_ready'] is False
        for row in data['proofs']:process(row,source)
        assert len({row['id'] for row in data['proofs']})==len(data['proofs'])
        assert len({row['instance'] for row in data['proofs']})==len(data['proofs'])
    assert remote['ipc']['chunk_size']==32768 and all(remote['ipc'][k] is True for k in ('capability_refused','replay_refused','redirect_refused','body_limit'))
    assert all(remote['dns'][k] is True for k in ('actual_stuck_dns','cancel','deadline','fresh_after_each'))
    assert len(remote['dns']['proofs'])==4
    assert [r['failure'] for r in remote['dns']['proofs']]==[0,0,2,0] # Explicit close is cancellation, not an invented successful DNS completion.
    assert all(remote['resources'][k] is True for k in ('crash','cpu_measured','rss_measured','fresh_after_each'))
    assert len(remote['resources']['proofs'])==6 and [r['failure'] for r in remote['resources']['proofs']]==[4,0,15,0,7,0]
    assert remote['resources']['proofs'][2]['observed_cpu_ms']>1000 and remote['resources']['proofs'][4]['peak_rss_bytes']>16*1024*1024
    assert remote['public']['system_tls'] is True and remote['public']['fixture_only'] is False and remote['public']['bytes']==2428
    assert remote['public']['sha256']=='dd201a3b814b113ca98749902195b996a1e2f608247bdd22d2044b4c0dfd5cb2'
    assert all(remote['schema'][k] is True for k in ('oversize_rejected','typed_metadata','non_exported'))
    process(recovery,source);assert recovery['source_commit']==source and recovery['recovered'] is True and recovery['old_result_not_fabricated'] is True
    assert recovery['old_network_pid']>0 and recovery['old_owner_pid']>0
    for name,data in main.items():
        assert data['case']==name and data['source_commit']==source and data['api']==api and data['pages']==pages and data['production_ready'] is False
    assert all(main['public'][k] is True for k in ('actual_graph','typed_formats','unknowns_preserved','download_blocked','direct_preserved'))
    runtime(main['public']['runtime'],source);assert main['public']['runtime']['error']==0 and main['public']['runtime']['formats']==1
    assert main['public']['runtime']['python_calls']==1 and main['public']['runtime']['machine']=='x86_64' and main['public']['runtime']['pages']==pages
    assert all(main['cancel'][k] is True for k in ('ui_cancel','ui_deadline','actual_results','fresh_after_each'))
    attempts=main['cancel']['proofs'];assert len(attempts)==4
    for row in attempts:runtime(row,source)
    assert attempts[0]['error']==1 and attempts[1]['error']==0 and attempts[2]['error'] in (1,2) and attempts[3]['error']==0
    assert len({row['id'] for row in attempts})==4
    assert all(main['security'][k] is True for k in ('scope_refused','tls_refused','fresh'))
    runtime(main['security']['tls_runtime'],source);assert main['security']['tls_runtime']['error']==8
    runtime(main['security']['runtime'],source);assert main['security']['runtime']['error']==0
    early=main['early'];assert early['verified_asset_cancel'] is True and early['temporary_files']==0 and early['actual_error']==1
    assert early['python_entered'] is False and early['death_confirmed'] is True
    runtime(early['fresh'],source);assert early['fresh']['error']==0
    assert main['ui']['actual_main_ui'] is True and main['ui']['download_disabled'] is True
    runtime(main['ui']['runtime'],source);assert main['ui']['runtime']['error']==0
    return dict(internal_main_isolated_analyzer='PASS',api=api,pages=pages,remote_tests=5,remote_owner_recovery=1,main_tests=5,
                old_73_tests='SEPARATE_MANDATORY_GATES',physical_arm64='NOT_RUN',arbitrary_public_sources_enabled=False,owned_main_ui_tested=True,production_ready=False,automatic_publication=False)


def raw(evidence,name,count):
    text=(evidence/name).read_text();print(text)
    assert re.search(rf'^OK \({count} tests?\)\s*$',text,re.M)
    assert len(re.findall(r'^INSTRUMENTATION_STATUS_CODE: 0\s*$',text,re.M))==count
    assert not re.search(r'FAILURES!!!|shortMsg=|INSTRUMENTATION_(?:FAILED|ABORTED)|INSTRUMENTATION_STATUS_CODE: -[1-4]\b',text)


def read(path):
    raw=path.read_bytes();assert 0<len(raw)<=256*1024;return json.loads(raw)


def main():
    p=argparse.ArgumentParser();p.add_argument('--evidence',type=Path,required=True);p.add_argument('--source',required=True);p.add_argument('--api',type=int,required=True);p.add_argument('--pages',type=int,required=True);a=p.parse_args()
    raw(a.evidence,'remote-instrumentation.txt',5);raw(a.evidence,'remote-owner-recovery-instrumentation.txt',1)
    raw(a.evidence,'main-analysis-instrumentation.txt',4);raw(a.evidence,'main-ui-instrumentation.txt',1)
    probe=(a.evidence/'main-boundary.txt').read_text()
    assert all(x in probe for x in ('product_boundary_probe=PASS','IsolatedRuntimeService_external_denied=true','RemoteHttpsService_external_denied=true','shared_uid_python_service_absent=true'))
    screenshot=(a.evidence/'main-isolated-preview.png').read_bytes();assert 1024<len(screenshot)<=5*1024*1024 and screenshot[:8]==b'\x89PNG\r\n\x1a\n'
    remote={name:read(a.evidence/'remote'/f'{name}.json') for name in REMOTE};m={name:read(a.evidence/'main'/f'{name}.json') for name in MAIN}
    result=inspect(remote,m,read(a.evidence/'network-owner-recovery.json'),a.source,a.api,a.pages)
    (a.evidence/'bridge-summary.json').write_text(json.dumps(result,indent=2)+'\n');print('::notice::'+json.dumps(result))


if __name__=='__main__':main()
