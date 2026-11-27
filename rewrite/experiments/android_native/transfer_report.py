"""Additional D1 acceptance. Original84 main/analysis/lab cases remain separate mandatory gates."""
import argparse
import json
import re
from pathlib import Path
from rewrite.experiments.android_native.bridge_report import process, runtime, raw, read

REMOTE=('stream','security','lifecycle','boundary')
MAIN=('verification','lifecycle','storage','migration','ui')


def inspect(remote,main,source,api,pages):
    assert re.fullmatch('[0-9a-f]{40}',source) and set(remote)==set(REMOTE) and set(main)==set(MAIN)
    allproofs=[]
    for group in (remote,main):
        for name,row in group.items():
            assert row['case']==name and row['source_commit']==source and row['api']==api and row['pages']==pages and row['production_ready'] is False
            for proof in row['proofs']:
                process(proof,source);assert proof['transfer_only'] is True
            assert len({proof['id'] for proof in row['proofs']})==len(row['proofs'])
            assert len({proof['instance'] for proof in row['proofs']})==len(row['proofs'])
            allproofs+=row['proofs']
    assert remote['stream']['bytes']==2*1024*1024+17
    assert all(remote['stream'][k] is True for k in ('exact_bytes','bounded_pipe','fresh','history_immutable')) and len(remote['stream']['proofs'])==2
    assert all(remote['security'][k] is True for k in ('scope_redirect','framing','size','truncation','http_errors','tls','fresh_after_each')) and len(remote['security']['proofs'])==20
    assert all(remote['lifecycle'][k] is True for k in ('actual_stuck_dns','cancel','deadline','explicit_failure','fresh_after_each'))
    assert len(remote['lifecycle']['proofs'])==4 and remote['lifecycle']['proofs'][2]['failure']==2
    assert all(remote['boundary'][k] is True for k in ('capability','file_fd_refused','analysis_grant_refused')) and len(remote['boundary']['proofs'])==2
    assert all(main['verification'][k] is True for k in ('corrupt_refused','receipt_not_completion','retry_fresh'))
    assert main['verification']['verified_bytes']==2428 and len(main['verification']['proofs'])==2
    assert all(main['lifecycle'][k] is True for k in ('cancel','deadline','actual_http','no_late_writes','fresh_after_each'))
    assert len(main['lifecycle']['proofs'])==4 and main['lifecycle']['proofs'][2]['failure']==2
    assert all(main['storage'][k] is True for k in ('no_space','callback_storage_error','scope_refused','direct_downgrade_refused','fixture_alias_refused')) and len(main['storage']['proofs'])==2
    assert all(main['migration'][k] is True for k in ('v1_history_preserved','old_backend_direct','new_backend_durable')) and not main['migration']['proofs']
    ui=main['ui'];assert all(ui[k] is True for k in ('actual_main_ui','playback','durable_history','open_action'))
    assert ui['verified_bytes']==2428 and ui['sha256']=='dd201a3b814b113ca98749902195b996a1e2f608247bdd22d2044b4c0dfd5cb2'
    assert len(ui['proofs'])==1 and ui['proofs'][0]['transfer_receipt'] is True
    runtime(ui['isolated_python'],source);assert ui['isolated_python']['error']==0
    return dict(owned_progressive_main_pipeline='PASS',source_commit=source,api=api,pages=pages,additional_tests=9,original_84='SEPARATE_MANDATORY_GATES',
        transfer_peak_sampled_rss_bytes=max(p['peak_rss_bytes'] for p in allproofs),transfer_max_observed_cpu_ms=max(p['observed_cpu_ms'] for p in allproofs),
        transfer_max_retire_ms=max(p['retire_ms'] for p in allproofs),physical_arm64='NOT_RUN',mux='NOT_IMPLEMENTED',youtube='NOT_IMPLEMENTED',
        production_ready=False,automatic_publication=False)


def main():
    p=argparse.ArgumentParser();p.add_argument('--evidence',type=Path,required=True);p.add_argument('--source',required=True);p.add_argument('--api',type=int,required=True);p.add_argument('--pages',type=int,required=True);a=p.parse_args()
    for name,count in (('remote',4),('main',4),('ui',1)):raw(a.evidence,name+'-instrumentation.txt',count)
    screenshot=(a.evidence/'completed.png').read_bytes();assert 1024<len(screenshot)<=5*1024*1024 and screenshot[:8]==b'\x89PNG\r\n\x1a\n'
    report=inspect({n:read(a.evidence/'remote'/f'{n}.json') for n in REMOTE},{n:read(a.evidence/'main'/f'{n}.json') for n in MAIN},a.source,a.api,a.pages)
    (a.evidence/'transfer-summary.json').write_text(json.dumps(report,indent=2)+'\n');print('::notice::'+json.dumps(report))


if __name__=='__main__':main()
