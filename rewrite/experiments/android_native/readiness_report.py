"""Additional configure-return and crash-result acceptance; original93 remain mandatory."""
import argparse
import json
import re
from pathlib import Path
from rewrite.experiments.android_native.bridge_report import process, raw, read


def inspect(barrier, crash, source, api, pages):
    assert re.fullmatch('[0-9a-f]{40}', source)
    all_proofs=[]
    for name, row in (('barrier',barrier),('crash',crash)):
        assert row['case']==name and row['source_commit']==source and row['api']==api and row['pages']==pages and row['production_ready'] is False
        assert len(row['proofs'])==6
        for proof in row['proofs']:
            process(proof,source)
            assert proof['ready_notification_seen'] is True and proof['configure_returned'] is True and proof['ready_published'] is True
            assert proof['configure_failure']=='NONE' and proof['transfer_only'] is False
        all_proofs+=row['proofs']
    assert len({p['id'] for p in all_proofs})==len({p['instance'] for p in all_proofs})==12
    assert all(barrier[k] is True for k in ('actual_binder_callback_held','actual_tls_after_return','no_early_network','fresh_after_each'))
    assert len(barrier['held'])==3 and all(p['failure']==0 for p in barrier['proofs'])
    for index, held in enumerate(barrier['held']):
        assert held['id']==barrier['proofs'][2*index]['id'] and held['pid']==barrier['proofs'][2*index]['pid']
        assert held['instance']==barrier['proofs'][2*index]['instance'] and held['start_ticks']==barrier['proofs'][2*index]['start_ticks']
        assert held['ready_notification_seen'] is True and held['configure_returned'] is False and held['ready_published'] is False
        assert held['handshake_phase']=='CONFIGURE_ACK' and held['requests']==0 and held['terminated'] is False
        assert held['kernel_process_gone'] is False and held['binder_dead'] is False and held['lease_clean'] is False
    assert crash['actual_crashes']==3 and crash['exact_worker_died'] is True and crash['fresh_after_each'] is True
    assert [p['failure'] for p in crash['proofs']]==[4,0,4,0,4,0]
    return dict(configure_return_readiness='PASS',source_commit=source,api=api,pages=pages,additional_tests=2,
        held_configure_cycles=3,actual_crash_fresh_cycles=3,old_93='SEPARATE_MANDATORY_GATES',
        peak_sampled_rss_bytes=max(p['peak_rss_bytes'] for p in all_proofs),max_observed_cpu_ms=max(p['observed_cpu_ms'] for p in all_proofs),
        max_retire_ms=max(p['retire_ms'] for p in all_proofs),physical_arm64='NOT_RUN',samsung46_root_cause='UNCONFIRMED',
        source_ui61_root_cause='UNCONFIRMED',production_ready=False,automatic_publication=False)


def main():
    p=argparse.ArgumentParser();p.add_argument('--evidence',type=Path,required=True);p.add_argument('--source',required=True)
    p.add_argument('--api',type=int,required=True);p.add_argument('--pages',type=int,required=True);a=p.parse_args()
    raw(a.evidence,'instrumentation.txt',2)
    result=inspect(read(a.evidence/'barrier.json'),read(a.evidence/'crash.json'),a.source,a.api,a.pages)
    (a.evidence/'readiness-summary.json').write_text(json.dumps(result,indent=2)+'\n');print('::notice::'+json.dumps(result))


if __name__=='__main__':main()
