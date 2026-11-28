from copy import deepcopy
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from rewrite.experiments.android_native import test_bridge_report
from rewrite.experiments.android_native.formats_report import ORACLE, inspect, failure_nodes


class FormatsIntakeTest(unittest.TestCase):
    def test_failure_export_is_bounded_redacted_and_never_grants_acceptance(self):
        node = dict(id='variant_owned-90p', package='owned', text='https://example.invalid/media?token=fixture-secret',
                    visible=True, clickable=True, enabled=True, focused=False, untrusted_extra='fixture-secret')
        row = dict(api=26, bounded=True, nodes=[node])
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder)/'nodes.json'
            path.write_text(json.dumps(row))
            result = failure_nodes(path)
            self.assertNotIn('fixture-secret', json.dumps(result))
            self.assertEqual(result[0]['text'], 'OWNED_URL_REDACTED')
            self.assertIs(result[0]['diagnostic_only'], True)
            self.assertIs(result[0]['grants_acceptance'], False)
            for bad in ([row]*101, dict(row, nodes=[node]*101), dict(row, api=True), dict(row, bounded=False),
                        dict(row, nodes=[dict(node, text=7)]), dict(row, nodes=[dict(node, visible='true')])):
                path.write_text(json.dumps(bad))
                with self.assertRaises(ValueError): failure_nodes(path)
            path.write_bytes(b' '* (256*1024+1))
            with self.assertRaises(ValueError): failure_nodes(path)

    def fixture(self):
        _, main, _, source = test_bridge_report.BridgeIntakeTest().fixture()
        ui = dict(source_commit=source, api=36, pages=16384, production_ready=False,
            actual_main_ui=True, unknown_size_fps_video_codec=True, distinct_quality_labels=True, descending_choices=True,
            durable_history=True, open_actions=True, distinct_outputs=True, runtime=deepcopy(main['public']['runtime']), selections=[])
        ui['runtime']['formats'] = 2
        for i, identifier in enumerate(('owned-180p', 'owned-90p')):
            width, height, size, sha = ORACLE[identifier]
            proof = deepcopy(main['public']['runtime']['network'])
            proof.update(source_commit=source, id=f'{i+1:08x}-0000-0000-0000-000000000000',
                instance=f'{i+1:08x}-0000-0000-0000-000000000000', transfer_only=True, transfer_receipt=True, verified_media=False)
            ui['selections'].append(dict(job_id=str(i), format_id=identifier, width=width, height=height,
                verified_bytes=size, sha256=sha, video_tracks=1, audio_tracks=1, all_tracks_sampled=True,
                selected_url_persisted=True, playback=True, proof=proof))
        grant = dict(source_commit=source, api=36, pages=16384, production_ready=False, exact_grant_refusals=4, no_network_or_staging=True)
        return ui, grant, source

    def test_no_format_pass_without_both_real_choices_tracks_history_and_fresh_death(self):
        ui, grant, source = self.fixture()
        self.assertEqual(inspect(ui, grant, source, 36, 16384)['additional_tests'], 2)
        for bad in ('missing', 'duplicate', 'source', 'pages', 'grant', 'network', 'invented', 'history', 'outputs', 'label', 'order',
                    'runtime_count', 'runtime_uid', 'width', 'hash', 'size', 'no_audio', 'no_video', 'samples', 'playback',
                    'selection', 'receipt', 'stale_process', 'binder_only', 'no_lease', 'no_service', 'changed_source', 'receipt_is_media'):
            data, denied = deepcopy(ui), deepcopy(grant); row = data['selections'][0]
            if bad == 'missing': data['selections'].pop()
            elif bad == 'duplicate': data['selections'][1] = deepcopy(row)
            elif bad == 'source': data['source_commit'] = 'e'*40
            elif bad == 'pages': data['pages'] = 4096
            elif bad == 'grant': denied['exact_grant_refusals'] = 0
            elif bad == 'network': denied['no_network_or_staging'] = False
            elif bad == 'invented': data['unknown_size_fps_video_codec'] = False
            elif bad == 'history': data['durable_history'] = False
            elif bad == 'outputs': data['distinct_outputs'] = False
            elif bad == 'label': data['distinct_quality_labels'] = False
            elif bad == 'order': data['descending_choices'] = False
            elif bad == 'runtime_count': data['runtime']['formats'] = 1
            elif bad == 'runtime_uid': data['runtime']['worker_uid'] = data['runtime']['owner_uid']
            elif bad == 'width': row['width'] = 160
            elif bad == 'hash': row['sha256'] = '0'*64
            elif bad == 'size': row['verified_bytes'] += 1
            elif bad == 'no_audio': row['audio_tracks'] = 0
            elif bad == 'no_video': row['video_tracks'] = 0
            elif bad == 'samples': row['all_tracks_sampled'] = False
            elif bad == 'playback': row['playback'] = False
            elif bad == 'selection': row['selected_url_persisted'] = False
            elif bad == 'receipt': row['proof']['transfer_receipt'] = False
            elif bad == 'stale_process': data['selections'][1]['proof'] = deepcopy(row['proof'])
            elif bad == 'binder_only': row['proof']['kernel_process_gone'] = False
            elif bad == 'no_lease': row['proof']['lease_clean'] = False
            elif bad == 'no_service': row['proof']['service_absent'] = False
            elif bad == 'changed_source': row['proof']['source_commit'] = 'e'*40
            elif bad == 'receipt_is_media': row['proof']['verified_media'] = True
            with self.subTest(bad=bad), self.assertRaises((AssertionError, KeyError)):
                inspect(data, denied, source, 36, 16384)

    def test_real_pinned_extractor_retains_both_committed_choices_and_does_not_invent_unknowns(self):
        # The actual owned HTML, real pinned yt-dlp and installed audit hook. Host transport is offline.
        code = r'''import json,sys,types
from pathlib import Path
html=Path('rewrite/android/app/src/androidTest/assets/owned-formats-page.html').read_bytes()
sys.path[:0]=['rewrite/experiments/android_native','rewrite/experiments/android_ytdlp']
class Error(Exception):pass
calls=[]
def fetch(url,method):
    calls.append(method)
    return html,url,'text/html',200,len(html)
sys.modules['_thekain_transport']=types.SimpleNamespace(Error=Error,fetch_info=fetch)
import thekain_lab
from source_analysis import analyze
result=analyze('https://raw.githubusercontent.com/aslanprp09-source/TheKaiN-Video/current/owned-formats-page.html')
assert calls==['GET'] and result['formats']==2 and result['download_enabled'] is False
print(json.dumps(result['analysis']['formats']))
'''
        root = Path(__file__).resolve().parents[3]
        result = subprocess.run([sys.executable, '-c', code], cwd=root, text=True, capture_output=True, timeout=20)
        self.assertEqual(result.returncode, 0, result.stderr)
        formats = json.loads(result.stdout)
        self.assertEqual({f['id'] for f in formats}, set(ORACLE))
        for row in formats:
            width, height, size, sha = ORACLE[row['id']]
            self.assertEqual((row['width'], row['height']), (width, height))
            self.assertEqual(row['audio_codec'], 'mp4a.40.2')
            for key in ('bytes', 'fps', 'video_codec'): self.assertIsNone(row[key])
            fixture = root/'rewrite/android/app/src/androidTest/assets'/row['url'].rsplit('/', 1)[1]
            self.assertEqual(fixture.stat().st_size, size)
            self.assertEqual(hashlib.sha256(fixture.read_bytes()).hexdigest(), sha)
