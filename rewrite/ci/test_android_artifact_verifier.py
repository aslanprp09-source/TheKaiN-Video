from copy import deepcopy
from datetime import datetime, timezone
import hashlib
import io
import json
import os
from pathlib import Path
import shutil
import ssl
import stat
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import urllib.error
import warnings
import zipfile

import android_artifact_verifier as v

PIN = v.parse_json(v.SOURCE.read_bytes())
SHA = 'a' * 40
ENV = {'GITHUB_SHA': SHA, 'GITHUB_REPOSITORY': PIN['repository'],
       'GITHUB_REF': 'refs/heads/' + PIN['branch'], 'GITHUB_EVENT_NAME': 'workflow_dispatch',
       'RUNNER_ENVIRONMENT': 'github-hosted', 'GITHUB_RUN_ID': '123', 'BYPASS_PREFLIGHT_COMMENT_ID': '789'}
APK = next(n for n in PIN['files'] if n.endswith('.apk'))
AAB = next(n for n in PIN['files'] if n.endswith('.aab'))


def repin(pin, raw):
    pin['files'] = {n: {'bytes': len(b), 'sha256': hashlib.sha256(b).hexdigest()} for n, b in raw.items()}


def fixture(root, apk=b'owned APK fixture', aab=b'owned AAB fixture', certificate=None):
    pin = deepcopy(PIN)
    pin['certificate_sha256'] = certificate or PIN['certificate_sha256']
    raw = {APK: apk, AAB: aab, 'inspection.json': json.dumps({'signed': True,
        'debuggable': False, 'application_id': 'com.thekain.video.rebuild', 'version_code': 2,
        'version_name': '0.1.0-m6', 'certificate_sha256': pin['certificate_sha256'],
        'production_ready': False}).encode()}
    repin(pin, raw)
    records = [{'file': n, **d} for n, d in sorted(pin['files'].items())]
    raw['manifest.json'] = json.dumps({'schema': 1, 'source_commit': pin['source_commit'],
        'version': '0.1.0-m6', 'platform': 'android', 'channel': 'signed-internal-qa',
        'production_ready': False, 'files': records}).encode()
    raw['SHA256SUMS'] = ''.join(f"{r['sha256']}  {r['file']}\n" for r in records).encode()
    repin(pin, raw)
    archive = root / 'fixture.zip'
    write_zip(archive, raw)
    pin['zip_bytes'], pin['zip_sha256'] = archive.stat().st_size, v.digest(archive)
    return pin, archive, raw


def write_zip(path, raw):
    with zipfile.ZipFile(path, 'w', compression=zipfile.ZIP_DEFLATED) as archive:
        for n, data in raw.items():
            archive.writestr(n, data)


