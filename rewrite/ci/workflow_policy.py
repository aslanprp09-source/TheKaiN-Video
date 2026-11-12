"""Enforce the reviewed rebuild CI boundary; archival workflows are not modified."""
import json
from pathlib import Path
import re
import sys
import yaml

ROOT = Path(__file__).resolve().parents[2]
SIGN_CONDITION = "inputs.sign_android && needs.signing-policy.outputs.authorized == 'true' && github.event_name == 'workflow_dispatch' && github.ref == 'refs/heads/rebuild-2026-11-11'"
SECRET_ENV = {
    'ANDROID_KEYSTORE_BASE64': '${{ secrets.ANDROID_KEYSTORE_BASE64 }}',
    'THEKAIN_STORE_PASSWORD': '${{ secrets.ANDROID_KEYSTORE_PASSWORD }}',
    'THEKAIN_KEY_ALIAS': '${{ secrets.ANDROID_KEY_ALIAS }}',
    'THEKAIN_KEY_PASSWORD': '${{ secrets.ANDROID_KEY_PASSWORD }}',
}


class UniqueLoader(yaml.BaseLoader):
    def construct_mapping(self, node, deep=False):
        result = {}
        for key_node, value_node in node.value:
            key = self.construct_object(key_node, deep=deep)
            if key in result:
                raise ValueError('Duplicate workflow field')
            result[key] = self.construct_object(value_node, deep=deep)
        return result


def load(path):
    return yaml.load(Path(path).read_text(encoding='utf-8'), Loader=UniqueLoader)


def scalar_paths(value, path=()):
    if isinstance(value, dict):
        for key, child in value.items():
            yield from scalar_paths(child, path + (key,))
    elif isinstance(value, list):
        for index, child in enumerate(value):
            yield from scalar_paths(child, path + (index,))
    else:
        yield path, value


