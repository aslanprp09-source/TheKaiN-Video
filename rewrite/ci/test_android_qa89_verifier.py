from copy import deepcopy
import hashlib
import json
import os
from pathlib import Path
import stat
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import zipfile

import android_qa89_verifier as qa
import android_artifact_verifier as original
from test_android_artifact_verifier import FakeAPI, ENV
from workflow_policy import check, load, ROOT


class QA89VerifierTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.pin = deepcopy(qa.load_pin())
        self.apk = next(n for n in self.pin['files'] if n.endswith('.apk'))
        self.aab = next(n for n in self.pin['files'] if n.endswith('.aab'))
        self.raw = {self.apk: b'owned APK fixture', self.aab: b'owned AAB fixture',
            'inspection.json': json.dumps({'signed': True, 'debuggable': False,
                'application_id': 'com.thekain.video.rebuild', 'version_code': 3,
                'version_name': '0.1.0-m6', 'certificate_sha256': self.pin['certificate_sha256'],
                'production_ready': False}).encode()}
        files = {n: {'bytes': len(b), 'sha256': hashlib.sha256(b).hexdigest()} for n, b in self.raw.items()}
        self.pin['files'] = {n: files[n] for n in (self.apk, self.aab)}
        records = [{'file': n, **d} for n, d in files.items()]
        self.raw['manifest.json'] = json.dumps({'schema': 1, 'source_commit': self.pin['source_commit'],
            'version': '0.1.0-m6', 'platform': 'android', 'channel': 'signed-internal-qa',
            'production_ready': False, 'files': records}).encode()
        self.raw['SHA256SUMS'] = ''.join(f"{r['sha256']}  {r['file']}\n" for r in records).encode()

    def archive(self, raw=None):
        path = self.root / 'fixture.zip'
        with zipfile.ZipFile(path, 'w', compression=zipfile.ZIP_DEFLATED) as archive:
            for name, data in (self.raw if raw is None else raw).items(): archive.writestr(name, data)
        self.pin['zip_bytes'] = path.stat().st_size
        self.pin['zip_sha256'] = original.digest(path)
        return path

    def contents(self, path=None):
        folder = self.root / 'files'; folder.mkdir()
        return qa.contents(path or self.archive(), folder, self.pin, {})

    def test_fixed_current_profile_does_not_repin_original(self):
        pin = qa.load_pin()
        self.assertEqual((pin['run_id'], pin['artifact_id'], pin['version_code']), (44031800034, 14306540310, 3))
        old = original.parse_json(original.SOURCE.read_bytes())
        self.assertEqual((old['run_id'], old['artifact_id']), (43574136079, 14105834067))
        self.assertEqual(pin['zip_sha256'], 'f0c73413ffdffeea516c9475e905ca2dbce47cb52e7a8fbd4808083791ae13f7')

    def test_fixture_archive_checks_actual_bytes_source_and_all_metadata(self):
        self.assertEqual(self.contents()['version_code'], 3)

    def test_old_inspection_version_is_rejected_even_in_an_authenticated_zip(self):
        data = json.loads(self.raw['inspection.json']); data['version_code'] = 2
        self.raw['inspection.json'] = json.dumps(data).encode()
        manifest = json.loads(self.raw['manifest.json'])
        for record in manifest['files']:
            if record['file'] == 'inspection.json':
                record.update(bytes=len(self.raw['inspection.json']),
                              sha256=hashlib.sha256(self.raw['inspection.json']).hexdigest())
        self.raw['manifest.json'] = json.dumps(manifest).encode()
        self.raw['SHA256SUMS'] = ''.join(f"{r['sha256']}  {r['file']}\n" for r in manifest['files']).encode()
        with self.assertRaisesRegex(original.VerificationError, 'INSPECTION_MISMATCH'): self.contents()

    def test_wrong_media_hash_is_rejected(self):
        self.raw[self.apk] = b'changed APK'
        with self.assertRaisesRegex(original.VerificationError, 'FILE_SIZE_OR_DIGEST_MISMATCH'): self.contents()

    def test_wrong_manifest_source_is_rejected(self):
        value = json.loads(self.raw['manifest.json']); value['source_commit'] = '0' * 40
        self.raw['manifest.json'] = json.dumps(value).encode()
        with self.assertRaisesRegex(original.VerificationError, 'MANIFEST_SOURCE_MISMATCH'): self.contents()

    def test_traversal_extra_entry_is_rejected(self):
        self.raw['../outside'] = b'owned malicious fixture'
        with self.assertRaisesRegex(original.VerificationError, 'UNSAFE_ZIP_ENTRIES'): self.contents()

    def test_symlink_is_rejected_without_following_it(self):
        path = self.root / 'links.zip'
        with zipfile.ZipFile(path, 'w') as archive:
            for name, data in self.raw.items():
                info = zipfile.ZipInfo(name)
                if name == self.apk: info.external_attr = (stat.S_IFLNK | 0o777) << 16
                archive.writestr(info, data)
        with self.assertRaisesRegex(original.VerificationError, 'UNSAFE_ZIP_ENTRY'): self.contents(path)

    def test_official_aapt_identity_requires_version3_and_non_debuggable(self):
        for code, suffix, accepted in [('3', '', True), ('2', '', False), ('3', '\napplication-debuggable', False)]:
            text = f"package: name='com.thekain.video.rebuild' versionCode='{code}' versionName='0.1.0-m6'\nsdkVersion:'26'\ntargetSdkVersion:'35'{suffix}\n"
            with self.subTest(code=code, suffix=suffix), patch.object(original, 'tool', return_value=text):
                if accepted: self.assertEqual(qa.apk_manifest(Path('owned.apk'), self.root, self.root)['version_code'], 3)
                else:
                    with self.assertRaises(original.VerificationError): qa.apk_manifest(Path('owned.apk'), self.root, self.root)

    def test_fixture_execution_has_separate_scope_and_never_claims_release(self):
        archive = self.archive()
        with patch.object(original, 'tool_versions', return_value={}), \
             patch.object(qa, 'apk_manifest', return_value={'version_code': 3}), \
             patch.object(original, 'verify_apk', return_value={}), \
             patch.object(original, 'verify_aab', return_value={}):
            report = qa.execute(self.pin, FakeAPI(self.pin, archive), ENV, self.root, self.root, self.root / 'report')
        self.assertEqual(report['overall_status'], 'PASS', report)
        self.assertEqual(report['scope'], 'independent-official-tools-run-89')
        self.assertFalse(report['production_ready'])
        self.assertFalse(report['automatic_publication'])

    def test_real_official_aapt_decodes_version3_and_rejects_version2(self):
        sdk = Path(os.environ.get('ANDROID_HOME', '/missing'))
        tools = sdk / 'build-tools/35.0.0'
        platform = sdk / 'platforms/android-35/android.jar'
        if not (tools / 'aapt').is_file() or not platform.is_file():
            self.skipTest('Official SDK35 fixture tools unavailable')
        for version in (3, 2):
            manifest = self.root / 'AndroidManifest.xml'
            manifest.write_text('<manifest xmlns:android="http://schemas.android.com/apk/res/android" '
                f'package="com.thekain.video.rebuild" android:versionCode="{version}" android:versionName="0.1.0-m6">'
                '<uses-sdk android:minSdkVersion="26" android:targetSdkVersion="35"/>'
                '<application android:label="Owned verifier fixture"/></manifest>')
            apk = self.root / f'owned-{version}.apk'
            built = subprocess.run([str(tools / 'aapt'), 'package', '-f', '-M', str(manifest),
                '-I', str(platform), '-F', str(apk)], capture_output=True, timeout=30)
            self.assertEqual(built.returncode, 0, built.stderr.decode(errors='replace'))
            output = self.root / f'aapt-{version}'; output.mkdir()
            if version == 3: self.assertEqual(qa.apk_manifest(apk, tools, output)['version_code'], 3)
            else:
                with self.assertRaisesRegex(original.VerificationError, 'APK_IDENTITY_MISMATCH'):
                    qa.apk_manifest(apk, tools, output)


