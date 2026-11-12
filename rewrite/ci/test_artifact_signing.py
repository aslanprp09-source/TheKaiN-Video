import base64
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from artifact_signing import APK, AAB, verify, sign, public_environment, SECRET_NAMES

spec = importlib.util.spec_from_file_location('release_manifest', Path(__file__).parents[1] / 'release/manifest.py')
manifest = importlib.util.module_from_spec(spec)
spec.loader.exec_module(manifest)


def inputs(root):
    root.mkdir()
    (root / APK).write_bytes(b'owned APK fixture')
    (root / AAB).write_bytes(b'owned AAB fixture')
    (root / 'inspection.json').write_text(json.dumps({'signed': False, 'debuggable': False,
        'application_id': 'com.thekain.video.rebuild'}), encoding='utf-8')
    manifest.write_manifest(root, 'a' * 40, 'android', 'unsigned-experimental')
    return verify(root, 'a' * 40)


class ArtifactSigningTest(unittest.TestCase):
    def test_actual_digests_bind_the_manifest_and_source(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / 'inputs'
            proof = inputs(root)
            self.assertEqual(verify(root, 'a' * 40, proof), proof)
            for expected in ({**proof, 'apk_sha256': '0' * 64}, {**proof, 'manifest_sha256': '0' * 64}):
                with self.assertRaises(ValueError): verify(root, 'a' * 40, expected)
            with self.assertRaises(ValueError): verify(root, 'b' * 40, proof)

    def test_tampering_is_rejected_before_a_key_is_written_or_tool_is_run(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / 'inputs'
            proof = inputs(root)
            (root / APK).write_bytes(b'changed APK')
            with patch('artifact_signing.run_tool') as tool, self.assertRaises(ValueError):
                sign(root, Path(directory) / 'signed', 'a' * 40, proof, '/tools', '/java')
            tool.assert_not_called()
            self.assertEqual(list(Path(directory).glob('thekain-signing-*')), [])

    def test_extra_files_symlinks_duplicate_names_and_traversal_are_rejected(self):
        for variant in ('extra', 'symlink', 'duplicate', 'traversal'):
            with self.subTest(variant=variant), tempfile.TemporaryDirectory() as directory:
                root = Path(directory) / 'inputs'
                inputs(root)
                if variant == 'extra': (root / 'untrusted.py').write_text('raise Exception()')
                if variant == 'symlink':
                    (root / APK).unlink(); (root / APK).symlink_to(root / AAB)
                if variant in ('duplicate', 'traversal'):
                    path = root / 'manifest.json'
                    data = json.loads(path.read_text())
                    data['files'][1]['file'] = data['files'][0]['file'] if variant == 'duplicate' else '../outside.apk'
                    path.write_text(json.dumps(data))
                with self.assertRaises(ValueError): verify(root, 'a' * 40)

    def test_failure_cleans_private_key_and_passes_passwords_only_to_signers(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / 'inputs'
            proof = inputs(root)
            secrets = {'ANDROID_KEYSTORE_BASE64': base64.b64encode(b'owned test key').decode(),
                'THEKAIN_STORE_PASSWORD': 'owned-password', 'THEKAIN_KEY_PASSWORD': 'owned-key-password',
                'THEKAIN_KEY_ALIAS': 'owned-alias', 'ANDROID_SIGNING_CERT_SHA256': 'a' * 64, 'RUNNER_TEMP': directory,
                'GH_TOKEN': 'owned-do-not-forward-token'}
            keys, count = [], []
            def tool(args, env):
                count.append(args)
                self.assertNotIn('GH_TOKEN', env)
                self.assertNotIn('ANDROID_KEYSTORE_BASE64', env)
                self.assertNotIn('owned-password', [str(a) for a in args])
                if len(count) == 1:
                    key = Path(args[args.index('--ks') + 1]); keys.append(key)
                    self.assertEqual(key.stat().st_mode & 0o777, 0o600)
                if len(count) == 3:
                    self.assertFalse(keys[0].exists())
                    self.assertTrue(all(name not in env for name in SECRET_NAMES))
                    raise RuntimeError('owned verification failure')
            with patch.dict(os.environ, secrets), patch('artifact_signing.run_tool', side_effect=tool), self.assertRaises(RuntimeError):
                sign(root, Path(directory) / 'signed', 'a' * 40, proof, '/tools', '/java')
            self.assertFalse(keys[0].exists())
            self.assertEqual(list(Path(directory).glob('thekain-signing-*')), [])
            self.assertFalse((Path(directory) / 'signed').exists())

    def test_missing_credentials_and_option_like_alias_fail_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / 'inputs'
            proof = inputs(root)
            with patch.dict(os.environ, {}, clear=True), self.assertRaises(ValueError):
                sign(root, Path(directory) / 'signed', 'a' * 40, proof, '/tools', '/java')
            with self.assertRaises(ValueError):
                sign(root, Path(directory) / 'signed', 'a' * 40, None, '/tools', '/java')
