import copy
import unittest
import uuid
from .attempt_report import current_suite, SUITE


def fixture():
    old,new=str(uuid.uuid4()),str(uuid.uuid4())
    rows=[]
    for i,(operation,mode,error,name) in enumerate(SUITE,1):
        phases=['SUBMITTED','STARTED',*(['DEADLINE'] if name=='BLOCK_MAIN_DEADLINE' else []),'RETIRING','CLEANUP','COMPLETED']
        rows.append(dict(attempt_id=new,scenario_id=str(uuid.uuid4()),scenario_name=name,scenario_index=i,
            operation=operation,mode=mode,expected_error=error,error=error,death_confirmed=True,
            started_elapsed_ms=100+i,finished_elapsed_ms=200+i,started_wall_ms=1000+i,finished_wall_ms=2000+i,
            transition_log=dict(limit=24,dropped=0,events=[dict(phase=p,elapsed_ms=100+j,wall_ms=1000+j) for j,p in enumerate(phases)])))
    attempts=[dict(id=old,kind='SUITE',status='FAILED',started_wall_ms=1,started_elapsed_ms=1,
        finished_wall_ms=90,finished_elapsed_ms=90,started_scenarios=4,completed_scenarios=3,failure_class='TimeoutException'),
        dict(id=new,kind='SUITE',status='COMPLETED',started_wall_ms=100,started_elapsed_ms=100,
        finished_wall_ms=3000,finished_elapsed_ms=300,started_scenarios=14,completed_scenarios=14)]
    return dict(attempt_schema=1,attempts=attempts,current_attempt_id=new,last_suite_attempt_id=new,
        cases=[dict(attempt_id=old,error=13),*rows],unfinished_operations=[dict(id='original-unfinished',wait_status='WAIT_EXCEEDED')])


class AttemptReportTest(unittest.TestCase):
    def test_complete_current_suite_retains_old_failed_attempt_and_missing_outcome(self):
        r=fixture();before=copy.deepcopy(r);rows,review=current_suite(r)
        self.assertEqual(len(rows),14);self.assertEqual(review['historical_failed_attempts'],1)
        self.assertEqual(review['unfinished_operations_retained'],1);self.assertEqual(r,before)

    def test_old_success_never_hides_new_failed_or_incomplete_attempt(self):
        for status in ('FAILED','RUNNING','INTERRUPTED'):
            r=fixture();r['attempts'][-1]['status']=status;r['attempts'][-1]['failure_class']='TimeoutException'
            with self.subTest(status=status),self.assertRaises(AssertionError):current_suite(r)

    def test_wrong_suite_or_missing_case_or_timeout_without_death_always_refused(self):
        for change in ('old_suite','missing','duplicate','wrong_order','wrong_timeout','no_death','no_deadline_phase','reused_scenario','forged_phase','busy_export','lost_failure'):
            r=fixture()
            if change=='old_suite':r['last_suite_attempt_id']=r['attempts'][0]['id']
            elif change=='missing':r['cases'].pop()
            elif change=='duplicate':r['cases'].append(copy.deepcopy(r['cases'][-1]))
            elif change=='wrong_order':r['cases'][1]['scenario_index']=2
            elif change=='wrong_timeout':r['cases'][4]['error']=0
            elif change=='no_death':r['cases'][4]['death_confirmed']=False
            elif change=='no_deadline_phase':r['cases'][4]['transition_log']['events'].pop(2)
            elif change=='reused_scenario':r['cases'][2]['scenario_id']=r['cases'][1]['scenario_id']
            elif change=='forged_phase':r['cases'][1]['transition_log']['events'][1]['phase']='RESET'
            elif change=='busy_export':r['exported_while_busy']=True
            else:del r['attempts'][0]['failure_class']
            with self.subTest(change=change),self.assertRaises((AssertionError,KeyError)):current_suite(r)


if __name__=='__main__':unittest.main()
