"""Read-only, fail-closed verification of the original run #69 archive.

No build, signing operation, artifact code execution or private key is needed.
Only the CLI loads the committed source pins; tests inject disposable fixtures.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import ssl
import stat
import subprocess
import tempfile
import urllib.error
import urllib.parse
import urllib.request
import zipfile

SOURCE = Path(__file__).with_name('android-verifier-source.json')
CHECKS = ('review', 'provenance', 'zip', 'contents', 'tools', 'apk', 'aab')
REQUIRED_CI = {'ci-policy', 'windows-tests', 'android-debug', 'android-device-tests'}
MAX_JSON = 4 * 1024 * 1024
PUBLIC_PASSWORD = 'public-certificate-only'


class VerificationError(RuntimeError):
    pass


def require(condition, code):
    if not condition:
        raise VerificationError(code)


def unique_pairs(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, 'DUPLICATE_JSON_KEY')
        result[key] = value
    return result


def parse_json(raw):
    return json.loads(raw, object_pairs_hook=unique_pairs)


def digest(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def storage_url(url):
    parsed = urllib.parse.urlsplit(url)
    require(parsed.scheme == 'https' and not parsed.username and not parsed.password
            and parsed.port in (None, 443) and not parsed.fragment
            and any((parsed.hostname or '').endswith(suffix) for suffix in
                    ('.blob.core.windows.net', '.actions.githubusercontent.com')),
            'UNTRUSTED_DOWNLOAD_REDIRECT')
    return url


class GitHub:
    def __init__(self, repository, token):
        self.base = 'https://api.github.com/repos/' + repository
        self.token = token
        self.opener = urllib.request.build_opener(NoRedirect)

    def request(self, path):
        # Paths are code-generated, never sourced from API fields or artifact data.
        require(path.startswith('/') and not path.startswith('//'), 'INVALID_API_PATH')
        request = urllib.request.Request(self.base + path, headers={
            'Authorization': 'Bearer ' + self.token,
            'Accept': 'application/vnd.github+json', 'X-GitHub-Api-Version': '2022-11-28',
            'User-Agent': 'TheKaiN-independent-artifact-verifier'})
        return self.opener.open(request, timeout=60)

    def json(self, path):
        try:
            with self.request(path) as response:
                raw = response.read(MAX_JSON + 1)
            require(len(raw) <= MAX_JSON, 'API_RESPONSE_TOO_LARGE')
            return parse_json(raw)
        except urllib.error.HTTPError as error:
            raise VerificationError('API_HTTP_' + str(error.code)) from None
        except (urllib.error.URLError, TimeoutError):
            raise VerificationError('API_UNAVAILABLE') from None

    def pages(self, path, field=None):
        result = []
        for page in range(1, 11):
            separator = '&' if '?' in path else '?'
            data = self.json(f'{path}{separator}per_page=100&page={page}')
            items = data[field] if field else data
            require(isinstance(items, list), 'INVALID_API_LIST')
            result.extend(items)
            if len(items) < 100:
                return result
        raise VerificationError('API_PAGINATION_LIMIT')

    def download(self, artifact_id, target, limit):
        try:
            try:
                response = self.request(f'/actions/artifacts/{artifact_id}/zip')
            except urllib.error.HTTPError as error:
                require(error.code == 302, 'ARTIFACT_DOWNLOAD_UNAVAILABLE')
                url = storage_url(error.headers.get('Location', ''))
                # Do not forward Authorization to storage, nor follow arbitrary redirects.
                response = self.opener.open(urllib.request.Request(url), timeout=60)
            with response, Path(target).open('xb') as output:
                total = 0
                while chunk := response.read(1024 * 1024):
                    total += len(chunk)
                    require(total <= limit, 'ZIP_DOWNLOAD_TOO_LARGE')
                    output.write(chunk)
        except (urllib.error.URLError, TimeoutError):
            raise VerificationError('ARTIFACT_DOWNLOAD_UNAVAILABLE') from None


def reviewed_execution(api, pin, env):
    sha = env.get('GITHUB_SHA', '')
    require(env.get('GITHUB_REPOSITORY') == pin['repository']
            and env.get('GITHUB_EVENT_NAME') == 'workflow_dispatch'
            and env.get('GITHUB_REF') == 'refs/heads/' + pin['branch']
            and env.get('RUNNER_ENVIRONMENT') == 'github-hosted'
            and re.fullmatch('[0-9a-f]{40}', sha), 'UNTRUSTED_VERIFIER_CONTEXT')
    tip = api.json('/git/ref/heads/' + pin['branch'])
    require(tip['object']['sha'] == sha, 'VERIFIER_CURRENT_TIP_REQUIRED')
    prs = api.pages('/commits/' + sha + '/pulls')
    matches = [p for p in prs if p.get('merged_at') and p.get('merge_commit_sha') == sha
               and p.get('base', {}).get('ref') == pin['branch']
               and p.get('base', {}).get('repo', {}).get('full_name') == pin['repository']]
    require(len(matches) == 1, 'MERGED_VERIFIER_PR_REQUIRED')
    pr = matches[0]
    reviews = api.pages(f'/pulls/{pr["number"]}/reviews')
    independent = [r for r in reviews if r.get('user', {}).get('login') == 'KaiN1One'
                   and r.get('user', {}).get('id') == 355790290]
    latest = max(independent, key=lambda r: r['id'], default={})
    require(latest.get('state') == 'APPROVED' and latest.get('commit_id') == pr['head']['sha']
            and pr['user']['id'] != 355790290, 'INDEPENDENT_CURRENT_HEAD_REVIEW_REQUIRED')
    # Required checks belong to the PR commit or its GitHub test merge commit.
    # Server-side strict protection governs the merge; read its effective rules too.
    rules = api.json('/rules/branches/' + pin['branch'])
    by_type = {r['type']: r for r in rules}
    require({'deletion', 'non_fast_forward', 'pull_request', 'required_status_checks'} <= set(by_type),
            'BRANCH_PROTECTION_REQUIRED')
    review_policy = by_type['pull_request']['parameters']
    require(review_policy.get('required_approving_review_count', 0) >= 1 and all(
        review_policy.get(k) is True for k in ('dismiss_stale_reviews_on_push',
        'require_last_push_approval', 'required_review_thread_resolution')), 'BRANCH_REVIEW_POLICY_REQUIRED')
    checks_policy = by_type['required_status_checks']['parameters']
    require(checks_policy.get('strict_required_status_checks_policy') is True
            and REQUIRED_CI <= {c['context'] for c in checks_policy['required_status_checks']
                               if c.get('integration_id') == 15368}, 'STRICT_REQUIRED_CI_REQUIRED')
    for ruleset_id in {r['ruleset_id'] for r in rules}:
        ruleset = api.json(f'/rulesets/{ruleset_id}')
        require(ruleset.get('enforcement') == 'active' and ruleset.get('bypass_actors') == [],
                'NO_BYPASS_RULESET_REQUIRED')
    return {'pr': pr['number'], 'review_id': latest['id'], 'reviewed_head': pr['head']['sha'],
            'verifier_commit': sha}


def provenance(api, pin, now):
    run = api.json(f'/actions/runs/{pin["run_id"]}')
    require(all(run.get(k) == v for k, v in {
        'id': pin['run_id'], 'run_number': pin['run_number'], 'head_branch': pin['branch'],
        'head_sha': pin['source_commit'], 'status': 'completed', 'conclusion': 'success',
        'event': 'workflow_dispatch', 'path': '.github/workflows/rewrite-ci.yml'}.items())
        and run.get('repository', {}).get('full_name') == pin['repository']
        and run['repository'].get('id') == pin['repository_id'], 'SOURCE_RUN_MISMATCH')
    artifact = api.json(f'/actions/artifacts/{pin["artifact_id"]}')
    listing = api.pages(f'/actions/runs/{pin["run_id"]}/artifacts', 'artifacts')
    require(sum(a.get('id') == pin['artifact_id'] for a in listing) == 1,
            'ARTIFACT_NOT_IN_SOURCE_RUN')
    for item in (artifact, next(a for a in listing if a.get('id') == pin['artifact_id'])):
        require(all(item.get(k) == v for k, v in {'id': pin['artifact_id'],
            'name': pin['artifact_name'], 'size_in_bytes': pin['zip_bytes'],
            'digest': 'sha256:' + pin['zip_sha256'], 'expired': False}.items()),
            'ARTIFACT_METADATA_MISMATCH')
        require(all(item.get('workflow_run', {}).get(k) == v for k, v in {
            'id': pin['run_id'], 'repository_id': pin['repository_id'],
            'head_repository_id': pin['repository_id'], 'head_branch': pin['branch'],
            'head_sha': pin['source_commit']}.items()), 'ARTIFACT_SOURCE_MISMATCH')
        expiry = datetime.fromisoformat(item.get('expires_at', '').replace('Z', '+00:00'))
        require(expiry > now, 'ARTIFACT_EXPIRED')
    return {'run_id': run['id'], 'artifact_id': artifact['id'], 'expired': False,
            'expires_at': artifact['expires_at'], 'digest': artifact['digest'],
            'size_in_bytes': artifact['size_in_bytes']}


def verify_zip(path, pin, report):
    actual = {'bytes': path.stat().st_size, 'sha256': digest(path)}
    report['zip'] = {'expected': {'bytes': pin['zip_bytes'], 'sha256': pin['zip_sha256']},
                     'actual': actual}
    require(actual == report['zip']['expected'], 'ZIP_SIZE_OR_DIGEST_MISMATCH')


def extract_contents(path, folder, pin, report):
    expected = pin['files']
    with zipfile.ZipFile(path) as archive:
        entries = archive.infolist()
        require(len(entries) == len(expected) and {i.filename for i in entries} == set(expected),
                'UNSAFE_ZIP_ENTRIES')
        require(sum(i.file_size for i in entries) <= 32 * 1024 * 1024, 'ZIP_EXPANSION_LIMIT')
        for item in entries:
            mode = stat.S_IFMT(item.external_attr >> 16)
            require(item.filename == item.orig_filename and '/' not in item.filename
                    and '\\' not in item.filename and not item.is_dir()
                    and mode in (0, stat.S_IFREG) and not item.flag_bits & 1
                    and item.compress_type in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED)
                    and item.file_size == expected[item.filename]['bytes'], 'UNSAFE_ZIP_ENTRY')
        files = report['files'] = {}
        for item in entries:
            # Never extract paths, execute files or trust declared ZIP permissions.
            with archive.open(item) as source:
                raw = source.read(expected[item.filename]['bytes'] + 1)
            actual = {'bytes': len(raw), 'sha256': hashlib.sha256(raw).hexdigest()}
            files[item.filename] = {'expected': expected[item.filename], 'actual': actual}
            require(actual == expected[item.filename], 'FILE_SIZE_OR_DIGEST_MISMATCH')
            (folder / item.filename).write_bytes(raw)
    manifest = parse_json((folder / 'manifest.json').read_bytes())
    require(all(manifest.get(k) == v for k, v in {'schema': 1,
        'source_commit': pin['source_commit'], 'version': '0.1.0-m6', 'platform': 'android',
        'channel': 'signed-internal-qa', 'production_ready': False}.items()), 'MANIFEST_SOURCE_MISMATCH')
    records = manifest.get('files', [])
    content_names = set(expected) - {'manifest.json', 'SHA256SUMS'}
    require(len(records) == 3 and {r['file'] for r in records} == content_names,
            'MANIFEST_FILE_SET_MISMATCH')
    for record in records:
        require(record == {'file': record['file'], **expected[record['file']]}, 'MANIFEST_DIGEST_MISMATCH')
    sums = ''.join(f"{r['sha256']}  {r['file']}\n" for r in records)
    require((folder / 'SHA256SUMS').read_bytes() == sums.encode(), 'CHECKSUMS_MISMATCH')
    inspection = parse_json((folder / 'inspection.json').read_bytes())
    require(all(inspection.get(k) == v for k, v in {'signed': True, 'debuggable': False,
        'application_id': 'com.thekain.video.rebuild', 'version_code': 2,
        'version_name': '0.1.0-m6', 'certificate_sha256': pin['certificate_sha256'],
        'production_ready': False}.items()), 'INSPECTION_MISMATCH')


def tool(arguments, output, name):
    executable = Path(arguments[0])
    require(executable.is_file() and os.access(executable, os.X_OK), 'TOOL_MISSING')
    env = {k: v for k, v in os.environ.items() if k in
           ('PATH', 'JAVA_HOME', 'LANG', 'LC_ALL', 'TZ', 'TMPDIR')}
    env['LC_ALL'] = 'C'
    with (output / (name + '.log')).open('wb') as log:
        try:
            result = subprocess.run([str(a) for a in arguments], env=env,
                stdout=log, stderr=subprocess.STDOUT, timeout=180, check=False)
        except subprocess.TimeoutExpired:
            raise VerificationError('TOOL_TIMEOUT') from None
    log_path = output / (name + '.log')
    require(log_path.stat().st_size <= MAX_JSON, 'TOOL_OUTPUT_TOO_LARGE')
    text = log_path.read_text(encoding='utf-8', errors='replace')
    require(result.returncode == 0, name.upper().replace('-', '_') + '_FAILED')
    return text


def tool_versions(tools, java, output):
    require(all((java / 'bin' / name).is_file() for name in ('java', 'keytool', 'jarsigner')),
            'TOOL_MISSING')
    properties = (tools / 'source.properties').read_text()
    version = re.findall(r'^Pkg.Revision\s*=\s*([^\r\n]+)', properties, re.M)
    require(version == ['35.0.0'], 'BUILD_TOOLS_VERSION_MISMATCH')
    apksigner = tool([tools / 'apksigner', 'version'], output, 'apksigner-version').strip()
    jdk = tool([java / 'bin/java', '-version'], output, 'java-version').strip()
    require(re.search(r'version "17[.\"]', jdk), 'JDK_VERSION_MISMATCH')
    return {'build_tools': version[0], 'apksigner': apksigner, 'jdk': jdk,
            'jarsigner': 'JDK bin/jarsigner', 'build_tools_path': str(tools), 'java_home': str(java)}


def verify_apk(apk, tools, output, certificate, report=None):
    text = tool([tools / 'apksigner', 'verify', '--verbose', '--print-certs', apk], output, 'apksigner')
    certs = re.findall(r'^Signer #(\d+) certificate SHA-256 digest: ([0-9a-fA-F]{64})\r?$', text, re.M)
    signers = re.findall(r'^Number of signers: (\d+)\r?$', text, re.M)
    if report is not None:
        report['apk_certificates'] = {'expected_sha256': certificate,
                                     'actual': [{'signer': n, 'sha256': d.lower()} for n, d in certs]}
    require(signers == ['1'] and len(certs) == 1 and certs[0][0] == '1'
            and certs[0][1].lower() == certificate
            and set(re.findall(r'^Signer #(\d+) ', text, re.M)) == {'1'}, 'APK_SIGNER_MISMATCH')
    schemes = {k: v == 'true' for k, v in re.findall(
        r'^Verified using (v\d+[^ ]*) scheme.*: (true|false)\r?$', text, re.M)}
    require(schemes.get('v2') is True and schemes.get('v3') is True, 'APK_SCHEMES_MISSING')
    return {'certificate_sha256': certs[0][1].lower(), 'signers': 1, 'schemes': schemes}


def verify_aab(aab, java, output, certificate, temp, report=None):
    with zipfile.ZipFile(aab) as archive:
        blocks = [n for n in archive.namelist() if re.fullmatch(r'META-INF/[^/]+\.(RSA|DSA|EC)', n, re.I)]
        require(len(blocks) == 1, 'AAB_AMBIGUOUS_SIGNERS')
    text = tool([java / 'bin/keytool', '-printcert', '-rfc', '-jarfile', aab], output, 'aab-cert')
    certs = re.findall(r'-----BEGIN CERTIFICATE-----.*?-----END CERTIFICATE-----', text, re.S)
    require(len(certs) == 1, 'AAB_AMBIGUOUS_CERTIFICATES')
    actual = hashlib.sha256(ssl.PEM_cert_to_DER_cert(certs[0])).hexdigest()
    if report is not None:
        report['aab_certificate'] = {'expected_sha256': certificate, 'actual_sha256': actual}
    require(actual == certificate, 'AAB_CERTIFICATE_MISMATCH')
    public, trust = temp / 'pinned.pem', temp / 'public-trust.p12'
    public.write_text(certs[0], encoding='ascii')
    require(not trust.exists(), 'TRUSTSTORE_MUST_BE_NEW')
    tool([java / 'bin/keytool', '-importcert', '-noprompt', '-alias', 'expected-signer',
          '-storetype', 'PKCS12', '-keystore', trust, '-storepass', PUBLIC_PASSWORD,
          '-file', public], output, 'truststore-import')
    tool([java / 'bin/jarsigner', '-verify', '-strict', '-keystore', trust,
          '-storepass', PUBLIC_PASSWORD, aab, 'expected-signer'], output, 'jarsigner')
    return {'certificate_sha256': actual, 'signers': 1, 'strict': True,
            'truststore': 'temporary; only pinned public certificate'}


def execute(pin, api, env, tools, java, output):
    report = {'schema': 1, 'scope': 'independent-official-tools-run-69', 'source': pin,
        'verifier_commit': env.get('GITHUB_SHA'), 'verifier_run_id': env.get('GITHUB_RUN_ID'),
        'created_at': datetime.now(timezone.utc).isoformat(), 'overall_status': 'FAIL',
        'checks': {name: {'status': 'NOT_RUN'} for name in CHECKS},
        'remaining': {'physical_device_qa': 'BLOCKED', 'upgrade_data_preservation': 'BLOCKED',
                      'runtime_16k_device': 'BLOCKED', 'other_M6_gates': 'BLOCKED'},
        'production_ready': False, 'automatic_publication': False}
    stage = CHECKS[0]
    output.mkdir(parents=True, exist_ok=False)
    try:
        with tempfile.TemporaryDirectory(prefix='thekain-verifier-') as directory:
            temp = Path(directory)
            def check(name, operation):
                nonlocal stage
                stage = name
                details = operation()
                report['checks'][name] = {'status': 'PASS', 'details': details}
            check('review', lambda: reviewed_execution(api, pin, env))
            check('provenance', lambda: provenance(api, pin, datetime.now(timezone.utc)))
            archive = temp / 'original.zip'
            def download_zip():
                api.download(pin['artifact_id'], archive, pin['zip_bytes'])
                verify_zip(archive, pin, report)
            check('zip', download_zip)
            folder = temp / 'contents'
            folder.mkdir()
            check('contents', lambda: extract_contents(archive, folder, pin, report))
            check('tools', lambda: tool_versions(tools, java, output))
            apk = folder / next(n for n in pin['files'] if n.endswith('.apk'))
            aab = folder / next(n for n in pin['files'] if n.endswith('.aab'))
            check('apk', lambda: verify_apk(apk, tools, output, pin['certificate_sha256'], report))
            check('aab', lambda: verify_aab(aab, java, output, pin['certificate_sha256'], temp, report))
            require(all(c['status'] == 'PASS' for c in report['checks'].values()), 'INCOMPLETE_VERIFICATION')
            report['overall_status'] = 'PASS'
    except Exception as error:
        # Never expose API tokens, signed download URLs or uncontrolled exception text.
        code = str(error) if isinstance(error, VerificationError) else 'UNEXPECTED_VERIFICATION_ERROR'
        require(bool(re.fullmatch('[A-Z0-9_]+', code)), 'UNSAFE_ERROR_CODE')
        report['checks'][stage] = {'status': 'FAIL', 'error_code': code}
    finally:
        (output / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
        summary = '\n'.join(['# Independent Android artifact verification',
            f"Overall: **{report['overall_status']}** (internal QA only)",
            f"Source run: {pin['run_id']}; artifact: {pin['artifact_id']}",
            f"Source commit: `{pin['source_commit']}`", f"Verifier commit: `{report['verifier_commit']}`",
            '', '| Check | Status | Error |', '| --- | --- | --- |',
            *[f"| {k} | {v['status']} | {v.get('error_code', '')} |" for k, v in report['checks'].items()],
            '', 'Full digests, certificate results and tool versions: report.json and tool logs.',
            'Physical devices, upgrade, 16 KiB runtime and remaining M6 acceptance: BLOCKED.',
            'production_ready=false; automatic_publication=false. Issue #6 stays open.', '',
            '<details><summary>Machine-readable report</summary>', '', '```json',
            json.dumps(report, indent=2), '```', '</details>', ''])
        (output / 'summary.md').write_text(summary)
        if env.get('GITHUB_STEP_SUMMARY'):
            with Path(env['GITHUB_STEP_SUMMARY']).open('a') as stream:
                stream.write(summary)
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    pin = parse_json(SOURCE.read_bytes())
    report = execute(pin, GitHub(pin['repository'], os.environ.get('GH_TOKEN', '')),
        os.environ, Path(os.environ.get('ANDROID_HOME', '/missing')) / 'build-tools/35.0.0',
        Path(os.environ.get('JAVA_HOME', '/missing')), args.output)
    print('Independent verifier: ' + report['overall_status'] + '; see report.json')
    return 0 if report['overall_status'] == 'PASS' else 1


if __name__ == '__main__':
    raise SystemExit(main())
