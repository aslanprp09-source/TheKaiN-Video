"""Additional operator recovery evidence; no device attestation or release-gate update."""
import argparse
import json
from pathlib import Path
if __package__:
    from .isolated_phone_report import inspect as inspect_uid
else:
    from isolated_phone_report import inspect as inspect_uid


def inspect_trials(report):
    assert not report.get('recovery_pending') and not report.get('recovery_failure_class')
    assert report['lost_owner_recovery']=='EXPLICIT_KERNEL_GUARD_RECOVERY_OR_FAIL_CLOSED'
    trials=report.get('recovery_trials',[]);assert 2<=len(trials)<=16
    positive=[r for r in trials if r['status']==1]
    assert {r['trigger'] for r in positive}>={'supervisor_loss','owner_restart'}
    assert len({r['operation_id'] for r in positive})==len(positive)
    for row in trials:
        assert row['status'] in (0,1) and row['kernel_lock_exclusive']==(row['status']==1)
        assert row['production_ready'] is False and row['old_operation_not_rewritten'] is True
        if row['status']==0:continue
        assert row['inode']>0 and row['device']>=0 and row['elapsed_ms']>=0
        if row['trigger']=='supervisor_loss':assert row['original_error']==13 and row['original_death_confirmed'] is False
        elif row['trigger']=='owner_restart':assert row['previous_owner_pid']!=row['owner_pid'] and row['previous_owner_pid']>0
        else:raise AssertionError('Unknown recovery trigger')
        fresh=row['fresh_analysis']
        assert fresh['id']!=row['operation_id'] and fresh['error']==0 and fresh['death_confirmed'] and fresh['owner_canary_intact']
        assert fresh['python_calls']==1 and fresh['formats']==1 and fresh['guard_files']==0 and not fresh['main_python_loaded']
        assert fresh['worker_uid']!=row['parent_uid'] and 90000<=fresh['worker_uid']%100000<100000
        assert fresh['observer_uid']==fresh['worker_uid']
        assert fresh['worker_pid']!=row['owner_pid'] and fresh['observer_pid'] not in (row['owner_pid'],fresh['worker_pid'])
    return {'positive_explicit_recoveries':len(positive),'observer_loss_and_owner_restart_reported':True,'production_ready':False,'physical_device_attested':False}


def inspect(report,receipt):
    result=inspect_uid(report,receipt);result['recovery']=inspect_trials(report);return result

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--report',type=Path,required=True);p.add_argument('--receipt',type=Path,required=True);a=p.parse_args()
    assert a.report.stat().st_size<=256*1024 and a.receipt.stat().st_size<=2*1024*1024
    print(json.dumps(inspect(json.loads(a.report.read_text()),json.loads(a.receipt.read_text())),indent=2))
