"""Bounded, whitelisted source/runtime journal correlation. Never grants a PASS."""
import argparse
import json
from pathlib import Path
import re

STAGES = ('SUBMITTED', 'PREPARATION_STARTED', 'RESOURCES_READY', 'NETWORK_CLIENT_CREATED', 'BIND_REQUESTED',
    'SERVICE_CONNECTED', 'SUPERVISOR_AUTHENTICATED', 'CHILD_ANNOUNCED', 'LIMITS_ARMED', 'PYTHON_ENTERED',
    'FIRST_FETCH_ENTERED', 'FIRST_FETCH_RETURNED', 'RETIRING', 'COMPLETED')


def rows(path, limit):
    with path.open('rb') as stream:
        value = stream.read(limit+1)
    if not value or len(value) > limit:
        raise ValueError('JOURNAL_SIZE')
    result = [json.loads(line) for line in value.splitlines() if line.strip()]
    if any(not isinstance(r, dict) for r in result):
        raise ValueError('JOURNAL_SHAPE')
    return result


def correlate(source, runtime, commit):
    if not source or not runtime:
        raise ValueError('JOURNAL_MISSING')
    if not re.fullmatch('[0-9a-f]{40}', commit):
        raise ValueError('SOURCE_IDENTITY')
    by_id = {}
    for row in runtime:
        key = row.get('id')
        if not isinstance(key, str) or not re.fullmatch('[0-9a-f-]{36}', key) or key in by_id:
            raise ValueError('RUNTIME_IDENTITY')
        by_id[key] = row
    result = []
    for row in source[-6:]:
        if row.get('source_commit') != commit or row.get('id') not in by_id:
            raise ValueError('SOURCE_RUNTIME_MISSING')
        peer = by_id[row['id']]
        if row['worker_pid'] != peer['worker_pid'] or row['worker_uid'] != peer['worker_uid']:
            raise ValueError('SOURCE_RUNTIME_MISMATCH')
        safe = dict(diagnostic_only=True, grants_acceptance=False, id=row['id'], source_commit=commit)
        for key in ('error', 'elapsed_ms', 'worker_pid', 'worker_uid', 'observer_pid', 'python_entered', 'samples',
                    'peak_rss_bytes', 'observed_cpu_ms', 'worker_exit_signal', 'worker_exit_code', 'death_confirmed'):
            value = peer.get(key, row.get(key))
            if value is not None and type(value) not in (int, bool):
                raise ValueError('DIAGNOSTIC_SCALAR')
            safe[key] = value
        timeline = peer.get('source_timeline')
        if not isinstance(timeline, dict) or timeline.get('limit') != len(STAGES) or timeline.get('diagnostic_only') is not True or timeline.get('grants_death_proof') is not False:
            raise ValueError('TIMELINE_IDENTITY')
        events = timeline.get('events')
        if not isinstance(events, list) or not 1 <= len(events) <= len(STAGES):
            raise ValueError('TIMELINE_LIMIT')
        seen = set(); clean = []
        for event in events:
            phase = event.get('phase')
            if phase not in STAGES or phase in seen or any(type(event.get(k)) is not int or event[k] < 0 for k in ('elapsed_ms', 'wall_ms')):
                raise ValueError('TIMELINE_EVENT')
            seen.add(phase); clean.append({k: event[k] for k in ('phase', 'elapsed_ms', 'wall_ms')})
        safe['source_timeline'] = clean
        network = row.get('network', {})
        safe['network'] = {}
        for key in ('failure', 'requests', 'ready_notification_seen', 'configure_returned', 'ready_published', 'terminated'):
            value = network.get(key)
            if value is not None and type(value) not in (int, bool):
                raise ValueError('NETWORK_SCALAR')
            safe['network'][key] = value
        for key, allowed in (('handshake_phase', ('BIND_PENDING','HELLO','CONFIGURE','CONFIGURE_ACK','READY')),
                             ('configure_failure', ('NONE','DEAD_OBJECT','REMOTE','PROTOCOL'))):
            value = network.get(key)
            if value is not None and (not isinstance(value, str) or value not in allowed):
                raise ValueError('NETWORK_CATEGORY')
            safe['network'][key] = value
        safe['python_calls_result'] = row.get('python_calls') if type(row.get('python_calls')) is int else None
        result.append(safe)
    return result


def main():
    p = argparse.ArgumentParser(); p.add_argument('--source', type=Path, required=True)
    p.add_argument('--runtime', type=Path, required=True); p.add_argument('--commit', required=True); a = p.parse_args()
    try:
        for row in correlate(rows(a.source, 256*1024), rows(a.runtime, 1536*1024), a.commit):
            print('MAIN_SOURCE_PHASE_PROOF '+json.dumps(row, sort_keys=True))
    except (OSError, ValueError, TypeError, KeyError, AttributeError, RecursionError):
        print('MAIN_SOURCE_PHASE_DIAGNOSTICS_UNAVAILABLE; original failure retained; no acceptance granted')


if __name__ == '__main__': main()
