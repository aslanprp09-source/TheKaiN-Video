import base64
import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
from artifact_signing import APK, AAB, verify, sign, main, public_environment, SECRET_NAMES, SigningFailure

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


def signing_environment(directory):
    return {'ANDROID_KEYSTORE_BASE64': base64.b64encode(b'private-owned-test-key').decode(),
        'THEKAIN_STORE_PASSWORD': 'private-owned-store-password',
        'THEKAIN_KEY_PASSWORD': 'private-owned-key-password', 'THEKAIN_KEY_ALIAS': 'private-owned-alias',
        'ANDROID_SIGNING_CERT_SHA256': 'a' * 64, 'RUNNER_TEMP': str(directory)}


def signing_cli(root, proof, output, environment):
    arguments = ['artifact_signing.py', 'sign', '--input', str(root), '--commit', 'a' * 40,
        '--apk-sha', proof['apk_sha256'], '--aab-sha', proof['aab_sha256'],
        '--manifest-sha', proof['manifest_sha256'], '--tools', '/tools', '--java-home', '/java', '--output', str(output)]
    stdout, stderr = io.StringIO(), io.StringIO()
    with patch.dict(os.environ, environment, clear=True), patch('sys.argv', arguments), \
            contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
        status = main()
    return status, stdout.getvalue() + stderr.getvalue()


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
            with patch.dict(os.environ, {}, clear=True), self.assertRaises(SigningFailure):
                sign(root, Path(directory) / 'signed', 'a' * 40, proof, '/tools', '/java')
            with self.assertRaises(ValueError):
                sign(root, Path(directory) / 'signed', 'a' * 40, None, '/tools', '/java')

    def test_configuration_diagnostics_identify_repairs_without_disclosing_values_or_writing_keys(self):
        cases = [
            ('ANDROID_KEYSTORE_BASE64', '', 'MISSING_KEYSTORE'),
            ('THEKAIN_STORE_PASSWORD', '', 'MISSING_STORE_PASSWORD'),
            ('THEKAIN_KEY_ALIAS', '', 'MISSING_KEY_ALIAS'),
            ('THEKAIN_KEY_PASSWORD', '', 'MISSING_KEY_PASSWORD'),
            ('ANDROID_SIGNING_CERT_SHA256', '', 'INVALID_CERTIFICATE_SHA256'),
            ('ANDROID_SIGNING_CERT_SHA256', 'AA:' * 31 + 'AA', 'INVALID_CERTIFICATE_SHA256'),
            ('ANDROID_SIGNING_CERT_SHA256', 'a' * 64 + '\n', 'INVALID_CERTIFICATE_SHA256'),
            ('THEKAIN_KEY_ALIAS', '-private-invalid-alias', 'INVALID_KEY_ALIAS'),
            ('ANDROID_KEYSTORE_BASE64', 'private-invalid-base64\n', 'INVALID_KEYSTORE_BASE64'),
            ('ANDROID_KEYSTORE_BASE64', base64.b64encode(b'owned-test-key').decode() + '\n', 'INVALID_KEYSTORE_BASE64'),
            ('ANDROID_KEYSTORE_BASE64', base64.b64encode(b'x' * (1024 * 1024 + 1)).decode(), 'INVALID_KEYSTORE_SIZE'),
        ]
        for name, value, expected_code in cases:
            with self.subTest(code=expected_code), tempfile.TemporaryDirectory() as directory:
                root = Path(directory) / 'inputs'
                proof = inputs(root)
                environment = signing_environment(directory)
                environment[name] = value
                with patch('artifact_signing.run_tool') as tool:
                    status, log = signing_cli(root, proof, Path(directory) / 'signed', environment)
                self.assertEqual(status, 1)
                self.assertIn(expected_code + ':', log)
                tool.assert_not_called()
                self.assertEqual(list(Path(directory).iterdir()), [root])
                for secret in [environment[k] for k in SECRET_NAMES if environment[k]]:
                    self.assertNotIn(secret, log)

    def test_stage_diagnostics_redact_tool_failures_and_clean_keys_and_partial_outputs(self):
        for fail_at, expected_code in enumerate(('APK_SIGNING_FAILED', 'AAB_SIGNING_FAILED',
                'SIGNED_OUTPUT_VERIFICATION_FAILED', 'SIGNED_MANIFEST_FAILED'), 1):
            with self.subTest(code=expected_code), tempfile.TemporaryDirectory() as directory:
                root = Path(directory) / 'inputs'
                proof = inputs(root)
                output = Path(directory) / 'signed'
                environment = signing_environment(directory)
                calls = []
                def tool(arguments, env):
                    calls.append(arguments)
                    if len(calls) == fail_at:
                        output.mkdir()  # A failed inspector/manifest may already have written output.
                        (output / 'partial.apk').write_bytes(b'incomplete-output')
                        raise RuntimeError('private-owned-store-password\n::notice::private-tool-error')
                with patch('artifact_signing.run_tool', side_effect=tool):
                    status, log = signing_cli(root, proof, output, environment)
                self.assertEqual(status, 1)
                self.assertEqual(len(calls), fail_at)
                self.assertIn(expected_code + ':', log)
                self.assertNotIn('private-tool-error', log)
                for secret in [environment[k] for k in SECRET_NAMES]:
                    self.assertNotIn(secret, log)
                self.assertEqual(list(Path(directory).iterdir()), [root])

    def test_raw_signer_stdout_stderr_and_command_are_never_logged(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / 'inputs'
            proof = inputs(root)
            environment = signing_environment(directory)
            raw = '\n'.join(environment[k] for k in SECRET_NAMES) + '\n::notice::private-tool-output'
            failed = subprocess.CompletedProcess(['private-command'], 1, stdout=raw.encode(), stderr=raw.encode())
            with patch('artifact_signing.subprocess.run', return_value=failed):
                status, log = signing_cli(root, proof, Path(directory) / 'signed', environment)
            self.assertEqual(status, 1)
            self.assertIn('APK_SIGNING_FAILED:', log)
            self.assertNotIn('private-command', log)
            self.assertNotIn('private-tool-output', log)
            for secret in [environment[k] for k in SECRET_NAMES]:
                self.assertNotIn(secret, log)
            self.assertEqual(list(Path(directory).iterdir()), [root])

    def test_unexpected_failures_and_untrusted_codes_use_only_a_fixed_fallback(self):
        for error in (ValueError('private-unexpected-value\n::notice::private-tool-error'),
                SigningFailure('private-unexpected-value\n::notice::private-tool-error')):
            with self.subTest(error_type=type(error).__name__), tempfile.TemporaryDirectory() as directory:
                root = Path(directory) / 'inputs'
                proof = inputs(root)
                with patch('artifact_signing.sign', side_effect=error):
                    status, log = signing_cli(root, proof, Path(directory) / 'signed', signing_environment(directory))
                self.assertEqual(status, 1)
                self.assertIn('UNEXPECTED_SIGNING_FAILURE:', log)
                self.assertNotIn('private-unexpected-value', log)
                self.assertNotIn('private-tool-error', log)
