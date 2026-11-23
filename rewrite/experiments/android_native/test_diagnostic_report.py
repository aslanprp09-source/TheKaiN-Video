import copy
import json
from pathlib import Path
import tempfile
import unittest
from .diagnostic_report import inspect


def fixture():
    cycles=[]
    for i in range(3):
        rows={}
        for kind,error in (('cancel',1),('deadline',2)):
            phases=['SUBMITTED','STARTED',*(['DEADLINE'] if kind=='deadline' else []),'RETIRING','CLEANUP','COMPLETED']
            rows[kind]=dict(id=f'{kind}{i}',attempt_id=f'attempt{i}',parent_pid=100,error=error,expected_error=error,
                operation=4,mode=2,death_confirmed=True,owner_canary_intact=True,owned_ipc_temp_files=0,
                main_python_loaded=False,child_protection=255,worker_uid=99001,observer_uid=99001,parent_uid=10001,
                worker_pid=200+i,observer_pid=300+i,transition_log=dict(events=[dict(phase=x) for x in phases]))
        fresh=dict(error=0,death_confirmed=True,owner_canary_intact=True,guard_files=0,python_calls=1,formats=1,main_python_loaded=False)
        cycles.append(dict(attempt_id=f'attempt{i}',owner_pid=100,live_snapshot_observed=True,**rows,
            recovery=dict(status=1,kernel_lock_exclusive=True,old_operation_not_rewritten=True,original_error=13,original_death_confirmed=False,fresh_analysis=fresh)))
    return dict(same_application_lifecycle=True,production_ready=False,cycles=cycles)


class DiagnosticReportTest(unittest.TestCase):
    def check(self,data,text='OK (5 tests)\n'):
        with tempfile.TemporaryDirectory() as temp:
            p=Path(temp);(p/'diagnostic-instrumentation.txt').write_text(text)
            (p/'diagnostic-cycle-evidence.json').write_text(json.dumps(data));return inspect(p)

    def test_all_three_cycles_require_real_results_and_same_owner(self):
        self.assertEqual(self.check(fixture())['cycles'],3)
        for change in ('missing_cycle','different_owner','deadline_no_death','deadline_wrong_error','missing_deadline_phase','guard_remaining','rewritten_old_death','no_live_snapshot','worker_owner_uid'):
            d=copy.deepcopy(fixture());c=d['cycles'][1]
            if change=='missing_cycle':d['cycles'].pop()
            elif change=='different_owner':c['owner_pid']=101
            elif change=='deadline_no_death':c['deadline']['death_confirmed']=False
            elif change=='deadline_wrong_error':c['deadline']['error']=0
            elif change=='missing_deadline_phase':c['deadline']['transition_log']['events'].pop(2)
            elif change=='guard_remaining':c['recovery']['fresh_analysis']['guard_files']=1
            elif change=='rewritten_old_death':c['recovery']['original_death_confirmed']=True
            elif change=='no_live_snapshot':c['live_snapshot_observed']=False
            else:c['deadline']['worker_uid']=c['deadline']['parent_uid']
            with self.subTest(change=change),self.assertRaises(AssertionError):self.check(d)

    def test_incomplete_or_failed_instrumentation_cannot_be_accepted(self):
        for text in ('OK (3 tests)','OK (5 tests)\nFAILURES!!!','shortMsg=Process crashed','INSTRUMENTATION_FAILED'):
            with self.subTest(text=text),self.assertRaises(AssertionError):self.check(fixture(),text)


if __name__=='__main__':unittest.main()
