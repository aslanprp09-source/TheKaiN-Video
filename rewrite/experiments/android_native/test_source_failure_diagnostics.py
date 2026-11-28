from copy import deepcopy
import json
from pathlib import Path
import tempfile
import unittest
from rewrite.experiments.android_native.source_failure_diagnostics import STAGES, correlate, rows


class SourceFailureDiagnosticsTest(unittest.TestCase):
    def fixture(self):
        source = dict(id='a'*36, source_commit='e'*40, worker_pid=123, worker_uid=99001, error=2, python_calls=0,
            secret_url='https://example.invalid/?token=fixture-secret', network=dict(failure=2,requests=0,
            ready_notification_seen=True, configure_returned=True, ready_published=True, terminated=True, url='fixture-secret'))
        runtime = dict(id=source['id'], worker_pid=123, worker_uid=99001, python_entered=True, error=2,
            source_timeline=dict(limit=len(STAGES), diagnostic_only=True, grants_death_proof=False, events=[
                dict(phase='SUBMITTED',elapsed_ms=100,wall_ms=1000),dict(phase='PYTHON_ENTERED',elapsed_ms=120,wall_ms=1020),
                dict(phase='RETIRING',elapsed_ms=30100,wall_ms=31000),dict(phase='COMPLETED',elapsed_ms=30200,wall_ms=31100)]))
        return source, runtime

    def test_exact_operation_correlation_is_bounded_diagnostic_only_and_never_leaks_urls(self):
        source, runtime = self.fixture()
        out = correlate([source],[runtime],'e'*40)
        self.assertTrue(out[0]['python_entered']);self.assertEqual(out[0]['python_calls_result'],0)
        self.assertFalse(out[0]['grants_acceptance']);self.assertTrue(out[0]['diagnostic_only'])
        self.assertNotIn('fixture-secret',json.dumps(out));self.assertNotIn('https:',json.dumps(out))
        with self.assertRaises(ValueError):correlate([], [runtime], 'e'*40)
        for bad in ('commit','worker','missing','duplicate','scalar','unbounded','fake_proof','unknown_phase','duplicate_phase','timestamp','network_category'):
            s,r=deepcopy(source),deepcopy(runtime);rr=[r]
            if bad=='commit':s['source_commit']='d'*40
            elif bad=='worker':r['worker_pid']=124
            elif bad=='missing':rr=[]
            elif bad=='duplicate':rr.append(deepcopy(r))
            elif bad=='scalar':r['error']='fixture-secret'
            elif bad=='unbounded':r['source_timeline']['events']*=4
            elif bad=='fake_proof':r['source_timeline']['grants_death_proof']=True
            elif bad=='unknown_phase':r['source_timeline']['events'][0]['phase']='fixture-secret'
            elif bad=='duplicate_phase':r['source_timeline']['events'].append(deepcopy(r['source_timeline']['events'][0]))
            elif bad=='timestamp':r['source_timeline']['events'][0]['elapsed_ms']=True
            elif bad=='network_category':s['network']['handshake_phase']='https://example.invalid/?token=fixture-secret'
            with self.subTest(bad=bad),self.assertRaises(ValueError):correlate([s],rr,'e'*40)
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)/'journal';path.write_text(json.dumps(source)+'\n')
            self.assertEqual(rows(path,4096),[source])
            path.write_bytes(b' '*4097)
            with self.assertRaises(ValueError):rows(path,4096)
