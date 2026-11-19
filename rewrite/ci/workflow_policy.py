"""Enforce the reviewed rebuild CI boundary; archival workflows are not modified."""
import json
from pathlib import Path
import re
import sys
import yaml

ROOT = Path(__file__).resolve().parents[2]
CI_WORKFLOW = '.github/workflows/rewrite-ci.yml'
PACKAGE_WORKFLOW = '.github/workflows/rewrite-packaging.yml'
QA89_WORKFLOW = '.github/workflows/rewrite-verify-qa89.yml'
SIGN_CONDITION = "inputs.build_packages && inputs.sign_android && needs.experimental-packages.outputs.signing-authorized == 'true' && github.event_name == 'workflow_dispatch' && github.ref == 'refs/heads/rebuild-2026-11-11' && !inputs.verify_artifact"
BUILD_CONDITION = "github.event_name == 'workflow_dispatch' && inputs.build_packages && !inputs.verify_artifact"
VERIFY_BASE = "github.event_name == 'workflow_dispatch' && github.ref == 'refs/heads/rebuild-2026-11-11' && inputs.verify_artifact && !inputs.build_packages && !inputs.sign_android"
VERIFY_CONDITION = VERIFY_BASE + " && inputs.verification_target != 'qa89'"
QA89_CONDITION = VERIFY_BASE + " && inputs.verification_target == 'qa89'"
NATIVE_CONDITION = "github.event_name == 'workflow_dispatch' && inputs.native_runtime_lab && !inputs.build_packages && !inputs.sign_android && !inputs.verify_artifact"
REQUIRED_CHECKS = {'ci-policy', 'windows-tests', 'android-debug', 'android-device-tests'}
PUBLIC_OUTPUTS = {
    'signing-authorized': '${{ jobs.signing-policy.outputs.authorized }}',
    **{name: '${{ jobs.android-unsigned.outputs.' + name + ' }}'
       for name in ('signing-artifact-id', 'apk-sha', 'aab-sha', 'manifest-sha')},
}
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
        require('env' not in data and 'defaults' not in data, 'Workflow-wide environment or shell defaults forbidden')
        events = data.get('on', {})
        allowed = {'push', 'pull_request', 'workflow_dispatch'} if filename.endswith('rewrite-ci.yml') else {'workflow_dispatch', 'workflow_call'}
        require(set(events) == allowed, 'Unreviewed workflow trigger')
        if filename.endswith('rewrite-ci.yml'):
            require(events.get('push') == {'branches': ['rebuild-2026-11-11']},
                    'Push events must target only rebuild branch')
            require(events.get('pull_request') == {'branches': ['rebuild-2026-11-11']},
                    'Required PR checks must run for PRs targeting rebuild branch')
        for event in ('workflow_dispatch', 'workflow_call'):
            if event in events and filename != QA89_WORKFLOW:
                setting = events[event].get('inputs', {}).get('sign_android', {})
                require(setting.get('type') == 'boolean' and setting.get('default') == 'false', 'Signing must default off')
        require('secrets' not in events.get('workflow_call', {}), 'Reusable builds must not accept signing secrets')
        for path, value in scalar_paths(data):
            if isinstance(value, str) and any(re.search(r'\bsecrets\b', expression) for expression in
                    re.findall(r'\$\{\{(.*?)\}\}', value, re.S)):
                require(filename == CI_WORKFLOW and len(path) == 6 and
                    path[:3] == ('jobs', 'android-signed-internal-qa', 'steps') and path[4] == 'env' and
                    data['jobs']['android-signed-internal-qa']['steps'][path[3]].get('id') == 'sign-artifacts' and
                    SECRET_ENV.get(path[5]) == value,
                    'Secrets may only enter the direct protected artifact-signing step')
        for job_id, job in data.get('jobs', {}).items():
            require('continue-on-error' not in job, 'Failed jobs must fail the workflow')
            permissions = job.get('permissions', {})
            allowed_permissions = ('contents', 'actions', 'pull-requests') if (
                filename == CI_WORKFLOW and job_id in ('android-independent-verifier', 'android-qa89-independent-verifier')
                or filename == QA89_WORKFLOW and job_id == 'verify') else ('contents', 'actions')
            require(isinstance(permissions, dict) and all(k in allowed_permissions and v == 'read'
                for k, v in permissions.items()), 'Write, OIDC or broad token permissions forbidden')
            require('secrets' not in job, 'Broad secret forwarding forbidden')
            if 'environment' in job:
                require(filename == CI_WORKFLOW and job_id == 'android-signed-internal-qa', 'Unreviewed protected environment consumer')
            if 'uses' in job:
                require(filename == CI_WORKFLOW and (
                    job_id == 'experimental-packages' and job['uses'] == './' + PACKAGE_WORKFLOW or
                    job_id == 'android-qa89-independent-verifier' and job['uses'] == './' + QA89_WORKFLOW),
                    'Unreviewed reusable workflow')
            for step in job.get('steps', []):
                require('continue-on-error' not in step, 'Failed steps must not be accepted')
                if 'uses' in step:
                    uses = step['uses']
                    action, _, sha = uses.partition('@')
                    entry = lock['actions'].get(action)
                    require(bool(entry) and bool(re.fullmatch('[0-9a-f]{40}', sha)) and sha == entry['commit'], 'Action is floating or not in the reviewed SHA inventory')
                    seen.add(action)
                    if action == 'actions/checkout':
                        require(step.get('with', {}).get('persist-credentials') == 'false', 'Persisted checkout credentials forbidden')
    require(seen == set(lock['actions']), 'Action inventory and workflow usage differ')
    ci = documents[CI_WORKFLOW]
    target = ci['on'].get('workflow_dispatch', {}).get('inputs', {}).get('verification_target', {})
    require(target.get('type') == 'choice' and target.get('default') == 'original69' and
            target.get('options') == ['original69', 'qa89'], 'Only fixed verifier profiles are permitted')
    require(any(s.get('if') == "inputs.verify_artifact && inputs.verification_target != 'original69' && inputs.verification_target != 'qa89'"
                and s.get('run') == 'exit 1' for s in ci['jobs']['ci-policy']['steps']),
            'Unknown verifier target must fail closed')
    require(ci['jobs'].get('android-qa89-independent-verifier') == {
        'if': QA89_CONDITION, 'needs': 'ci-policy',
        'permissions': {'contents': 'read', 'actions': 'read', 'pull-requests': 'read'},
        'uses': './' + QA89_WORKFLOW,
        'with': {'bypass_preflight_comment': '${{ inputs.bypass_preflight_comment }}'}},
        'QA89 caller must be manual, reviewed-policy gated and forward only the public preflight ID')
    qa89_errors = check_qa89(documents.get(QA89_WORKFLOW, {}), lock)
    errors.extend(qa89_errors)
    errors.extend(check_native_lab(ci, lock))
    verifier_input = ci['on'].get('workflow_dispatch', {}).get('inputs', {}).get('verify_artifact', {})
    require(verifier_input.get('type') == 'boolean' and verifier_input.get('default') == 'false',
            'Independent verifier must default off')
    preflight_input = ci['on'].get('workflow_dispatch', {}).get('inputs', {}).get('bypass_preflight_comment', {})
    require(preflight_input.get('type') == 'string' and preflight_input.get('default') == '',
            'Verifier needs an explicit fresh public preflight comment ID')
    for name in REQUIRED_CHECKS - {'ci-policy'}:
        require(ci['jobs'].get(name, {}).get('if') == '${{ !inputs.verify_artifact }}',
                'Source CI must run normally and skip only in explicit verifier mode')
    legacy = ci['jobs'].get('android-legacy-device-tests', {})
    require(legacy.get('if') == '${{ !inputs.verify_artifact }}'
            and legacy.get('runs-on') == 'ubuntu-24.04'
            and legacy.get('strategy') == {'fail-fast': 'false', 'matrix': {'api': ['26', '29']}},
            'Android storage boundary tests must use real hosted API 26/29 and skip verifier mode')
    require(any(s.get('if') == 'inputs.verify_artifact && (inputs.build_packages || inputs.sign_android || !inputs.bypass_preflight_comment)'
                and s.get('run') == 'exit 1' for s in ci['jobs']['ci-policy']['steps']),
            'Conflicting verifier/build modes must fail')
    verify = ci['jobs'].get('android-independent-verifier', {})
    require(set(verify) == {'if', 'needs', 'permissions', 'runs-on', 'timeout-minutes', 'steps'}
            and verify.get('if') == VERIFY_CONDITION and verify.get('needs') == 'ci-policy'
            and verify.get('runs-on') == 'ubuntu-24.04' and verify.get('timeout-minutes') == '10'
            and verify.get('permissions') == {'contents': 'read', 'actions': 'read', 'pull-requests': 'read'},
            'Verifier requires isolated hosted runner, read-only access and reviewed manual mode')
    verify_steps = verify.get('steps', [])
    require([s.get('id') for s in verify_steps] == ['checkout', 'setup-java', 'verify', 'upload-report'],
            'Verifier must have no build, cache, forwarding or extra steps')
    for step in verify_steps:
        require(set(step) <= {'id', 'name', 'uses', 'with', 'run', 'env', 'if'},
                'Verifier step boundary changed')
        require('if' not in step or step.get('id') == 'upload-report', 'Verifier checks must not be skipped')
    verifier_steps = {s.get('id'): s for s in verify_steps}
    require(verifier_steps.get('checkout', {}).get('with') == {
        'persist-credentials': 'false', 'ref': '${{ github.sha }}'}, 'Verifier checkout must bind execution SHA')
    require(verifier_steps.get('setup-java', {}).get('with') == {
        'distribution': 'temurin', 'java-version': '17'}, 'Verifier must use JDK 17 without caches')
    run = verifier_steps.get('verify', {})
    require(run.get('run') == 'python3 rewrite/ci/android_artifact_verifier.py --output "$RUNNER_TEMP/thekain-independent-verification"'
            and run.get('env') == {'GH_TOKEN': '${{ github.token }}',
                'BYPASS_PREFLIGHT_COMMENT_ID': '${{ inputs.bypass_preflight_comment }}'}
            and 'uses' not in run and 'with' not in run,
            'Only the reviewed verifier may receive the read-only API token')
    for step in verify_steps:
        require(step.get('id') == 'verify' or ('run' not in step and 'env' not in step),
                'Verifier actions may not receive extra commands or environment')
        if step.get('id') != 'verify':
            expected_action = {'checkout': 'actions/checkout', 'setup-java': 'actions/setup-java',
                               'upload-report': 'actions/upload-artifact'}.get(step.get('id'))
            require(step.get('uses', '').split('@')[0] == expected_action, 'Verifier action boundary changed')
    upload_report = verifier_steps.get('upload-report', {})
    require(upload_report.get('if') == 'always()' and upload_report.get('with') == {
        'name': 'M6-Android-independent-verification',
        'path': '${{ runner.temp }}/thekain-independent-verification/',
        'if-no-files-found': 'error', 'retention-days': '7'}, 'Verifier must retain bounded failure/success evidence only')
    call = ci['jobs']['experimental-packages']
    require(call.get('needs') == 'ci-policy', 'Packaging must depend on workflow policy')
    require(call.get('if') == BUILD_CONDITION and call.get('with') == {'sign_android': '${{ inputs.sign_android }}'},
            'Packaging must use only the explicit manual build and signing request')
    build_input = ci['on'].get('workflow_dispatch', {}).get('inputs', {}).get('build_packages', {})
    require(build_input.get('type') == 'boolean' and build_input.get('default') == 'false', 'Packaging must default off')
    package = documents[PACKAGE_WORKFLOW]
    require(set(package['jobs']) == {'signing-policy', 'windows-installer', 'android-unsigned', 'signing-rehearsal'},
            'All four unprivileged build and rehearsal jobs are required')
    require(all('if' not in job for job in package['jobs'].values()), 'Build and rehearsal jobs must not be conditionally skipped')
    require(package['jobs'].get('signing-rehearsal', {}).get('needs') == 'android-unsigned',
            'Rehearsal must verify the unsigned build before the caller can complete')
    outputs = package['on'].get('workflow_call', {}).get('outputs', {})
    require({k: v.get('value') for k, v in outputs.items()} == PUBLIC_OUTPUTS,
            'Reusable workflow may return only the reviewed public policy and same-run artifact proofs')
    require('android-signed-internal-qa' not in package['jobs'], 'Real signing must not run across workflow_call')
    gate = package['jobs'].get('signing-policy', {})
    require('environment' not in gate and gate.get('outputs', {}).get('authorized') == '${{ steps.policy.outputs.authorized }}', 'Unprivileged policy gate required')
    require(any(s.get('id') == 'workflow-check' and 'python rewrite/ci/workflow_policy.py' in s.get('run', '')
                for s in gate.get('steps', [])), 'Direct packaging dispatch must enforce the same workflow policy')
    sign = ci['jobs'].get('android-signed-internal-qa', {})
    require(sign.get('if') == SIGN_CONDITION and set(sign.get('needs', [])) == REQUIRED_CHECKS | {'experimental-packages'}, 'Signing approval/ref/build dependencies weakened')
    require(sign.get('environment') == 'release-signing' and sign.get('runs-on') == 'ubuntu-24.04', 'Signing must use the protected environment and a fresh hosted runner')
    require(sign.get('concurrency') == {'group': 'thekain-android-signing', 'cancel-in-progress': 'false'}, 'Signing must serialize without canceling an active signing process')
    require('env' not in sign and 'defaults' not in sign, 'Signing job must not inherit broad env or build defaults')
    steps = sign.get('steps', [])
    require([s.get('id') for s in steps] == ['checkout', 'policy-recheck', 'setup-java', 'download-unsigned', 'verify-unsigned', 'sign-artifacts', 'upload-signed'], 'Signing job step boundary changed')
    for step in steps:
        require('if' not in step and 'shell' not in step,
                'Protected signing steps must use the default success gate and runner shell')
        require('gradle' not in step.get('uses', '').lower() and 'gradle' not in step.get('run', '').lower() and
                not any(k.startswith('cache') for k in step.get('with', {})), 'No builds or caches in the signing job')
    by_id = {s.get('id'): s for s in steps}
    require(by_id.get('checkout', {}).get('with', {}).get('ref') == '${{ github.sha }}', 'Signing checkout must be the approved source SHA')
    require(by_id.get('policy-recheck', {}).get('run') == 'python3 rewrite/ci/signing_policy.py --requested true', 'Policy must be rechecked after environment approval')
    download = by_id.get('download-unsigned', {}).get('with', {})
    require(download == {'artifact-ids': '${{ needs.experimental-packages.outputs.signing-artifact-id }}', 'merge-multiple': 'true',
                         'path': '${{ runner.temp }}/thekain-unsigned-input'}, 'Only the same-run artifact ID is accepted')
    command = 'python3 rewrite/ci/artifact_signing.py sign --input "$RUNNER_TEMP/thekain-unsigned-input" --commit "$GITHUB_SHA" --apk-sha "$APK_SHA" --aab-sha "$AAB_SHA" --manifest-sha "$MANIFEST_SHA" --tools "$ANDROID_HOME/build-tools/35.0.0" --java-home "$JAVA_HOME" --output "$RUNNER_TEMP/thekain-signed-output"'
    require(by_id.get('sign-artifacts', {}).get('run') == command, 'Secret-bearing step may only execute the reviewed signing helper')
    sign_env = by_id.get('sign-artifacts', {}).get('env', {})
    require(all(sign_env.get(k) == v for k, v in SECRET_ENV.items()) and set(sign_env) == set(SECRET_ENV) | {
        'ANDROID_SIGNING_CERT_SHA256', 'APK_SHA', 'AAB_SHA', 'MANIFEST_SHA'}, 'Signing secret scope changed')
    for key, output in [('APK_SHA', 'apk-sha'), ('AAB_SHA', 'aab-sha'), ('MANIFEST_SHA', 'manifest-sha')]:
        require(sign_env.get(key) == '${{ needs.experimental-packages.outputs.' + output + ' }}', 'Same-run proof binding changed')
    require(sign_env.get('ANDROID_SIGNING_CERT_SHA256') == '${{ vars.ANDROID_SIGNING_CERT_SHA256 }}', 'Expected public certificate required')
    upload = by_id.get('upload-signed', {})
    require('if' not in upload and upload.get('with', {}).get('path') == '${{ runner.temp }}/thekain-signed-output/', 'Failed or broader signing output must not be uploaded')
    return errors


