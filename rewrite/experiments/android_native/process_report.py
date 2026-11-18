"""Validate measured process/limit evidence, never infer exact peaks or phone results."""
import argparse
import json
from pathlib import Path


def summarize(records):
    assert 10 <= len(records) <= 128, 'Missing/bloated operation evidence'
    assert len({r['id'] for r in records}) == len(records), 'Duplicate operation result'
    assert len({r['parent_pid'] for r in records}) == 1, 'Main process changed'
    assert all(r['death_confirmed'] for r in records), 'Unconfirmed worker cleanup'
    assert all(r['retire_to_exit_ms'] >= 0 for r in records), 'Missing cleanup latency'
    assert all(r['cancel_to_exit_ms'] >= 0 for r in records if r['error']==1), 'Missing cancellation latency'
    assert {0,1,2,4,5,7,8,9,10,11,14} <= {r['error'] for r in records}, 'Missing fault coverage'
    started=[r for r in records if r['worker_pid']]
    assert all(r['worker_pid'] != r['parent_pid'] and r['start_ticks'] > 0 for r in started), 'Python shared main PID'
    assert all(r['requests'] <= 12 and r['payload_bytes'] <= 2*1024*1024+1 for r in records), 'External network budget exceeded'
    limited=[r for r in started if r['rlimit_as']]
    assert limited and all(r['rlimit_as'] == r['virtual_before']+r['virtual_headroom'] and
        0 < r['rlimit_cpu'] <= 64 and r['kernel_socket_denied'] for r in limited), 'Kernel limit proof missing'
    assert all(r['samples'] > 0 and r['peak_rss_bytes'] > 0 for r in started), 'Missing independent RSS samples'
    memory=[r for r in records if r['operation']==6]
    assert memory and all(r['error']==7 for r in memory), 'Memory fault escaped'
    success=[r for r in records if r['error']==0]
    return {'operations':len(records),'independent_rss_samples':sum(r['samples'] for r in records),
        'max_success_sampled_rss_bytes':max(r['peak_rss_bytes'] for r in success),
        'memory_fault_sampled_rss_bytes':max(r['peak_rss_bytes'] for r in memory),
        'memory_fault_rss_limit_bytes':memory[0]['rss_limit'],
        'max_sampled_rss_overshoot_bytes':max(max(0,r['peak_rss_bytes']-r['rss_limit']) for r in records),
        'timeout_elapsed_ms':[r['elapsed_ms'] for r in records if r['error']==2],
        'canceled_operation_elapsed_ms':[r['elapsed_ms'] for r in records if r['error']==1],
        'cancel_to_exit_ms':[r['cancel_to_exit_ms'] for r in records if r['error']==1],
        'max_retire_to_exit_ms':max(r['retire_to_exit_ms'] for r in records),
        'death_confirmed':True,'source':'parent /proc RSS polling + child kernel getrlimit, not Python counters',
        'production_ready':False,'arm64_execution':'NOT_RUN'}


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--evidence',type=Path,required=True)
    parser.add_argument('--api',type=int,required=True);parser.add_argument('--pages',type=int,required=True)
    args=parser.parse_args()
    records=[json.loads(line) for line in (args.evidence/'process-evidence.jsonl').read_text().splitlines()]
    keys=('operation','error','elapsed_ms','requests','payload_bytes','samples','death_confirmed',
          'worker_pid','python_entered','virtual_before','cancel_to_exit_ms','retire_to_exit_ms')
    # Include all fault diagnostics even when the record schema is valid but JUnit fails.
    # Fixed parent counters only; never fixture URLs, certificates or keys.
    print('::notice::'+json.dumps({'fault_operation_evidence':[
        {key:r.get(key) for key in keys} for r in records[:128] if r.get('error')!=0]}))
    report=summarize(records)
    report.update(api=args.api,page_size=args.pages)
    report['meminfo_snapshots']=sum('MEMINFO in pid' in p.read_text(errors='replace') for p in args.evidence.glob('meminfo-*.txt'))
    assert report['meminfo_snapshots']>0,'No independent dumpsys meminfo snapshot'
    (args.evidence/'resource-report.json').write_text(json.dumps(report,indent=2)+'\n')
    print('::notice::'+json.dumps(report))


if __name__=='__main__':main()