def check(documents, lock):
    errors, seen = [], set()
    def require(value, message):
        if not value:
            errors.append(message)
    require(set(documents) == set(lock['workflows']), 'Unreviewed rebuild workflow set')
    for filename, data in documents.items():
        require(data.get('permissions') == {'contents': 'read'}, 'Default token must be contents:read only')
        events = data.get('on', {})
        allowed = {'push', 'workflow_dispatch'} if filename.endswith('rewrite-ci.yml') else {'workflow_dispatch', 'workflow_call'}
        require(set(events) == allowed, 'Unreviewed workflow trigger')
        for event in ('workflow_dispatch', 'workflow_call'):
            if event in events:
                setting = events[event].get('inputs', {}).get('sign_android', {})
                require(setting.get('type') == 'boolean' and setting.get('default') == 'false', 'Signing must default off')
        for job_id, job in data.get('jobs', {}).items():
            permissions = job.get('permissions', {})
            require(isinstance(permissions, dict) and all(k in ('contents', 'actions') and v == 'read'
                for k, v in permissions.items()), 'Write, OIDC or broad token permissions forbidden')
            require('secrets' not in job, 'Broad secret forwarding forbidden')
            if 'environment' in job:
                require(filename.endswith('rewrite-packaging.yml') and job_id == 'android-signed-internal-qa', 'Unreviewed protected environment consumer')
            if 'uses' in job:
                require(filename.endswith('rewrite-ci.yml') and job_id == 'experimental-packages' and
                    job['uses'] == './.github/workflows/rewrite-packaging.yml', 'Unreviewed reusable workflow')
            for step in job.get('steps', []):
                if 'uses' in step:
                    uses = step['uses']
                    action, _, sha = uses.partition('@')
                    entry = lock['actions'].get(action)
                    require(bool(entry) and bool(re.fullmatch('[0-9a-f]{40}', sha)) and sha == entry['commit'], 'Action is floating or not in the reviewed SHA inventory')
                    seen.add(action)
                    if action == 'actions/checkout':
                        require(step.get('with', {}).get('persist-credentials') == 'false', 'Persisted checkout credentials forbidden')
            for path, value in scalar_paths(job):
                if isinstance(value, str) and 'secrets.' in value:
                    require(filename.endswith('rewrite-packaging.yml') and job_id == 'android-signed-internal-qa' and
                        len(path) == 4 and path[0] == 'steps' and path[2] == 'env' and
                        job['steps'][path[1]].get('id') == 'sign-artifacts' and SECRET_ENV.get(path[3]) == value,
                        'Secrets may only enter the minimal artifact-signing step')
    require(seen == set(lock['actions']), 'Action inventory and workflow usage differ')
    ci = documents['.github/workflows/rewrite-ci.yml']
    require(ci['jobs']['experimental-packages'].get('needs') == 'ci-policy', 'Packaging must depend on workflow policy')
    package = documents['.github/workflows/rewrite-packaging.yml']
    gate = package['jobs'].get('signing-policy', {})
    require('environment' not in gate and gate.get('outputs', {}).get('authorized') == '${{ steps.policy.outputs.authorized }}', 'Unprivileged policy gate required')
    sign = package['jobs'].get('android-signed-internal-qa', {})
    require(sign.get('if') == SIGN_CONDITION and set(sign.get('needs', [])) == {'signing-policy', 'android-unsigned', 'windows-installer', 'signing-rehearsal'}, 'Signing approval/ref/build dependencies weakened')
    require(sign.get('environment') == 'release-signing' and sign.get('runs-on') == 'ubuntu-24.04', 'Signing must use the protected environment and a fresh hosted runner')
    require(sign.get('concurrency') == {'group': 'thekain-android-signing', 'cancel-in-progress': 'false'}, 'Signing must serialize without canceling an active signing process')
    require('env' not in sign and 'defaults' not in sign, 'Signing job must not inherit broad env or build defaults')
    steps = sign.get('steps', [])
    require([s.get('id') for s in steps] == ['checkout', 'policy-recheck', 'setup-java', 'download-unsigned', 'verify-unsigned', 'sign-artifacts', 'upload-signed'], 'Signing job step boundary changed')
    for step in steps:
        require('gradle' not in step.get('uses', '').lower() and 'gradle' not in step.get('run', '').lower() and
                not any(k.startswith('cache') for k in step.get('with', {})), 'No builds or caches in the signing job')
    by_id = {s.get('id'): s for s in steps}
    require(by_id.get('checkout', {}).get('with', {}).get('ref') == '${{ github.sha }}', 'Signing checkout must be the approved source SHA')
    require(by_id.get('policy-recheck', {}).get('run') == 'python3 rewrite/ci/signing_policy.py --requested true', 'Policy must be rechecked after environment approval')
    download = by_id.get('download-unsigned', {}).get('with', {})
    require(download == {'artifact-ids': '${{ needs.android-unsigned.outputs.signing-artifact-id }}', 'merge-multiple': 'true',
                         'path': '${{ runner.temp }}/thekain-unsigned-input'}, 'Only the same-run artifact ID is accepted')
    command = 'python3 rewrite/ci/artifact_signing.py sign --input "$RUNNER_TEMP/thekain-unsigned-input" --commit "$GITHUB_SHA" --apk-sha "$APK_SHA" --aab-sha "$AAB_SHA" --manifest-sha "$MANIFEST_SHA" --tools "$ANDROID_HOME/build-tools/35.0.0" --java-home "$JAVA_HOME" --output "$RUNNER_TEMP/thekain-signed-output"'
    require(by_id.get('sign-artifacts', {}).get('run') == command, 'Secret-bearing step may only execute the reviewed signing helper')
    sign_env = by_id.get('sign-artifacts', {}).get('env', {})
    require(all(sign_env.get(k) == v for k, v in SECRET_ENV.items()) and set(sign_env) == set(SECRET_ENV) | {
        'ANDROID_SIGNING_CERT_SHA256', 'APK_SHA', 'AAB_SHA', 'MANIFEST_SHA'}, 'Signing secret scope changed')
    for key, output in [('APK_SHA', 'apk-sha'), ('AAB_SHA', 'aab-sha'), ('MANIFEST_SHA', 'manifest-sha')]:
        require(sign_env.get(key) == '${{ needs.android-unsigned.outputs.' + output + ' }}', 'Same-run proof binding changed')
    require(sign_env.get('ANDROID_SIGNING_CERT_SHA256') == '${{ vars.ANDROID_SIGNING_CERT_SHA256 }}', 'Expected public certificate required')
    upload = by_id.get('upload-signed', {})
    require('if' not in upload and upload.get('with', {}).get('path') == '${{ runner.temp }}/thekain-signed-output/', 'Failed or broader signing output must not be uploaded')
    return errors


def main():
    lock = json.loads((ROOT / 'rewrite/ci/actions-lock.json').read_text())
    names = {p.relative_to(ROOT).as_posix() for p in (ROOT / '.github/workflows').glob('rewrite*.yml')}
    documents = {name: load(ROOT / name) for name in names}
    errors = check(documents, lock)
    for error in sorted(set(errors)):
        print('::error title=CI policy::' + error)
    print(f'CI policy: {len(documents)} rebuild workflows, {len(lock["actions"])} pinned Actions; {len(errors)} violations')
    return bool(errors)


if __name__ == '__main__':
    raise SystemExit(main())