def check_native_lab(ci, lock):
    errors = []
    def require(value, message):
        if not value: errors.append('Native lab: ' + message)
    setting = ci['on'].get('workflow_dispatch', {}).get('inputs', {}).get('native_runtime_lab', {})
    require(setting.get('type') == 'boolean' and setting.get('default') == 'false', 'must default off')
    require(any(s.get('if') == 'inputs.native_runtime_lab && (inputs.build_packages || inputs.sign_android || inputs.verify_artifact)'
                and s.get('run') == 'exit 1' for s in ci['jobs']['ci-policy']['steps']), 'conflicting modes must fail')
    build = ci['jobs'].get('native-runtime-build', {})
    device = ci['jobs'].get('native-runtime-device-tests', {})
    common = {'if', 'needs', 'runs-on', 'timeout-minutes', 'permissions', 'steps'}
    require(set(build) == common | {'outputs'} and set(device) == common | {'strategy'},
            'no Environment, inherited credentials, defaults, services or extra fields')
    for job, needs, permissions, timeout in (
            (build, 'ci-policy', {'contents': 'read'}, '60'),
            (device, 'native-runtime-build', {'contents': 'read', 'actions': 'read'}, '25')):
        require(job.get('if') == NATIVE_CONDITION and job.get('needs') == needs and
                job.get('permissions') == permissions and job.get('runs-on') == 'ubuntu-24.04' and
                job.get('timeout-minutes') == timeout, 'isolated default-off hosted read-only jobs required')
    require(build.get('outputs') == {'artifact-id': '${{ steps.upload-lab.outputs.artifact-id }}'}, 'only same-run lab artifact output')
    require(device.get('strategy') == {'fail-fast': 'false', 'matrix': {'include': [
        {'api': '26', 'image': 'google_apis', 'pages': '4096'},
        {'api': '29', 'image': 'google_apis', 'pages': '4096'},
        {'api': '35', 'image': 'google_apis', 'pages': '4096'},
        {'api': '36', 'image': 'google_apis_ps16k', 'pages': '16384'}]}}, 'all four real API/page-size targets required')
    action_specs = {
        'checkout': ('actions/checkout', {'persist-credentials': 'false', 'ref': '${{ github.sha }}'}),
        'setup-python': ('actions/setup-python', {'python-version': '3.11.18'}),
        'setup-java': ('actions/setup-java', {'distribution': 'temurin', 'java-version': '17'}),
        'setup-gradle': ('gradle/actions/setup-gradle', {'gradle-version': '8.11.1', 'cache-disabled': 'true'}),
        'upload-lab': ('actions/upload-artifact', {'name': 'TheKaiN-native-lab-INTERNAL-TEST',
            'path': '${{ runner.temp }}/native-lab-delivery/', 'if-no-files-found': 'error', 'retention-days': '1'}),
        'download-lab': ('actions/download-artifact', {'artifact-ids': '${{ needs.native-runtime-build.outputs.artifact-id }}',
            'merge-multiple': 'true', 'path': '${{ runner.temp }}/native-lab-delivery'}),
        'upload-evidence': ('actions/upload-artifact', {'name': 'native-lab-API${{ matrix.api }}-${{ matrix.pages }}',
            'path': '${{ runner.temp }}/native-lab-evidence/', 'if-no-files-found': 'error', 'retention-days': '7'}),
    }
    commands = {
        'source-build': 'python3 rewrite/experiments/android_native/build.py --work "$RUNNER_TEMP/native-lab"',
        'assemble-lab': '"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" \'platforms;android-35\' \'build-tools;35.0.0\'\n'
            'gradle --no-daemon -p rewrite/experiments/android_native/android :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug :probe:assembleDebug :probe:lintDebug -PlabRuntimeDir="$RUNNER_TEMP/native-lab/package"\n'
            'python3 rewrite/experiments/android_native/delivery.py pack --work "$RUNNER_TEMP/native-lab" --output "$RUNNER_TEMP/native-lab-delivery"\n',
        'build-diagnostics': 'python3 rewrite/experiments/android_native/diagnostics.py --work "$RUNNER_TEMP/native-lab"',
        'native-smoke': "bash rewrite/experiments/android_native/device-smoke.sh '${{ matrix.api }}' '${{ matrix.image }}' '${{ matrix.pages }}' "
            '"$RUNNER_TEMP/native-lab-delivery" "$RUNNER_TEMP/native-lab-evidence"',
    }
    for job, ids in ((build, ['checkout', 'setup-python', 'setup-java', 'setup-gradle', 'source-build', 'assemble-lab', 'upload-lab', 'build-diagnostics']),
                     (device, ['checkout', 'setup-java', 'download-lab', 'native-smoke', 'upload-evidence'])):
        require([s.get('id') for s in job.get('steps', [])] == ids, 'step boundary changed')
        for step in job.get('steps', []):
            ident = step.get('id')
            require(set(step) <= {'id', 'uses', 'with', 'run', 'if'}, 'no token/env/shell forwarding or extra step fields')
            if ident in action_specs:
                action, options = action_specs[ident]
                require(step.get('uses') == action + '@' + lock['actions'][action]['commit'] and
                        step.get('with') == options and 'run' not in step, 'fixed action/input/upload boundary changed')
            else:
                require(ident in commands and step.get('run') == commands.get(ident) and
                        'uses' not in step and 'with' not in step, 'only reviewed fixed commands permitted')
            expected_if = {'upload-evidence': 'always()', 'build-diagnostics': 'failure()'}.get(ident)
            require(step.get('if') == expected_if, 'checks may not be skipped or failures hidden')
    return errors