class FakeAPI:
    def __init__(self, pin, archive=None):
        self.pin, self.archive = pin, archive
        self.base = 'https://api.github.com/repos/' + pin['repository']
        self.run = {'id': pin['run_id'], 'run_number': pin['run_number'], 'head_branch': pin['branch'],
            'head_sha': pin['source_commit'], 'status': 'completed', 'conclusion': 'success',
            'event': 'workflow_dispatch', 'path': '.github/workflows/rewrite-ci.yml',
            'repository': {'full_name': pin['repository'], 'id': pin['repository_id']}}
        self.artifact = {'id': pin['artifact_id'], 'name': pin['artifact_name'],
            'size_in_bytes': pin['zip_bytes'], 'digest': 'sha256:' + pin['zip_sha256'],
            'expired': False, 'expires_at': '2099-01-01T00:00:00Z',
            'workflow_run': {'id': pin['run_id'], 'repository_id': pin['repository_id'],
                'head_repository_id': pin['repository_id'], 'head_branch': pin['branch'],
                'head_sha': pin['source_commit']}}
        self.pr = {'number': 22, 'merged_at': '2026-11-16T00:00:00Z', 'merge_commit_sha': SHA,
            'base': {'ref': pin['branch'], 'repo': {'full_name': pin['repository']}},
            'head': {'sha': 'b' * 40, 'ref': 'owned-verifier', 'repo': {'full_name': pin['repository']}},
            'user': {'id': 352417087}}
        self.ci_run = {'id': 123, 'workflow_id': v.CI_WORKFLOW_ID, 'event': 'pull_request',
            'head_sha': 'b' * 40, 'head_branch': 'owned-verifier', 'status': 'completed',
            'conclusion': 'success', 'path': '.github/workflows/rewrite-ci.yml',
            'repository': {'full_name': pin['repository']},
            'head_repository': {'full_name': pin['repository']}}
        self.ci_jobs = [{'name': name, 'status': 'completed', 'conclusion': 'success'} for name in v.REQUIRED_CI]
        self.review = {'id': 99, 'state': 'APPROVED', 'commit_id': 'b' * 40,
            'user': {'id': 355790290, 'login': 'KaiN1One'}}
        self.rules = [{'type': k, 'ruleset_id': 1} for k in ('deletion', 'non_fast_forward')]
        self.rules += [{'type': 'pull_request', 'ruleset_id': 1, 'parameters': {
            'required_approving_review_count': 1, 'dismiss_stale_reviews_on_push': True,
            'require_last_push_approval': True, 'required_review_thread_resolution': True}},
            {'type': 'required_status_checks', 'ruleset_id': 1, 'parameters': {
                'strict_required_status_checks_policy': True,
                'required_status_checks': [{'context': n, 'integration_id': 15368} for n in v.REQUIRED_CI]}}]
        self.ruleset = {'id': 1, 'name': 'owned-protection', 'target': 'branch',
            'source_type': 'Repository', 'source': pin['repository'],
            'enforcement': 'active', 'bypass_actors': [],
            'created_at': '2026-11-13T00:00:00Z', 'updated_at': '2026-11-13T00:00:00Z',
            'conditions': {'ref_name': {'include': ['refs/heads/' + pin['branch']], 'exclude': []}},
            'rules': deepcopy(self.rules)}
        self.downloaded = False
        timestamp = datetime.now(timezone.utc).replace(microsecond=0).isoformat()
        self.comment = {'id': 789, 'user': {'login': 'aslanprp09-source', 'id': 352417087},
            'issue_url': self.base + '/issues/6', 'created_at': timestamp, 'updated_at': timestamp,
            'body': json.dumps(v.audit_bypass_before_launch(self, pin, SHA))}

    def json(self, path):
        if path.startswith('/issues/comments/'): return self.comment
        if path.startswith('/git/ref/'): return {'object': {'sha': SHA}}
        if path.startswith('/actions/runs/'): return self.run
        if path.startswith('/actions/artifacts/'): return self.artifact
        if path.startswith('/rules/'): return self.rules
        if path.startswith('/rulesets/'): return self.ruleset
        raise AssertionError(path)

    def pages(self, path, field=None):
        if path.startswith('/rules/branches/'): return self.rules
        if path.startswith('/actions/workflows/'): return [self.ci_run]
        if path.endswith('/jobs'): return self.ci_jobs
        if path.endswith('/artifacts'): return [self.artifact]
        if path.startswith('/commits/'): return [self.pr]
        if path.startswith('/pulls/'): return [self.review]
        raise AssertionError(path)

    def download(self, artifact_id, target, limit):
        self.downloaded = True
        shutil.copyfile(self.archive, target)


class VerifierTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.pin, self.archive, self.raw = fixture(self.root)
        self.api = FakeAPI(self.pin, self.archive)

    def contents(self, pin=None):
        folder = self.root / 'extracted'
        folder.mkdir(exist_ok=True)
        return v.extract_contents(self.archive, folder, pin or self.pin, {})

    def test_source_pin_matches_previous_evidence_and_owner_digests(self):
        evidence = json.loads((v.SOURCE.parents[1] / 'release/evidence/android-signing-run-69.json').read_text())
        self.assertEqual(PIN['source_commit'], evidence['source_commit'])
        self.assertEqual(PIN['artifact_id'], evidence['artifact']['id'])
        self.assertEqual(PIN['zip_sha256'], evidence['artifact']['zip_sha256'])
        self.assertEqual(PIN['certificate_sha256'], evidence['expected_certificate']['sha256'].lower())

    def test_valid_provenance_original_zip_and_contents(self):
        v.provenance(self.api, self.pin, datetime.now(timezone.utc))
        report = {}
        v.verify_zip(self.archive, self.pin, report)
        self.assertEqual(report['zip']['actual'], report['zip']['expected'])
        self.contents()

    def test_wrong_source_run_fields_rejected(self):
        for key, value in {'id': 1, 'run_number': 1, 'head_branch': 'main', 'head_sha': 'f' * 40,
                'status': 'queued', 'conclusion': 'failure', 'event': 'push', 'path': 'other.yml',
                'repository': {'full_name': 'other/repo', 'id': 1}}.items():
            with self.subTest(key=key):
                api = FakeAPI(self.pin)
                api.run[key] = value
                with self.assertRaises(v.VerificationError):
                    v.provenance(api, self.pin, datetime.now(timezone.utc))

    def test_wrong_artifact_and_expiry_rejected(self):
        for key, value in {'id': 1, 'name': 'substituted', 'size_in_bytes': 1, 'digest': 'sha256:' + '0' * 64,
                           'expired': True, 'expires_at': '2000-01-01T00:00:00Z'}.items():
            with self.subTest(key=key):
                api = FakeAPI(self.pin)
                api.artifact[key] = value
                with self.assertRaises(v.VerificationError):
                    v.provenance(api, self.pin, datetime.now(timezone.utc))
        for key in self.api.artifact['workflow_run']:
            api = FakeAPI(self.pin)
            api.artifact['workflow_run'][key] = 'wrong'
            with self.subTest(source=key), self.assertRaises(v.VerificationError):
                v.provenance(api, self.pin, datetime.now(timezone.utc))
        with patch.object(self.api, 'pages', return_value=[]), self.assertRaises(v.VerificationError):
            v.provenance(self.api, self.pin, datetime.now(timezone.utc))

    def test_review_requires_independent_exact_head_and_protected_current_tip(self):
        self.assertEqual(v.reviewed_execution(self.api, self.pin, ENV)['pr'], 22)
        for key, value in {'GITHUB_REPOSITORY': 'other/repo', 'GITHUB_REF': 'refs/heads/main',
                           'GITHUB_EVENT_NAME': 'pull_request', 'GITHUB_SHA': 'old',
                           'RUNNER_ENVIRONMENT': 'self-hosted'}.items():
            with self.subTest(key=key), self.assertRaises(v.VerificationError):
                v.reviewed_execution(self.api, self.pin, {**ENV, key: value})
        for variant in ('unmerged', 'old-head', 'other-reviewer', 'changes-requested', 'no-rules',
                        'bypass', 'non-strict', 'stale-review', 'self-review'):
            api = FakeAPI(self.pin)
            if variant == 'unmerged': api.pr['merged_at'] = None
            if variant == 'old-head': api.review['commit_id'] = 'c' * 40
            if variant == 'other-reviewer': api.review['user']['id'] = 1
            if variant == 'changes-requested': api.review['state'] = 'CHANGES_REQUESTED'
            if variant == 'no-rules': api.rules = []
            if variant == 'bypass': api.ruleset['bypass_actors'] = [{'actor_id': 1}]
            if variant == 'non-strict': api.rules[-1]['parameters']['strict_required_status_checks_policy'] = False
            if variant == 'stale-review': api.rules[-2]['parameters']['dismiss_stale_reviews_on_push'] = False
            if variant == 'self-review': api.pr['user']['id'] = 355790290
            with self.subTest(variant=variant), self.assertRaises(v.VerificationError):
                v.reviewed_execution(api, self.pin, ENV)

    def test_corrupt_zip_bytes_and_size_are_rejected(self):
        for key, value in (('zip_bytes', 1), ('zip_sha256', '0' * 64)):
            with self.subTest(key=key), self.assertRaises(v.VerificationError):
                v.verify_zip(self.archive, {**self.pin, key: value}, {})
        self.archive.write_bytes(b'not a ZIP')
        with self.assertRaises(zipfile.BadZipFile): self.contents()

    def test_read_only_hidden_bypass_metadata_is_explicit_and_does_not_skip_actual_ci(self):
        self.api.ruleset.pop('bypass_actors')
        details = v.reviewed_execution(self.api, self.pin, ENV)
        configuration = details['rulesets'][0]['bypass_configuration']
        self.assertEqual(configuration['status'], 'PASS')
        self.assertFalse(configuration['direct_field_visible'])
        self.assertEqual(configuration['preflight_comment_id'], 789)
        self.assertEqual(details['required_ci']['run_id'], 123)
        self.api.ci_jobs[0]['conclusion'] = 'skipped'
        with self.assertRaisesRegex(v.VerificationError, 'REQUIRED_CI_JOB_NOT_SUCCESSFUL'):
            v.reviewed_execution(self.api, self.pin, ENV)

    def test_latest_approved_head_ci_must_be_the_real_successful_workflow(self):
        for key, value in {'workflow_id': 1, 'event': 'workflow_dispatch', 'head_sha': 'c' * 40,
            'head_branch': 'other', 'status': 'in_progress', 'conclusion': 'failure', 'path': 'other.yml',
            'repository': {'full_name': 'other/repo'}, 'head_repository': {'full_name': 'fork/repo'}}.items():
            api = FakeAPI(self.pin)
            api.ci_run[key] = value
            with self.subTest(key=key), self.assertRaises(v.VerificationError):
                v.reviewed_execution(api, self.pin, ENV)
        with patch.object(self.api, 'pages', return_value=[]), self.assertRaises(v.VerificationError):
            v.approved_head_ci(self.api, self.pin, self.api.pr)
        for variant in ('failed', 'skipped', 'missing', 'duplicate'):
            api = FakeAPI(self.pin)
            if variant in ('failed', 'skipped'): api.ci_jobs[0]['conclusion'] = variant
            if variant == 'missing': api.ci_jobs.pop()
            if variant == 'duplicate': api.ci_jobs.append(deepcopy(api.ci_jobs[0]))
            with self.subTest(variant=variant), self.assertRaises(v.VerificationError):
                v.reviewed_execution(api, self.pin, ENV)
        api = FakeAPI(self.pin)
        api.pr['head']['repo']['full_name'] = 'fork/repo'
        with self.assertRaises(v.VerificationError): v.reviewed_execution(api, self.pin, ENV)

    def test_external_no_bypass_preflight_requires_visible_empty_metadata_and_current_tip(self):
        audit = v.audit_bypass_before_launch(self.api, self.pin, SHA)
        self.assertEqual(audit['status'], 'PASS')
        self.assertEqual(audit['rulesets'][0]['bypass_actors'], [])
        self.assertEqual(len(audit['rulesets'][0]['public_sha256']), 64)
        self.assertFalse(self.api.downloaded)
        for variant in ('hidden', 'bypass', 'inactive', 'no-rules', 'old-tip'):
            api, commit = FakeAPI(self.pin), SHA
            if variant == 'hidden': api.ruleset.pop('bypass_actors')
            if variant == 'bypass': api.ruleset['bypass_actors'] = [{'actor_id': 1}]
            if variant == 'inactive': api.ruleset['enforcement'] = 'disabled'
            if variant == 'no-rules': api.rules = []
            if variant == 'old-tip': commit = 'c' * 40
            with self.subTest(variant=variant), self.assertRaises(v.VerificationError):
                v.audit_bypass_before_launch(api, self.pin, commit)

    def test_ruleset_revision_timestamps_are_canonical_across_equivalent_timezones(self):
        original = v.public_ruleset_digest(self.api.ruleset)
        self.api.ruleset['created_at'] = '2026-11-13T05:00:00.000+05:00'
        self.api.ruleset['updated_at'] = '2026-11-13T05:00:00+05:00'
        self.assertEqual(original, v.public_ruleset_digest(self.api.ruleset))
        v.reviewed_execution(self.api, self.pin, ENV)

    def test_hidden_configuration_requires_bound_external_proof_in_fixture_report(self):
        self.api.ruleset.pop('bypass_actors')
        with patch.object(v, 'tool_versions', return_value={}), patch.object(v, 'verify_apk', return_value={}), \
                patch.object(v, 'verify_aab', return_value={}):
            report = v.execute(self.pin, self.api, ENV, self.root, self.root, self.root / 'hidden-report')
        self.assertEqual(report['overall_status'], 'PASS')
        config = report['checks']['review']['details']['rulesets'][0]['bypass_configuration']
        self.assertFalse(config['direct_field_visible'])
        self.assertEqual(config['preflight_comment_id'], 789)
        self.api.comment['user']['id'] = 1
        with patch.object(v, 'tool') as tool:
            report = v.execute(self.pin, self.api, ENV, self.root, self.root, self.root / 'bad-proof')
        self.assertEqual(report['overall_status'], 'FAIL')
        self.assertEqual(report['checks']['provenance']['status'], 'NOT_RUN')
        tool.assert_not_called()

    def test_preflight_comment_identity_freshness_context_and_ruleset_revision_fail_closed(self):
        for variant in ('missing', 'injection', 'other-comment-id', 'other-author', 'other-issue', 'edited', 'expired',
                'wrong-commit', 'wrong-repository', 'old-audit', 'changed-revision', 'changed-public-rules',
                'bypass-proof', 'missing-ruleset'):
            api, env = FakeAPI(self.pin), dict(ENV)
            proof = json.loads(api.comment['body'])
            if variant == 'missing': env['BYPASS_PREFLIGHT_COMMENT_ID'] = ''
            if variant == 'injection': env['BYPASS_PREFLIGHT_COMMENT_ID'] = '789/../../secrets'
            if variant == 'other-comment-id': api.comment['id'] = 1
            if variant == 'other-author': api.comment['user']['id'] = 1
            if variant == 'other-issue': api.comment['issue_url'] = api.base + '/issues/7'
            if variant == 'edited': api.comment['updated_at'] = '2099-01-01T00:00:00Z'
            if variant == 'expired': api.comment['created_at'] = api.comment['updated_at'] = '2000-01-01T00:00:00Z'
            if variant == 'wrong-commit': proof['verifier_commit'] = 'f' * 40
            if variant == 'wrong-repository': proof['repository'] = 'other/repo'
            if variant == 'old-audit': proof['audited_at'] = '2000-01-01T00:00:00Z'
            if variant == 'changed-revision': api.ruleset['updated_at'] = '2026-11-16T00:00:00Z'
            if variant == 'changed-public-rules': api.ruleset['conditions']['ref_name']['exclude'] = ['*']
            if variant == 'bypass-proof': proof['rulesets'][0]['bypass_actors'] = [{'actor_id': 1}]
            if variant == 'missing-ruleset': proof['rulesets'] = []
            api.comment['body'] = json.dumps(proof)
            with self.subTest(variant=variant), self.assertRaises(v.VerificationError):
                v.reviewed_execution(api, self.pin, env)

    def test_cli_prints_safe_stage_errors_when_artifact_report_download_is_unavailable(self):
        import contextlib
        self.api.ruleset['bypass_actors'] = [{'actor_id': 1}]
        stdout = io.StringIO()
        args = ['android_artifact_verifier.py', '--output', str(self.root / 'cli-result')]
        with patch.object(v, 'GitHub', return_value=self.api), patch.dict(os.environ, ENV), \
                patch('sys.argv', args), contextlib.redirect_stdout(stdout):
            status = v.main()
        self.assertEqual(status, 1)
        self.assertIn('NO_BYPASS_RULESET_REQUIRED', stdout.getvalue())
        self.assertIn('"provenance": {"status": "NOT_RUN"}', stdout.getvalue())

    def test_unsafe_archive_entries_are_rejected_without_extraction(self):
        for variant in ('traversal', 'absolute', 'backslash', 'unexpected', 'duplicate', 'symlink', 'directory', 'oversized'):
            raw = dict(self.raw)
            if variant in ('traversal', 'absolute', 'backslash', 'unexpected'):
                raw[{'traversal': '../outside', 'absolute': '/outside', 'backslash': '..\\outside',
                     'unexpected': 'execute.py'}[variant]] = raw.pop(APK)
            write_zip(self.archive, raw)
            if variant in ('duplicate', 'symlink', 'directory', 'oversized'):
                info = zipfile.ZipInfo(APK)
                if variant == 'symlink': info.external_attr = (stat.S_IFLNK | 0o777) << 16
                if variant == 'directory': info.external_attr = (stat.S_IFDIR | 0o755) << 16
                with warnings.catch_warnings(), zipfile.ZipFile(self.archive, 'a') as archive:
                    warnings.simplefilter('ignore', UserWarning)
                    archive.writestr(info, b'x' if variant == 'oversized' else self.raw[APK])
            with self.subTest(variant=variant), self.assertRaises(v.VerificationError): self.contents()
        self.assertFalse((self.root / 'outside').exists())

    def test_symlink_and_size_are_checked_even_without_duplicate_entries(self):
        for variant in ('symlink', 'size'):
            with zipfile.ZipFile(self.archive, 'w') as archive:
                for n, raw in self.raw.items():
                    info = zipfile.ZipInfo(n)
                    if n == APK and variant == 'symlink': info.external_attr = (stat.S_IFLNK | 0o777) << 16
                    archive.writestr(info, raw + b'x' if n == APK and variant == 'size' else raw)
            with self.subTest(variant=variant), self.assertRaises(v.VerificationError): self.contents()

    def test_corrupt_file_manifest_checksums_and_inspection_are_rejected(self):
        for variant in ('apk', 'aab', 'manifest-source', 'manifest-digest', 'manifest-file-set', 'checksums', 'inspection', 'duplicate-json'):
            raw, pin = deepcopy(self.raw), deepcopy(self.pin)
            if variant == 'apk': raw[APK] = b'X' + raw[APK][1:]
            if variant == 'aab': raw[AAB] = b'X' + raw[AAB][1:]
            if variant.startswith('manifest'):
                m = json.loads(raw['manifest.json'])
                if variant == 'manifest-source': m['source_commit'] = 'f' * 40
                if variant == 'manifest-digest': m['files'][0]['sha256'] = '0' * 64
                if variant == 'manifest-file-set': m['files'][0]['file'] = '../file'
                raw['manifest.json'] = json.dumps(m).encode()
            if variant == 'checksums': raw['SHA256SUMS'] = b'incorrect'
            if variant == 'inspection': raw['inspection.json'] = b'{}'
            if variant == 'duplicate-json': raw['manifest.json'] = b'{"schema":1,"schema":1}'
            if variant not in ('apk', 'aab'): repin(pin, raw)  # Exercise semantics after file hashes pass.
            write_zip(self.archive, raw)
            with self.subTest(variant=variant), self.assertRaises(v.VerificationError): self.contents(pin)

    def test_apk_single_signer_and_schemes_are_required(self):
        text = ('Number of signers: 1\nSigner #1 certificate SHA-256 digest: ' + PIN['certificate_sha256'] +
                '\nVerified using v2 scheme (APK Signature Scheme v2): true\n'
                'Verified using v3 scheme (APK Signature Scheme v3): true\n')
        with patch.object(v, 'tool', return_value=text):
            self.assertEqual(v.verify_apk(Path('a.apk'), self.root, self.root, PIN['certificate_sha256'])['signers'], 1)
        for invalid in (text.replace('signers: 1', 'signers: 2'), text + 'Signer #2 certificate DN: other\n',
                        text.replace(PIN['certificate_sha256'], '0' * 64), text.replace(': true', ': false'), ''):
            with patch.object(v, 'tool', return_value=invalid), self.assertRaises(v.VerificationError):
                v.verify_apk(Path('a.apk'), self.root, self.root, PIN['certificate_sha256'])

    def test_no_pass_on_failure_or_skipped_stage_and_safe_error_codes(self):
        for fail_stage in v.CHECKS:
            with self.subTest(stage=fail_stage):
                funcs = {'review': 'reviewed_execution', 'provenance': 'provenance', 'zip': 'verify_zip',
                    'contents': 'extract_contents', 'tools': 'tool_versions', 'apk': 'verify_apk', 'aab': 'verify_aab'}
                import contextlib
                with contextlib.ExitStack() as stack:
                    for stage, func in funcs.items():
                        stack.enter_context(patch.object(v, func, side_effect=(RuntimeError('secret-token https://storage/?sig=secret')
                            if stage == fail_stage else None), return_value={'rulesets': []} if stage == 'review' else {}))
                    report = v.execute(self.pin, self.api, ENV, self.root, self.root, self.root / fail_stage)
                self.assertEqual(report['overall_status'], 'FAIL')
                self.assertEqual(report['checks'][fail_stage]['status'], 'FAIL')
                for later in v.CHECKS[v.CHECKS.index(fail_stage) + 1:]:
                    self.assertEqual(report['checks'][later]['status'], 'NOT_RUN')
                self.assertFalse(report['production_ready'])
                self.assertFalse(report['automatic_publication'])
                data = (self.root / fail_stage / 'report.json').read_text()
                self.assertNotIn('secret-token', data)
                self.assertNotIn('sig=secret', data)

    def test_missing_artifact_does_not_download_or_run_tools(self):
        with patch.object(self.api, 'json', side_effect=v.VerificationError('API_HTTP_404')), patch.object(v, 'tool') as tool:
            report = v.execute(self.pin, self.api, ENV, self.root, self.root, self.root / 'out')
        self.assertEqual(report['overall_status'], 'FAIL')
        self.assertFalse(self.api.downloaded)
        tool.assert_not_called()

    def test_api_redirects_never_receive_authorization(self):
        api = v.GitHub(PIN['repository'], 'owned-token')
        url = 'https://owned.blob.core.windows.net/artifact?sig=owned-test'
        redirect = urllib.error.HTTPError(api.base, 302, 'redirect', {'Location': url}, None)
        response = io.BytesIO(b'fixture')
        with patch.object(api.opener, 'open', side_effect=[redirect, response]) as opened:
            api.download(PIN['artifact_id'], self.root / 'download.zip', 7)
        first, second = [c.args[0] for c in opened.call_args_list]
        self.assertEqual(first.get_header('Authorization'), 'Bearer owned-token')
        self.assertIsNone(second.get_header('Authorization'))
        for invalid in ('http://owned.blob.core.windows.net/a', 'https://evil.invalid/a',
                'https://owned.blob.core.windows.net.evil.invalid/a', 'https://user@owned.blob.core.windows.net/a'):
            with self.subTest(url=invalid), self.assertRaises(v.VerificationError): v.storage_url(invalid)

    def test_missing_failing_and_timed_out_tools(self):
        with self.assertRaisesRegex(v.VerificationError, 'TOOL_MISSING'):
            v.tool([self.root / 'missing'], self.root, 'apksigner')
        false = shutil.which('false')
        if false:
            with self.assertRaisesRegex(v.VerificationError, 'APKSIGNER_FAILED'):
                v.tool([false], self.root, 'apksigner')
        with patch.object(v.subprocess, 'run', side_effect=subprocess.TimeoutExpired(['tool'], 180)), \
                self.assertRaisesRegex(v.VerificationError, 'TOOL_TIMEOUT'):
            v.tool([Path(shutil.which('python3'))], self.root, 'apksigner')


