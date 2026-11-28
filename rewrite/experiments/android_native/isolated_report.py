"""Require real isolated-UID/kernel-FD/OS-reap evidence; no QA gate promotion."""
import argparse
import json
from pathlib import Path
import re


def validate(rows):
    assert rows, 'Missing isolated evidence'
    assert {r['error'] for r in rows} >= {0,1,2,4,5,6,7,8,9,10,11,14}, 'Missing fault coverage'
    seen=set()
    for row in rows:
        assert row['death_confirmed'] is True, 'Unconfirmed process cleanup'
        assert 0 <= row['retire_to_exit_ms'] <= 3000, 'Unbounded retirement'
        assert row['requests'] <= 12 and row['payload_bytes'] <= 2*1024*1024+1
        assert row['id'] not in seen, 'Duplicate operation'
        seen.add(row['id'])
        assert row['owner_canary_intact'] is True, 'Owner canary absent or modified'
        if row['worker_pid']:
            assert row['worker_pid'] != row['parent_pid'] and row['worker_uid'] != row['parent_uid']
            assert 90000 <= row['worker_uid'] % 100000 < 100000, 'Not an Android isolated UID'
            assert row['observer_pid'] not in (row['parent_pid'],row['worker_pid']) and row['observer_pid']>0
            assert row['observer_uid'] == row['worker_uid'] and row['observer_uid'] != row['parent_uid']
            assert row['observer_non_dumpable'] is True
            assert 90000 <= row['observer_uid'] % 100000 < 100000
            assert row['observer_start_ticks']>0 and row['observer_peak_rss_bytes']>0 and row['observer_cpu_ticks']>0
            assert row['observer_python_loaded'] is False, 'Python must never run in its resource observer'
            assert row['samples'] > 0 and row['peak_rss_bytes'] > 0 and row['start_ticks'] > 0
        if row['python_entered']:
            assert row['child_protection']==255, 'Missing irreversible native-child supervisor protections'
            assert 0 < row['resource_input_bytes'] <= 160*1024*1024
            assert row['rlimit_as'] == row['virtual_before']+256*1024*1024 and 0 < row['rlimit_cpu'] <= 64
            assert row['cpu_ticks'] > 0 and row['observed_cpu_ms'] > 0
        if row['error'] == 0:
            assert row['security_denials'] == (255 if row['mode'] == 1 else 31)
        if row['mode'] in (3,4):
            assert row['python_entered'] is False, 'Unverified resource/proc FD reached Python'
    assert any(r['error']==7 and r['rss_limit']==64*1024*1024 and r['peak_rss_bytes']>r['rss_limit'] for r in rows), 'No actual independent resident-limit retirement'
    credential=[r for r in rows if r['mode']==7]
    assert credential and all(r['child_protection']==255 and not r['python_entered'] and r['credential_denial_proof'] in (1,2,3)
        and r['error']==(6 if r['credential_denial_proof']==1 else 4)
        and (r['credential_denial_proof']!=3 or r['worker_exit_signal']==31) for r in credential), 'Missing measured kernel credential denial/recovery'
    blocked=[r for r in rows if r['mode']==2]
    assert {r['error'] for r in blocked} >= {1,2}, 'Blocked-main forced reap not demonstrated'
    assert all(r['python_entered'] for r in blocked)
    return {
        'schema':1,'boundary':'two UIDs / three PIDs; non-dumpable isolated supervisor; fenced native exec child',
        'operations':len(rows),'worker_instances':len({r['instance'] for r in rows if r['worker_pid']}),
        'max_retire_to_exit_ms':max(r['retire_to_exit_ms'] for r in rows),
        'max_cancel_to_exit_ms':max(r['cancel_to_exit_ms'] for r in rows),
        'max_success_sampled_rss_bytes':max(r['peak_rss_bytes'] for r in rows if r['error']==0),
        'max_sampled_rss_overshoot_bytes':max(max(0,r['peak_rss_bytes']-r['rss_limit']) for r in rows),
        'max_observed_cpu_ms':max(r['observed_cpu_ms'] for r in rows),
        'max_observer_rss_bytes':max(r['observer_peak_rss_bytes'] for r in rows),
        'max_observer_cpu_ticks':max(r['observer_cpu_ticks'] for r in rows),
        'max_resource_input_bytes':max(r['resource_input_bytes'] for r in rows),
        'sealed_memfd_and_file_cache_not_fully_accounted_by_rss':True,
        'rss_is_sampled_not_hard_limit':True,'production_ready':False,'automatic_publication':False,
        'physical_arm64':'NOT_RUN','qa89_independent_review':'BLOCKED_SEPARATE_TASK',
        'lost_owner_kernel_handles':'FAIL_CLOSED_UNTIL_OS_REBOOT; automatic recovery not claimed',
    }


