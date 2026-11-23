"""Read-only redacted failure facts. Never execute data or grant a QA pass."""
import json
from pathlib import Path
import re
import sys


def summary(path):
    raw = Path(path).read_bytes()
    assert 0 < len(raw) <= 256 * 1024
    data = json.loads(raw)
    assert data['source_commit'] == '4be4485430306b158ada6cd01b967a771009815c'
    fields = set('scenario_name scenario_index wait_stage wait_status last_capture_reason operation mode error expected_error death_confirmed python_entered owner_canary_intact owned_ipc_temp_files elapsed_ms cancel_to_exit_ms peak_rss_bytes rss_limit_bytes rlimit_as virtual_before rlimit_cpu requests payload_bytes runtime_future_done finished retiring preparing configuring fetching worker_death_observed binder_death_observed lease_observation retirement_service_observation supervisor_alive supervisor_thread_state supervisor_tick_age_ms deadline_expired deadline_lateness_ms wait_source'.split())
    def safe(obj):
        return {key:value for key,value in obj.items() if key in fields and
                (isinstance(value, (bool,int)) or isinstance(value,str) and re.fullmatch('[A-Za-z0-9_]{1,80}',value))}
    output = dict(source=data['source_commit'], bytes=len(raw), qa_failure_class=data.get('qa_failure_class'),
                  case_count=len(data.get('cases',[])), last_cases=[safe(row) for row in data.get('cases',[])[-3:]],
                  diagnostic_only=True, grants_acceptance=False)
    pending=data.get('pending',{})
    output['pending']=safe(pending)
    output['runtime_snapshot']=safe(pending.get('runtime_snapshot',{}))
    if output['qa_failure_class'] is not None:
        assert re.fullmatch('[A-Za-z0-9_]{1,80}',output['qa_failure_class'])
    return output


if __name__ == '__main__':
    matches=list(Path(sys.argv[1]).rglob('failure-qa-report.json'))
    assert len(matches)==1
    print(json.dumps(summary(matches[0]),indent=2))
