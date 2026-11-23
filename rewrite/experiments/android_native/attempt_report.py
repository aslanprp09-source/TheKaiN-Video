"""Strict current-attempt selection. Historical failures are retained, never relabelled PASS."""
import uuid

SUITE = ((1,0,0,'PROBE'),(1,1,0,'SECURITY'),(4,2,1,'BLOCK_MAIN_CANCEL'),
         (4,2,2,'BLOCK_MAIN_DEADLINE'),(5,0,4,'CRASH'),(6,0,7,'RSS_DEFAULT'),
         (6,0,7,'RSS_64M'),(1,4,6,'BAD_DIGEST'),(1,3,5,'FAKE_PROC'),
         *((2,0,0,'ANALYZE'),)*5)


def identifier(value):
    assert isinstance(value,str) and str(uuid.UUID(value))==value


def current_suite(report):
    if 'attempt_schema' not in report:
        return report['cases'], None  # Original strict intake for historical APKs.
    assert report['attempt_schema']==1
    attempts=report['attempts'];assert 1<=len(attempts)<=16
    indexed={}
    for attempt in attempts:
        identifier(attempt['id']);assert attempt['id'] not in indexed
        indexed[attempt['id']]=attempt
        assert attempt['kind'] in ('LEGACY','PROBE','SUITE','MANUAL_HANG','SUPERVISOR_RECOVERY','OWNER_RECOVERY','DIAGNOSTIC_CYCLE')
        assert attempt['status'] in ('RUNNING','COMPLETED','FAILED','INTERRUPTED')
        if attempt['kind']=='LEGACY':
            assert attempt['status']=='FAILED' and attempt['historical'] and attempt['timestamps_unavailable']
        else:
            assert attempt['started_wall_ms']>0 and attempt['started_elapsed_ms']>=0
            assert 0<=attempt['completed_scenarios']<=attempt['started_scenarios']<=16
            if attempt['status'] in ('COMPLETED','FAILED'):
                assert attempt['finished_wall_ms']>0 and attempt['finished_elapsed_ms']>=attempt['started_elapsed_ms']
            if attempt['status']=='COMPLETED':
                assert attempt['completed_scenarios']==attempt['started_scenarios']
        if attempt['status']=='FAILED':
            assert isinstance(attempt['failure_class'],str) and 0<len(attempt['failure_class'])<=64
    current=indexed[report['current_attempt_id']]
    assert current['status']=='COMPLETED', 'Current attempt is incomplete/failed'
    suite=indexed[report['last_suite_attempt_id']]
    assert suite['kind']=='SUITE' and suite['status']=='COMPLETED', 'Latest full suite is incomplete/failed'
    assert suite['started_scenarios']==suite['completed_scenarios']==14
    rows=[r for r in report['cases'] if r.get('attempt_id')==suite['id']]
    assert len(rows)==14, 'Current suite must retain all14 actual outcomes'
    identifiers=set()
    for index,(row,expected) in enumerate(zip(rows,SUITE),1):
        identifier(row['scenario_id']);assert row['scenario_id'] not in identifiers;identifiers.add(row['scenario_id'])
        assert row['scenario_index']==index
        assert (row['operation'],row['mode'],row['expected_error'],row['scenario_name'])==expected
        assert row['started_elapsed_ms']>=suite['started_elapsed_ms']
        assert row['finished_elapsed_ms']>=row['started_elapsed_ms'] and row['finished_elapsed_ms']<=suite['finished_elapsed_ms']
        assert row['started_wall_ms']>0 and row['finished_wall_ms']>0
        trace=row['transition_log'];assert trace['limit']==24 and trace['dropped']==0
        events=trace['events'];phases=[e['phase'] for e in events]
        assert 1<=len(events)<=24 and len(phases)==len(set(phases))
        assert phases[0]=='SUBMITTED' and phases[-1]=='COMPLETED'
        assert set(phases)<= {'SUBMITTED','STARTED','DEADLINE','RETIRING','CLEANUP','COMPLETED'}
        assert all(a['elapsed_ms']<=b['elapsed_ms'] for a,b in zip(events,events[1:]))
        if row['scenario_name']=='BLOCK_MAIN_DEADLINE':
            assert phases==['SUBMITTED','STARTED','DEADLINE','RETIRING','CLEANUP','COMPLETED']
            assert row['error']==2 and row['death_confirmed'], 'Missing result is never a successful timeout'
    unfinished=report.get('unfinished_operations',[]);assert len(unfinished)<=4
    for pending in unfinished:
        assert 'id' in pending  # Preserved evidence, never treated as a completed result.
    return rows, {'suite_attempt_id':suite['id'],'retained_attempts':len(attempts),
                  'historical_failed_attempts':sum(x['status']=='FAILED' for x in attempts),
                  'unfinished_operations_retained':len(unfinished)}
