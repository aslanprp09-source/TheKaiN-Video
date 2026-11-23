"""Additional same-lifecycle QA repetition gate, separate from all original test gates."""
import argparse
import json
from pathlib import Path
import re


def inspect(folder):
    text=(folder/'diagnostic-instrumentation.txt').read_text()
    assert re.search(r'^OK \(5 tests\)\s*$',text,re.M)
    assert not any(x in text for x in ('FAILURES!!!','shortMsg=','INSTRUMENTATION_FAILED'))
    data=json.loads((folder/'diagnostic-cycle-evidence.json').read_text())
    assert data['same_application_lifecycle'] and data['production_ready'] is False
    cycles=data['cycles'];assert len(cycles)==3
    assert len({x['owner_pid'] for x in cycles})==1 and cycles[0]['owner_pid']>0
    assert len({x['attempt_id'] for x in cycles})==3
    operation_ids=set()
    for cycle in cycles:
        assert cycle['live_snapshot_observed'] is True
        for kind,error in (('cancel',1),('deadline',2)):
            row=cycle[kind];assert row['error']==row['expected_error']==error and row['operation']==4 and row['mode']==2
            assert row['attempt_id']==cycle['attempt_id'] and row['parent_pid']==cycle['owner_pid']
            assert row['id'] not in operation_ids;operation_ids.add(row['id'])
            assert row['death_confirmed'] and row['owner_canary_intact'] and row['owned_ipc_temp_files']==0
            assert not row['main_python_loaded'] and row['child_protection']==255
            assert row['worker_uid']==row['observer_uid']!=row['parent_uid'] and row['worker_pid']!=row['observer_pid']
            phases=[e['phase'] for e in row['transition_log']['events']]
            assert phases==['SUBMITTED','STARTED',*(['DEADLINE'] if kind=='deadline' else []),'RETIRING','CLEANUP','COMPLETED']
        proof=cycle['recovery'];assert proof['status']==1 and proof['kernel_lock_exclusive'] and proof['old_operation_not_rewritten']
        assert proof['original_error']==13 and proof['original_death_confirmed'] is False
        fresh=proof['fresh_analysis'];assert fresh['error']==0 and fresh['death_confirmed'] and fresh['owner_canary_intact']
        assert fresh['guard_files']==0 and fresh['python_calls']==1 and fresh['formats']==1 and not fresh['main_python_loaded']
    return {'same_lifecycle_repetition':'PASS','cycles':3,'additional_tests':5,
            'physical_samsung':'BLOCKED_SEPARATE_ACCEPTANCE','production_ready':False}


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--evidence',type=Path,required=True);a=p.parse_args()
    result=inspect(a.evidence);(a.evidence/'diagnostic-report.json').write_text(json.dumps(result,indent=2)+'\n')
    print('::notice::'+json.dumps(result))
