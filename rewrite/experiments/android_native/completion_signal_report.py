"""Strict additional evidence; a missing/delayed Runtime outcome is never a successful timeout."""
import argparse
import json
from pathlib import Path
import re


def inspect(data, source, api, pages):
    assert data['source_commit'] == source and re.fullmatch('[0-9a-f]{40}', source)
    assert data['api'] == api and data['page_size'] == pages
    assert data['production_ready'] is False and data['physical_samsung_root_cause_confirmed'] is False
    rows = data['rows']
    assert len(rows) == 3 and len({r['id'] for r in rows}) == 3
    assert len({r['owner_uid'] for r in rows}) == 1
    for step, (row, error) in enumerate(zip(rows, (1, 2, 0))):
        assert re.fullmatch('[0-9a-f-]{36}', row['id']) and row['step'] == step and row['error'] == error
        assert row['death_confirmed'] is True and row['owner_canary_intact'] is True
        assert row['child_protection'] == 255 and row['worker_uid'] != row['owner_uid']
        assert row['worker_uid'] % 100000 >= 90000
        assert row['signal_done'] is True and row['notification_done_before_release'] is False
        assert row['owned_ipc_temp_files'] == 0 and 0 < row['elapsed_ms'] < 35000
    assert 0 <= rows[0]['cancel_to_exit_ms'] <= 3000
    assert rows[1]['elapsed_ms'] >= 12000
    return dict(read_only_runtime_signal='PASS', paused_notifications='CONFIRMED',
                real_cancel_deadline_analysis='PASS', api=api, page_size=pages,
                physical_samsung_root_cause='UNCONFIRMED', production_ready=False)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--evidence', type=Path, required=True)
    parser.add_argument('--source', required=True)
    parser.add_argument('--api', type=int, required=True)
    parser.add_argument('--pages', type=int, required=True)
    args = parser.parse_args()
    text = (args.evidence / 'completion-signal-instrumentation.txt').read_text()
    print(text)
    assert re.search(r'^OK \(1 test\)\s*$', text, re.M)
    assert len(re.findall(r'^INSTRUMENTATION_STATUS_CODE: 0\s*$', text, re.M)) == 1
    assert not re.search(r'FAILURES!!!|shortMsg=|INSTRUMENTATION_(?:FAILED|ABORTED)|INSTRUMENTATION_STATUS_CODE: -[1-4]\b', text)
    result = inspect(json.loads((args.evidence / 'completion-signal-evidence.json').read_text()), args.source, args.api, args.pages)
    print('::notice::' + json.dumps(result))


if __name__ == '__main__':
    main()
