"""Additional kernel-guard recovery acceptance; never substitutes for the original matrices."""
import argparse
import json
from pathlib import Path
import re


def validate(folder):
    text=(folder/'lease-recovery-instrumentation.txt').read_text()
    assert re.search(r'^OK \(4 tests\)\s*$',text,re.M) and not any(s in text for s in ('FAILURES!!!','shortMsg=','INSTRUMENTATION_FAILED'))
    owner=(folder/'owner-guard-recovery-instrumentation.txt').read_text()
    assert re.search(r'^OK \(1 test\)\s*$',owner,re.M) and not any(s in owner for s in ('FAILURES!!!','shortMsg=','INSTRUMENTATION_FAILED'))
    rows=[json.loads(line) for line in (folder/'recovery-operation-evidence.jsonl').read_text().splitlines()]
    proofs=[json.loads(line) for line in (folder/'kernel-guard-recovery.jsonl').read_text().splitlines()]
    marker=json.loads((folder/'guard-owner-before-kill.json').read_text())
    assert marker['schema']==2 and marker['phase']=='ARMED' and marker['observed_started_worker_pid']>0
    successes=[x for x in proofs if x['status']==1]
    assert len(successes)>=5 and len({x['operation_id'] for x in successes})==len(successes)
    failed={x['id']:x for x in rows if x['error']==13}
    for proof in successes:
        assert proof['kernel_lock_exclusive'] and proof['old_operation_not_rewritten'] and not proof['production_ready']
        assert proof['device']>=0 and proof['inode']>0 and proof['elapsed_ms']>=0
        if proof['operation_id']!=marker['id']:
            assert proof['operation_id'] in failed and not failed[proof['operation_id']]['death_confirmed']
    owner_proof=[x for x in successes if x['operation_id']==marker['id']]
    assert len(owner_proof)==1
    proof=owner_proof[0]
    assert proof['previous_owner_pid']==marker['owner_pid'] and proof['owner_pid']!=marker['owner_pid']
    assert proof['inode']==marker['inode'] and proof['device']==marker['device']
    analyses=[x for x in rows if x['operation']==2 and x['error']==0]
    assert len(analyses)>=5
    assert all(x['death_confirmed'] and x['owner_canary_intact'] and x['child_protection']==255 for x in analyses)
    return {'kernel_guard_recovery':'PASS','new_tests':5,'positive_exclusive_proofs':len(successes),
        'abrupt_owner_restart':'PASS','old_failure_not_rewritten':True,
        'max_recovery_elapsed_ms':max(x['elapsed_ms'] for x in successes),
        'physical_new_recovery':'NOT_RUN','rss_is_sampled':True,'production_ready':False,'automatic_publication':False}

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--evidence',type=Path,required=True);args=parser.parse_args()
    report=validate(args.evidence);(args.evidence/'kernel-guard-recovery-report.json').write_text(json.dumps(report,indent=2)+'\n')
    print('::notice::'+json.dumps(report))
