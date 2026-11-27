"""D2 intake: actual main UI, selected files, media oracle and fresh process cleanup."""
import argparse
import json
import re
from pathlib import Path
from rewrite.experiments.android_native.bridge_report import process, runtime, raw, read

ORACLE = {
    'owned-90p': (160, 90, 2428, 'dd201a3b814b113ca98749902195b996a1e2f608247bdd22d2044b4c0dfd5cb2'),
    'owned-180p': (320, 180, 2485, 'eec943f4eb96f892f1b5f2cce512d4802f810b38739d2ccfad49d1eb437e0856'),
}


def inspect(ui, grant, source, api, pages):
    assert re.fullmatch('[0-9a-f]{40}', source)
    for row in (ui, grant):
        assert row['source_commit'] == source and row['api'] == api and row['pages'] == pages
        assert row['production_ready'] is False
    assert grant['exact_grant_refusals'] == 4 and grant['no_network_or_staging'] is True
    assert all(ui[k] is True for k in ('actual_main_ui', 'unknown_size_fps_video_codec', 'distinct_quality_labels', 'descending_choices', 'durable_history', 'open_actions', 'distinct_outputs'))
    runtime(ui['runtime'], source)
    assert ui['runtime']['error'] == 0 and ui['runtime']['formats'] == 2 and ui['runtime']['python_calls'] == 1
    assert ui['runtime']['pages'] == pages and ui['runtime']['network']['requests'] >= 1
    selections = ui['selections']
    assert len(selections) == 2 and {r['format_id'] for r in selections} == set(ORACLE)
    assert [r['height'] for r in selections] == [180, 90]  # Actual explicit selection, not two submissions of the default.
    assert len({r['job_id'] for r in selections}) == len({r['sha256'] for r in selections}) == 2
    assert len({r['proof']['id'] for r in selections}) == len({r['proof']['instance'] for r in selections}) == 2
    for row in selections:
        assert (row['width'], row['height'], row['verified_bytes'], row['sha256']) == ORACLE[row['format_id']]
        assert row['audio_tracks'] == row['video_tracks'] == 1
        assert all(row[k] is True for k in ('all_tracks_sampled', 'selected_url_persisted', 'playback'))
        proof = row['proof']; process(proof, source)
        assert proof['source_commit'] == source and proof['transfer_only'] is True and proof['transfer_receipt'] is True
        assert proof['verified_media'] is False  # A network receipt does not claim media verification.
    return dict(owned_main_format_selection='PASS', source_commit=source, api=api, pages=pages, additional_tests=2,
        original_95='SEPARATE_MANDATORY_GATES', physical_arm64='NOT_RUN', mux='NOT_IMPLEMENTED', youtube='NOT_IMPLEMENTED',
        transfer_peak_sampled_rss_bytes=max(r['proof']['peak_rss_bytes'] for r in selections),
        transfer_max_observed_cpu_ms=max(r['proof']['observed_cpu_ms'] for r in selections),
        transfer_max_retire_ms=max(r['proof']['retire_ms'] for r in selections), production_ready=False, automatic_publication=False)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--evidence', type=Path, required=True); parser.add_argument('--source', required=True)
    parser.add_argument('--api', type=int, required=True); parser.add_argument('--pages', type=int, required=True)
    args = parser.parse_args()
    raw(args.evidence, 'instrumentation.txt', 2)
    for name in ('choices-180.png', 'choices-90.png', 'history.png'):
        picture = (args.evidence/name).read_bytes()
        assert 1024 < len(picture) <= 5*1024*1024 and picture[:8] == b'\x89PNG\r\n\x1a\n'
    report = inspect(read(args.evidence/'ui.json'), read(args.evidence/'grant.json'), args.source, args.api, args.pages)
    (args.evidence/'formats-summary.json').write_text(json.dumps(report, indent=2)+'\n')
    print('::notice::'+json.dumps(report))


if __name__ == '__main__': main()