class OfficialToolsTest(unittest.TestCase):
    """Real official tools and disposable local fixtures, never run #69 or owner keys."""
    @classmethod
    def setUpClass(cls):
        cls.tools = Path(os.environ.get('TEST_VERIFY_ANDROID_TOOLS',
            str(Path(os.environ.get('ANDROID_HOME', '/missing')) / 'build-tools/35.0.0')))
        cls.java = Path(os.environ.get('JAVA_HOME', '/missing'))
        platform = cls.tools.parents[1] / 'platforms/android-35/android.jar'
        if not all(p.is_file() for p in (cls.tools / 'apksigner', cls.tools / 'aapt',
                cls.java / 'bin/jarsigner', platform)):
            if os.environ.get('GITHUB_ACTIONS') == 'true':
                raise AssertionError('Official verifier fixture tools must be present in CI')
            raise unittest.SkipTest('Set ANDROID_HOME and JAVA_HOME to existing SDK 35/JDK 17 for official-tool fixtures')
        cls.temp = tempfile.TemporaryDirectory(prefix='thekain-disposable-verifier-tests-')
        cls.addClassCleanup(cls.temp.cleanup)
        cls.root = Path(cls.temp.name)
        def run(args):
            r = subprocess.run([str(x) for x in args], capture_output=True, timeout=120)
            if r.returncode: raise AssertionError(r.stderr.decode(errors='replace'))
            return r.stdout.decode(errors='replace')
        key = cls.root / 'disposable.p12'
        run([cls.java / 'bin/keytool', '-genkeypair', '-keystore', key, '-storetype', 'PKCS12',
             '-storepass', 'disposable-test-only', '-keypass', 'disposable-test-only', '-alias', 'test',
             '-keyalg', 'RSA', '-keysize', '2048', '-validity', '2', '-dname', 'CN=Disposable Verifier Fixture'])
        pem = run([cls.java / 'bin/keytool', '-exportcert', '-rfc', '-keystore', key,
                   '-storepass', 'disposable-test-only', '-alias', 'test'])
        cls.certificate = hashlib.sha256(ssl.PEM_cert_to_DER_cert(pem)).hexdigest()
        manifest = cls.root / 'AndroidManifest.xml'
        manifest.write_text('<manifest xmlns:android="http://schemas.android.com/apk/res/android" '
            'package="com.thekain.verifier.fixture" android:versionCode="1" android:versionName="test">'
            '<uses-sdk android:minSdkVersion="26" android:targetSdkVersion="35"/><application/></manifest>')
        unsigned, cls.apk, cls.aab = [cls.root / n for n in ('unsigned.apk', 'signed.apk', 'signed.aab')]
        run([cls.tools / 'aapt', 'package', '-f', '-M', manifest, '-I', platform, '-F', unsigned])
        run([cls.tools / 'apksigner', 'sign', '--ks', key, '--ks-pass', 'pass:disposable-test-only',
             '--out', cls.apk, unsigned])
        with zipfile.ZipFile(cls.aab, 'w') as archive: archive.writestr('base/owned-fixture.txt', 'authorized test fixture')
        run([cls.java / 'bin/jarsigner', '-keystore', key, '-storepass', 'disposable-test-only',
             cls.aab, 'test'])
        key.unlink()  # Private fixture key is no longer needed by any verification.

    def setUp(self):
        self.temp_case = tempfile.TemporaryDirectory(dir=self.root)
        self.addCleanup(self.temp_case.cleanup)
        self.case = Path(self.temp_case.name)
        self.logs = self.case / 'logs'
        self.logs.mkdir()

    def test_real_full_pipeline_with_disposable_fixtures(self):
        pin, archive, _ = fixture(self.case, self.apk.read_bytes(), self.aab.read_bytes(), self.certificate)
        report = v.execute(pin, FakeAPI(pin, archive), ENV, self.tools, self.java, self.case / 'result')
        self.assertEqual(report['overall_status'], 'PASS', report)
        self.assertTrue(all(c['status'] == 'PASS' for c in report['checks'].values()))
        self.assertEqual(report['checks']['apk']['details']['certificate_sha256'], self.certificate)
        self.assertEqual(report['checks']['aab']['details']['certificate_sha256'], self.certificate)
        self.assertFalse(report['production_ready'])
        self.assertFalse(report['automatic_publication'])

    def test_real_apk_tampering_and_wrong_certificate_rejected(self):
        apk = self.case / 'changed.apk'
        shutil.copyfile(self.apk, apk)
        with zipfile.ZipFile(apk, 'a') as archive: archive.writestr('untrusted.txt', 'tampered')
        with self.assertRaises(v.VerificationError):
            v.verify_apk(apk, self.tools, self.logs, self.certificate)
        with self.assertRaisesRegex(v.VerificationError, 'APK_SIGNER_MISMATCH'):
            v.verify_apk(self.apk, self.tools, self.logs, '0' * 64)

    def test_real_aab_strict_rejects_unsigned_and_changed_entries(self):
        for variant in ('unsigned', 'changed'):
            aab = self.case / (variant + '.aab')
            with zipfile.ZipFile(self.aab) as original, zipfile.ZipFile(aab, 'w') as changed:
                for info in original.infolist():
                    raw = original.read(info)
                    if variant == 'changed' and info.filename == 'base/owned-fixture.txt': raw = b'changed'
                    changed.writestr(info, raw)
                if variant == 'unsigned': changed.writestr('base/unsigned.txt', b'unsigned data')
            trust = self.case / variant
            trust.mkdir()
            with self.subTest(variant=variant), self.assertRaises(v.VerificationError):
                v.verify_aab(aab, self.java, self.logs, self.certificate, trust)

    def test_aab_wrong_certificate_is_never_imported(self):
        with patch.object(v, 'tool', wraps=v.tool) as tool, self.assertRaisesRegex(v.VerificationError, 'AAB_CERTIFICATE_MISMATCH'):
            v.verify_aab(self.aab, self.java, self.logs, '0' * 64, self.case)
        self.assertEqual(tool.call_count, 1)
        self.assertFalse((self.case / 'public-trust.p12').exists())

    def test_aab_multiple_signer_blocks_rejected(self):
        aab = self.case / 'multiple.aab'
        shutil.copyfile(self.aab, aab)
        with zipfile.ZipFile(aab, 'a') as archive: archive.writestr('META-INF/OTHER.RSA', b'other signer')
        with self.assertRaisesRegex(v.VerificationError, 'AAB_AMBIGUOUS_SIGNERS'):
            v.verify_aab(aab, self.java, self.logs, self.certificate, self.case)


if __name__ == '__main__':
    unittest.main()