def check_qa89(data, lock):
    errors = []
    def require(value, message):
        if not value: errors.append('QA89: ' + message)
    require(set(data) == {'name', 'on', 'permissions', 'jobs'}, 'workflow-wide context forbidden')
    require(data.get('on') == {event: {'inputs': {'bypass_preflight_comment': {
        'required': 'true', 'type': 'string'}}} for event in ('workflow_call', 'workflow_dispatch')},
        'only manual/call public preflight input allowed')
    require(set(data.get('jobs', {})) == {'verify'}, 'only read-only verification job allowed')
    job = data.get('jobs', {}).get('verify', {})
    require(set(job) == {'if', 'permissions', 'runs-on', 'timeout-minutes', 'steps'} and
            job.get('if') == "github.event_name == 'workflow_dispatch' && github.ref == 'refs/heads/rebuild-2026-11-11'" and
            job.get('permissions') == {'contents': 'read', 'actions': 'read', 'pull-requests': 'read'} and
            job.get('runs-on') == 'ubuntu-24.04' and job.get('timeout-minutes') == '10',
            'fresh hosted runner, fixed branch, no Environment/build/cache/write permissions')
    steps = job.get('steps', [])
    require([s.get('id') for s in steps] == ['checkout', 'setup-python', 'setup-java', 'policy', 'verify', 'upload-report'],
            'step boundary changed')
    expected_actions = {'checkout': 'actions/checkout', 'setup-python': 'actions/setup-python',
                        'setup-java': 'actions/setup-java', 'upload-report': 'actions/upload-artifact'}
    expected_with = {
        'checkout': {'persist-credentials': 'false', 'ref': '${{ github.sha }}'},
        'setup-python': {'python-version': '3.11.18'},
        'setup-java': {'distribution': 'temurin', 'java-version': '17'},
        'upload-report': {'name': 'M6-Android-QA89-independent-verification',
            'path': '${{ runner.temp }}/thekain-qa89-verification/', 'if-no-files-found': 'error', 'retention-days': '7'}}
    for step in steps:
        ident = step.get('id')
        if ident in expected_actions:
            action = expected_actions[ident]
            require(step.get('uses') == action + '@' + lock['actions'][action]['commit'] and
                    step.get('with') == expected_with[ident] and
                    set(step) <= {'id', 'name', 'uses', 'with', 'if'} and
                    (step.get('if') == 'always()' if ident == 'upload-report' else 'if' not in step),
                    'action/cache/upload boundary changed')
        elif ident == 'policy':
            require(set(step) <= {'id', 'name', 'run'} and step.get('run') ==
                'python -m pip install --require-hashes --only-binary=:all: --no-deps -r rewrite/ci/requirements.lock\npython rewrite/ci/workflow_policy.py\n',
                'policy cannot execute builds/signing fixtures or receive credentials')
        elif ident == 'verify':
            require(set(step) <= {'id', 'name', 'run', 'env'} and step.get('run') ==
                'python3 rewrite/ci/android_qa89_verifier.py --output "$RUNNER_TEMP/thekain-qa89-verification"' and
                step.get('env') == {'GH_TOKEN': '${{ github.token }}',
                    'BYPASS_PREFLIGHT_COMMENT_ID': '${{ inputs.bypass_preflight_comment }}'},
                'only fixed verifier gets the temporary read-only API token')
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