class QA89WorkflowPolicyTest(unittest.TestCase):
    def test_read_only_contract_rejects_secret_build_permission_and_input_expansion(self):
        lock = json.loads((ROOT / 'rewrite/ci/actions-lock.json').read_text())
        for variant in ('environment', 'secret', 'write', 'build', 'cache', 'skip', 'arbitrary-source', 'wrong-ref', 'caller-secret'):
            docs = {name: load(ROOT / name) for name in lock['workflows']}
            workflow = docs['.github/workflows/rewrite-verify-qa89.yml']
            job = workflow['jobs']['verify']; steps = {s['id']: s for s in job['steps']}
            if variant == 'environment': job['environment'] = 'release-signing'
            if variant == 'secret': steps['verify']['env']['KEY'] = '${{ secrets.ANDROID_KEYSTORE_BASE64 }}'
            if variant == 'write': job['permissions']['actions'] = 'write'
            if variant == 'build': steps['policy']['run'] += 'gradle assembleRelease\n'
            if variant == 'cache': steps['setup-java']['with']['cache'] = 'gradle'
            if variant == 'skip': steps['verify']['if'] = 'false'
            if variant == 'arbitrary-source': workflow['on']['workflow_call']['inputs']['artifact_id'] = {'type': 'number'}
            if variant == 'wrong-ref': job['if'] = 'true'
            if variant == 'caller-secret': docs['.github/workflows/rewrite-ci.yml']['jobs']['android-qa89-independent-verifier']['secrets'] = 'inherit'
            with self.subTest(variant=variant): self.assertTrue(check(docs, lock))