def validate_frame(frame, rows, source, api, pages):
    assert re.fullmatch('[0-9a-f]{40}', source)
    assert frame['case']=='frame-refusal' and frame['source_commit']==source
    assert frame['api']==api and frame['pages']==pages and frame['production_ready'] is False
    assert len(frame['cycles'])==3
    indexed={r['id']:r for r in rows};assert len(indexed)==len(rows)
    identities=[];gaps=[]
    for cycle in frame['cycles']:
        assert cycle['returned']==5
        before,after=cycle['before_sequence'],cycle['after_sequence']
        assert type(before) is int and type(after) is int and before>0 and after>=before+5
        gaps.append(after-before)
        old,fresh=indexed[cycle['id']],indexed[cycle['fresh_id']]
        assert old['operation']==1 and old['mode']==5 and old['error']==5 and old['samples']>=after
        assert fresh['operation']==2 and fresh['mode']==0 and fresh['error']==0 and fresh['requests']==1 and fresh['payload_bytes']>0
        for row,prefix in ((old,''),(fresh,'fresh_')):
            assert re.fullmatch('[0-9a-f-]{36}',row['id'])
            assert row['worker_pid']==cycle[prefix+'pid'] and row['start_ticks']==cycle[prefix+'start_ticks']
            assert row['death_confirmed'] is True and row['owner_canary_intact'] is True and row['python_entered'] is True
            assert row['child_protection']==255 and row['observer_python_loaded'] is False
            identities.append((row['id'],row['instance']))
    assert len({p[0] for p in identities})==len({p[1] for p in identities})==6
    return dict(frame_refusal_before_channel_close='PASS',cycles=3,real_process_proofs=6,
        live_samples_while_callback_held_min=min(gaps),production_ready=False)


def main():
    parser=argparse.ArgumentParser();parser.add_argument('--evidence',type=Path,required=True)
    parser.add_argument('--api',type=int,required=True);parser.add_argument('--pages',type=int,required=True)
    parser.add_argument('--source',required=True)
    args=parser.parse_args();folder=args.evidence
    text=(folder/'isolated-instrumentation.txt').read_text()
    assert re.search(r'^OK \(27 tests\)\s*$',text,re.M), text[-12000:]
    assert not any(s in text for s in ('FAILURES!!!','shortMsg=','INSTRUMENTATION_FAILED'))
    assert int((folder/'api.txt').read_text())==args.api
    assert int((folder/'page-size.txt').read_text())==args.pages
    rows=[json.loads(line) for line in (folder/'isolated-evidence.jsonl').read_text().splitlines()]
    frame=folder/'frame-refusal.json';assert 0<frame.stat().st_size<=65536
    report=validate(rows);report['frame_refusal']=validate_frame(json.loads(frame.read_text()),rows,args.source,args.api,args.pages)
    report.update(api=args.api,actual_page_size=args.pages,tests=27)
    snapshots=sum('MEMINFO in pid' in p.read_text(errors='replace') for p in folder.glob('meminfo-isolated-*.txt'))
    assert snapshots>0,'No independent isolated-worker dumpsys meminfo snapshot'
    report['independent_meminfo_snapshots']=snapshots
    observer_snapshots=sum('MEMINFO in pid' in p.read_text(errors='replace') for p in folder.glob('meminfo-observer-*.txt'))
    assert observer_snapshots>0,'No independent Java-observer dumpsys meminfo snapshot'
    report['independent_observer_meminfo_snapshots']=observer_snapshots
    (folder/'isolated-resource-report.json').write_text(json.dumps(report,indent=2)+'\n')
    print('::notice::'+json.dumps(report))


if __name__=='__main__': main()
