from copy import deepcopy
import json
from pathlib import Path
import unittest
from workflow_policy import ROOT, load, check, UniqueLoader
import yaml


class WorkflowPolicyTest(unittest.TestCase):
    def setUp(self):
        self.lock = json.loads((ROOT / 'rewrite/ci/actions-lock.json').read_text())
        self.docs = {name: load(ROOT / name) for name in self.lock['workflows']}

    def test_committed_workflows_meet_the_policy(self):
        self.assertEqual(check(self.docs, self.lock), [])

    def test_floating_or_unreviewed_sha_and_action_are_rejected(self):
        name = '.github/workflows/rewrite-ci.yml'
        for uses in ('actions/checkout@v7', 'actions/checkout@' + 'f' * 40, 'unknown/action@' + 'a' * 40,
                     'actions/checkout@${{ inputs.ref }}', 'docker://image:latest'):
            self.setUp()
            self.docs[name]['jobs']['ci-policy']['steps'][0]['uses'] = uses
            with self.subTest(uses=uses): self.assertTrue(check(self.docs, self.lock))

    def test_write_permissions_persisted_credentials_and_secret_inheritance_are_rejected(self):
        for variant in ('write', 'oidc', 'persist', 'inherit'):
            self.setUp()
            jobs = self.docs['.github/workflows/rewrite-ci.yml']['jobs']
            if variant == 'write': jobs['android-device-tests']['permissions'] = {'contents': 'write'}
            if variant == 'oidc': jobs['windows-tests']['permissions'] = {'id-token': 'write'}
            if variant == 'persist': jobs['windows-tests']['steps'][0]['with']['persist-credentials'] = 'true'
            if variant == 'inherit': jobs['experimental-packages']['secrets'] = 'inherit'
            with self.subTest(variant=variant): self.assertTrue(check(self.docs, self.lock))

    def test_untrusted_trigger_or_bypassed_gate_is_rejected(self):
        for variant in ('trigger', 'ref', 'environment', 'cache', 'secret', 'download', 'upload'):
            self.setUp()
            package = self.docs['.github/workflows/rewrite-packaging.yml']
            sign = self.docs['.github/workflows/rewrite-ci.yml']['jobs']['android-signed-internal-qa']
            steps = {s['id']: s for s in sign['steps']}
            if variant == 'trigger': package['on']['pull_request_target'] = {}
            if variant == 'ref': sign['if'] = 'inputs.sign_android'
            if variant == 'environment': sign['environment'] = 'unprotected'
            if variant == 'cache': steps['setup-java']['with']['cache'] = 'gradle'
            if variant == 'secret': steps['setup-java']['env'] = {'KEY': '${{ secrets.ANDROID_KEYSTORE_BASE64 }}'}
            if variant == 'download': steps['download-unsigned']['with']['run-id'] = '${{ inputs.run_id }}'
            if variant == 'upload': steps['upload-signed']['if'] = 'always()'
            with self.subTest(variant=variant): self.assertTrue(check(self.docs, self.lock))

    def test_secrets_cannot_cross_the_build_boundary_or_enter_unprotected_jobs(self):
        for variant in ('named-forward', 'call-declaration', 'build-step', 'build-environment',
                'workflow-env', 'bracket-access', 'serialize-context', 'output'):
            self.setUp()
            ci = self.docs['.github/workflows/rewrite-ci.yml']
            package = self.docs['.github/workflows/rewrite-packaging.yml']
            secret = '${{ secrets.ANDROID_KEYSTORE_BASE64 }}'
            if variant == 'named-forward': ci['jobs']['experimental-packages']['secrets'] = {'ANDROID_KEYSTORE_BASE64': secret}
            if variant == 'call-declaration': package['on']['workflow_call']['secrets'] = {'ANDROID_KEYSTORE_BASE64': {'required': 'true'}}
            if variant == 'build-step': package['jobs']['android-unsigned']['steps'][0]['env'] = {'KEY': secret}
            if variant == 'build-environment': package['jobs']['android-unsigned']['environment'] = 'release-signing'
            if variant == 'workflow-env': ci['env'] = {'KEY': secret}
            if variant == 'bracket-access': ci['jobs']['windows-tests']['env'] = {'KEY': "${{ secrets['ANDROID_KEYSTORE_BASE64'] }}"}
            if variant == 'serialize-context': ci['jobs']['windows-tests']['env'] = {'KEY': '${{ toJSON(secrets) }}'}
            if variant == 'output': package['on']['workflow_call']['outputs']['apk-sha']['value'] = secret
            with self.subTest(variant=variant): self.assertTrue(check(self.docs, self.lock))

    def test_direct_signer_requires_all_ci_and_public_same_run_build_outputs(self):
        for variant in ('reusable-signer', 'no-device-check', 'no-build', 'wrong-proof', 'fake-authorization', 'extra-output',
                'no-rehearsal', 'skip-rehearsal', 'skip-windows', 'ignore-build-failure', 'ignore-signing-failure'):
            self.setUp()
            ci = self.docs['.github/workflows/rewrite-ci.yml']
            package = self.docs['.github/workflows/rewrite-packaging.yml']
            sign = ci['jobs']['android-signed-internal-qa']
            if variant == 'reusable-signer': package['jobs']['android-signed-internal-qa'] = ci['jobs'].pop('android-signed-internal-qa')
            if variant == 'no-device-check': sign['needs'].remove('android-device-tests')
            if variant == 'no-build': sign['needs'].remove('experimental-packages')
            if variant == 'wrong-proof': package['on']['workflow_call']['outputs']['apk-sha']['value'] = '${{ inputs.apk_sha }}'
            if variant == 'fake-authorization': package['on']['workflow_call']['outputs']['signing-authorized']['value'] = 'true'
            if variant == 'extra-output': package['on']['workflow_call']['outputs']['unreviewed'] = {'value': '${{ jobs.android-unsigned.outputs.extra }}'}
            if variant == 'no-rehearsal': del package['jobs']['signing-rehearsal']
            if variant == 'skip-rehearsal': package['jobs']['signing-rehearsal']['if'] = 'false'
            if variant == 'skip-windows': package['jobs']['windows-installer']['if'] = 'false'
            if variant == 'ignore-build-failure': package['jobs']['android-unsigned']['continue-on-error'] = 'true'
            if variant == 'ignore-signing-failure': next(s for s in sign['steps'] if s['id'] == 'sign-artifacts')['continue-on-error'] = 'true'
            with self.subTest(variant=variant): self.assertTrue(check(self.docs, self.lock))

    def test_required_pr_checks_target_only_rebuild(self):
        name = '.github/workflows/rewrite-ci.yml'
        self.assertEqual(self.docs[name]['on']['pull_request'],
                         {'branches': ['rebuild-2026-11-11']})
        for variant in ('missing', 'main', 'wildcard', 'unrestricted', 'push-main'):
            with self.subTest(variant=variant):
                self.setUp()
                triggers = self.docs[name]['on']
                if variant == 'missing':
                    del triggers['pull_request']
                elif variant == 'main':
                    triggers['pull_request']['branches'] = ['main']
                elif variant == 'wildcard':
                    triggers['pull_request']['branches'] = ['*']
                elif variant == 'unrestricted':
                    triggers['pull_request'] = {}
                elif variant == 'push-main':
                    triggers['push']['branches'] = ['main']
                self.assertTrue(check(self.docs, self.lock))

    def test_protected_steps_cannot_skip_post_approval_checks_or_replace_the_shell(self):
        for step_id in ('policy-recheck', 'verify-unsigned', 'sign-artifacts'):
            for field, value in (('if', 'false'), ('if', 'always()'), ('shell', 'python')):
                with self.subTest(step=step_id, field=field, value=value):
                    self.setUp()
                    sign = self.docs['.github/workflows/rewrite-ci.yml']['jobs']['android-signed-internal-qa']
                    next(s for s in sign['steps'] if s['id'] == step_id)[field] = value
                    self.assertTrue(check(self.docs, self.lock))

    def test_duplicate_keys_cannot_hide_a_workflow_override(self):
        with self.assertRaises(ValueError):
            yaml.load('permissions: {contents: read}\npermissions: {contents: write}\n', Loader=UniqueLoader)

    def test_direct_packaging_dispatch_cannot_skip_workflow_policy(self):
        gate = self.docs['.github/workflows/rewrite-packaging.yml']['jobs']['signing-policy']
        gate['steps'] = [s for s in gate['steps'] if s.get('id') != 'workflow-check']
        self.assertTrue(check(self.docs, self.lock))
