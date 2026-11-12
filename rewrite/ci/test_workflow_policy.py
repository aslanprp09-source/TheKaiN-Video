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
            sign = package['jobs']['android-signed-internal-qa']
            steps = {s['id']: s for s in sign['steps']}
            if variant == 'trigger': package['on']['pull_request_target'] = {}
            if variant == 'ref': sign['if'] = 'inputs.sign_android'
            if variant == 'environment': sign['environment'] = 'unprotected'
            if variant == 'cache': steps['setup-java']['with']['cache'] = 'gradle'
            if variant == 'secret': steps['setup-java']['env'] = {'KEY': '${{ secrets.ANDROID_KEYSTORE_BASE64 }}'}
            if variant == 'download': steps['download-unsigned']['with']['run-id'] = '${{ inputs.run_id }}'
            if variant == 'upload': steps['upload-signed']['if'] = 'always()'
            with self.subTest(variant=variant): self.assertTrue(check(self.docs, self.lock))

    def test_duplicate_keys_cannot_hide_a_workflow_override(self):
        with self.assertRaises(ValueError):
            yaml.load('permissions: {contents: read}\npermissions: {contents: write}\n', Loader=UniqueLoader)

    def test_direct_packaging_dispatch_cannot_skip_workflow_policy(self):
        gate = self.docs['.github/workflows/rewrite-packaging.yml']['jobs']['signing-policy']
        gate['steps'] = [s for s in gate['steps'] if s.get('id') != 'workflow-check']
        self.assertTrue(check(self.docs, self.lock))
