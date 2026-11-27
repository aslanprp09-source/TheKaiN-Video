"""First-cause race intake; original95 native cases remain separately mandatory."""
import argparse
import json
import re
from pathlib import Path
from rewrite.experiments.android_native.bridge_report import process, raw, read


def inspect(race, kernel, source, api, pages):
    assert re.fullmatch('[0-9a-f]{40}', source)
    for name, row in (('race', race), ('kernel', kernel)):
        assert row['case'] == name and row['source_commit'] == source and row['api'] == api and row['pages'] == pages
        assert row['production_ready'] is False
    assert race['actual_tls'] is True and race['actual_binder'] is True and len(race['rows']) == 9
    proofs = []
    for index, row in enumerate(race['rows']):
        mode = ('death', 'cancel', 'ipc')[index // 3]
        assert row['mode'] == mode and row['attempt'] == index % 3
        expected = dict(death=4, cancel=1, ipc=5)[mode]
        assert row['returned'] == expected
        before, retired, fresh = (row[k] for k in ('before', 'retired', 'fresh'))
        for key in ('id', 'pid', 'start_ticks', 'instance'):
            assert before[key] == retired[key]
        assert before['failure'] == 0 and before['requests'] == 1
        assert before['ready_published'] is True and before['configure_returned'] is True
        assert before['binder_dead'] is False and before['terminated'] is False and before['kernel_process_gone'] is False
        assert before['lease_clean'] is False and before['terminal_origin'] == 'NONE'
        for p in (retired, fresh):
            process(p, source)
            assert p['configure_returned'] is True and p['ready_published'] is True and p['configure_failure'] == 'NONE'
            assert p['transfer_only'] is False
        assert retired['failure'] == (0 if mode == 'cancel' else expected)
        assert fresh['failure'] == 0 and fresh['requests'] == 1 and fresh['payload_bytes'] == 5
        assert fresh['terminal_origin'] == 'OWNER_CLOSE'
        if mode == 'death':
            assert retired['terminal_origin'] in ('BINDER_DEATH', 'SERVICE_DISCONNECTED', 'KERNEL_WATCH')
            assert retired['terminal_category'] in ('DEAD_OBJECT', 'DYING_OR_REUSED', 'PROC_ABSENT')
        elif mode == 'cancel':
            assert retired['terminal_origin'] == 'OWNER_CLOSE'
        else:
            assert retired['terminal_origin'] == 'REPLY_RPC' and retired['terminal_category'] == 'PROTOCOL'
        proofs += [retired, fresh]
    assert len({p['id'] for p in proofs}) == len({p['instance'] for p in proofs}) == 18
    assert all(kernel[k] is True for k in ('simulated_parser_only', 'dying_missing_rss', 'zombie_not_absent',
        'unknown_refused', 'malformed_refused', 'access_denied_refused', 'pid_change_refused', 'actual_absence_errno_only', 'pid_reuse'))
    return dict(https_terminal_cause='PASS', source_commit=source, api=api, pages=pages, additional_tests=2,
        forced_races=9, real_worker_proofs=18, kernel_checks='SIMULATED_PARSER_ONLY', original95='SEPARATE_MANDATORY_GATES',
        peak_sampled_rss_bytes=max(p['peak_rss_bytes'] for p in proofs), max_observed_cpu_ms=max(p['observed_cpu_ms'] for p in proofs),
        max_retire_ms=max(p['retire_ms'] for p in proofs), physical_arm64='NOT_RUN', source_ui61_root_cause='UNCONFIRMED',
        samsung46_root_cause='UNCONFIRMED', production_ready=False, automatic_publication=False)


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--evidence', type=Path, required=True); p.add_argument('--source', required=True)
    p.add_argument('--api', type=int, required=True); p.add_argument('--pages', type=int, required=True)
    a = p.parse_args(); raw(a.evidence, 'instrumentation.txt', 2)
    result = inspect(read(a.evidence/'race.json'), read(a.evidence/'kernel.json'), a.source, a.api, a.pages)
    (a.evidence/'terminal-summary.json').write_text(json.dumps(result, indent=2)+'\n')
    print('::notice::'+json.dumps(result))


if __name__ == '__main__': main()
