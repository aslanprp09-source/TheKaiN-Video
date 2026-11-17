"""Independent read-only official-tool verification of pinned QA89; no signing."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import tempfile
import zipfile

import android_artifact_verifier as original

SOURCE = Path(__file__).with_name('android-qa89-source.json')
CHECKS = ('review', 'provenance', 'zip', 'contents', 'tools', 'manifest', 'apk', 'aab')
require = original.require


def load_pin():
    pin = original.parse_json(SOURCE.read_bytes())
    require((pin['run_id'], pin['artifact_id'], pin['version_code']) ==
            (44031800034, 14306540310, 3), 'QA89_FIXED_SOURCE_REQUIRED')
    return pin


def contents(path, folder, pin, report):
    expected = set(pin['files']) | {'manifest.json', 'inspection.json', 'SHA256SUMS'}
    actual = {}
    report['files'] = actual
    with zipfile.ZipFile(path) as archive:
        entries = archive.infolist()
        require(len(entries) == 5 and {e.filename for e in entries} == expected, 'UNSAFE_ZIP_ENTRIES')
        require(sum(e.file_size for e in entries) <= 32 * 1024 * 1024, 'ZIP_EXPANSION_LIMIT')
        for entry in entries:
            name = entry.filename
            limit = pin['files'][name]['bytes'] if name in pin['files'] else 65536
            require(name == entry.orig_filename and '/' not in name and '\\' not in name
                    and not entry.is_dir() and stat.S_IFMT(entry.external_attr >> 16) in (0, stat.S_IFREG)
                    and not entry.flag_bits & 1 and entry.compress_type in (0, 8)
                    and 0 < entry.file_size <= limit, 'UNSAFE_ZIP_ENTRY')
            with archive.open(entry) as stream:
                raw = stream.read(limit + 1)  # Full bounded read also verifies CRC.
            require(len(raw) == entry.file_size, 'FILE_SIZE_MISMATCH')
            actual[name] = {'bytes': len(raw), 'sha256': hashlib.sha256(raw).hexdigest()}
            if name in pin['files']:
                require(actual[name] == pin['files'][name], 'FILE_SIZE_OR_DIGEST_MISMATCH')
            (folder / name).write_bytes(raw)
    manifest = original.parse_json((folder / 'manifest.json').read_bytes())
    require(all(manifest.get(k) == v for k, v in {
        'schema': 1, 'source_commit': pin['source_commit'], 'version': '0.1.0-m6',
        'platform': 'android', 'channel': 'signed-internal-qa', 'production_ready': False
    }.items()), 'MANIFEST_SOURCE_MISMATCH')
    records = manifest.get('files', [])
    require(len(records) == 3 and {r['file'] for r in records} == expected - {'manifest.json', 'SHA256SUMS'},
            'MANIFEST_FILE_SET_MISMATCH')
    for record in records:
        require(record == {'file': record['file'], **actual[record['file']]}, 'MANIFEST_DIGEST_MISMATCH')
    sums = ''.join(f"{r['sha256']}  {r['file']}\n" for r in records)
    require((folder / 'SHA256SUMS').read_bytes() == sums.encode(), 'CHECKSUMS_MISMATCH')
    inspection = original.parse_json((folder / 'inspection.json').read_bytes())
    require(all(inspection.get(k) == v for k, v in {
        'signed': True, 'debuggable': False, 'application_id': 'com.thekain.video.rebuild',
        'version_code': 3, 'version_name': '0.1.0-m6',
        'certificate_sha256': pin['certificate_sha256'], 'production_ready': False
    }.items()), 'INSPECTION_MISMATCH')
    report['files'] = actual
    return {'files': len(actual), 'crc_and_manifest': 'PASS', 'version_code': 3}


def apk_manifest(apk, tools, output):
    text = original.tool([tools / 'aapt', 'dump', 'badging', apk], output, 'apk-manifest')
    identity = re.findall(r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", text, re.M)
    require(identity == [('com.thekain.video.rebuild', '3', '0.1.0-m6')]
            and 'application-debuggable' not in text, 'APK_IDENTITY_MISMATCH')
    require(re.findall(r"^sdkVersion:'(\d+)'", text, re.M) == ['26'] and
            re.findall(r"^targetSdkVersion:'(\d+)'", text, re.M) == ['35'], 'APK_SDK_MISMATCH')
    return {'application_id': identity[0][0], 'version_code': 3, 'min_sdk': 26, 'target_sdk': 35,
            'debuggable': False}


def reviewed_execution(api, pin, env):
    proof = original.reviewed_execution(api, pin, env)
    head = api.json('/commits/' + proof['reviewed_head'])
    reviews = api.pages(f'/pulls/{proof["pr"]}/reviews')
    review = next((r for r in reviews if r.get('id') == proof['review_id']), {})
    submitted = datetime.fromisoformat(original.canonical_timestamp(review.get('submitted_at', '')))
    committed = datetime.fromisoformat(original.canonical_timestamp(head['commit']['committer']['date']))
    require(submitted >= committed, 'REVIEW_PREDATES_CURRENT_HEAD')
    return {**proof, 'review_submitted_at': submitted.isoformat(), 'head_committed_at': committed.isoformat()}


def execute(pin, api, env, tools, java, output):
    output.mkdir(parents=True, exist_ok=False)
    report = {'schema': 1, 'scope': 'independent-official-tools-run-89', 'source': pin,
        'verifier_commit': env.get('GITHUB_SHA'), 'verifier_run_id': env.get('GITHUB_RUN_ID'),
        'created_at': datetime.now(timezone.utc).isoformat(), 'overall_status': 'FAIL',
        'checks': {k: {'status': 'NOT_RUN'} for k in CHECKS},
        'production_ready': False, 'automatic_publication': False,
        'remaining': {'physical_matrix': 'BLOCKED', 'runtime_16k': 'BLOCKED', 'other_M6': 'BLOCKED'}}
    stage = CHECKS[0]
    try:
        with tempfile.TemporaryDirectory(prefix='thekain-qa89-verify-') as directory:
            temp = Path(directory)
            def check(name, operation):
                nonlocal stage
                stage = name
                report['checks'][name] = {'status': 'PASS', 'details': operation()}
            check('review', lambda: reviewed_execution(api, pin, env))
            check('provenance', lambda: original.provenance(api, pin, datetime.now(timezone.utc)))
            archive = temp / 'original.zip'
            def download():
                api.download(pin['artifact_id'], archive, pin['zip_bytes'])
                original.verify_zip(archive, pin, report)
                return {'original_bytes': True, 'sha256': original.digest(archive)}
            check('zip', download)
            folder = temp / 'files'; folder.mkdir()
            check('contents', lambda: contents(archive, folder, pin, report))
            check('tools', lambda: original.tool_versions(tools, java, output))
            apk = folder / next(n for n in pin['files'] if n.endswith('.apk'))
            aab = folder / next(n for n in pin['files'] if n.endswith('.aab'))
            check('manifest', lambda: apk_manifest(apk, tools, output))
            check('apk', lambda: original.verify_apk(apk, tools, output, pin['certificate_sha256'], report))
            check('aab', lambda: original.verify_aab(aab, java, output, pin['certificate_sha256'], temp, report))
            require(all(x['status'] == 'PASS' for x in report['checks'].values()), 'INCOMPLETE_VERIFICATION')
            report['overall_status'] = 'PASS'
    except Exception as error:
        code = str(error) if isinstance(error, original.VerificationError) else (
            'ZIP_INTEGRITY_FAILED' if isinstance(error, zipfile.BadZipFile) else 'UNEXPECTED_VERIFICATION_ERROR')
        if not re.fullmatch('[A-Z0-9_]+', code): code = 'UNEXPECTED_VERIFICATION_ERROR'
        report['checks'][stage] = {'status': 'FAIL', 'error_code': code}
    finally:
        (output / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
        summary = '# Independent APK89 verification\n\n' + json.dumps(report, indent=2) + '\n'
        (output / 'summary.md').write_text(summary)
        if env.get('GITHUB_STEP_SUMMARY'):
            with Path(env['GITHUB_STEP_SUMMARY']).open('a') as stream: stream.write(summary)
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--audit-bypass-commit')
    args = parser.parse_args()
    pin = load_pin()
    api = original.GitHub(pin['repository'], os.environ.get('GH_TOKEN', ''))
    if args.audit_bypass_commit:
        try:
            report = original.audit_bypass_before_launch(api, pin, args.audit_bypass_commit)
        except Exception as error:
            report = {'status': 'FAIL', 'error_code': str(error) if isinstance(error, original.VerificationError)
                      else 'UNEXPECTED_PREFLIGHT_ERROR', 'production_ready': False}
        args.output.mkdir(parents=True, exist_ok=False)
        (args.output / 'launch-preflight.json').write_text(json.dumps(report, indent=2) + '\n')
        print(json.dumps(report))
        return 0 if report['status'] == 'PASS' else 1
    report = execute(pin, api, os.environ,
        Path(os.environ.get('ANDROID_HOME', '/missing')) / 'build-tools/35.0.0',
        Path(os.environ.get('JAVA_HOME', '/missing')), args.output)
    print('Independent QA89 verifier: ' + report['overall_status'])
    print(json.dumps({'checks': report['checks']}))
    return 0 if report['overall_status'] == 'PASS' else 1


if __name__ == '__main__':
    raise SystemExit(main())
