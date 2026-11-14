"""Owned temporary signing test, outside protected jobs. Never uploads keys/signed copies."""
import base64
import contextlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile
from unittest.mock import patch
from artifact_signing import verify, sign, digest, SigningFailure, run_tool as real_tool


def rehearse():
    source = Path(os.environ['CI_UNSIGNED_INPUT'])
    commit = os.environ['GITHUB_SHA']
    java = Path(os.environ['JAVA_HOME'])
    tools = Path(os.environ['ANDROID_HOME']) / 'build-tools/35.0.0'
    proof = verify(source, commit)
    password = 'owned-ci-fixture-not-a-secret'
    env = {**os.environ, 'CI_TEST_PASSWORD': password}
    with tempfile.TemporaryDirectory(prefix='thekain-ci-rehearsal-', dir=os.environ.get('RUNNER_TEMP')) as directory:
        root = Path(directory)
        key, cert = root / 'disposable.p12', root / 'public.der'
        common = [java / 'bin/keytool']
        subprocess.run([str(x) for x in common + ['-genkeypair', '-alias', 'ci-fixture', '-keyalg', 'RSA',
            '-keysize', '4096', '-storetype', 'PKCS12', '-sigalg', 'SHA256withRSA',
            '-validity', '1', '-dname', 'CN=TheKaiN disposable CI test only', '-keystore', key,
            '-storepass:env', 'CI_TEST_PASSWORD', '-keypass:env', 'CI_TEST_PASSWORD']], env=env, check=True,
            capture_output=True, timeout=60)
        subprocess.run([str(x) for x in common + ['-exportcert', '-alias', 'ci-fixture', '-keystore', key,
            '-storepass:env', 'CI_TEST_PASSWORD', '-file', cert]], env=env, check=True, capture_output=True, timeout=60)
        fingerprint = digest(cert)
        values = {'ANDROID_KEYSTORE_BASE64': base64.b64encode(key.read_bytes()).decode(),
            'THEKAIN_STORE_PASSWORD': password, 'THEKAIN_KEY_PASSWORD': password, 'THEKAIN_KEY_ALIAS': 'ci-fixture',
            'ANDROID_SIGNING_CERT_SHA256': fingerprint}
        stage_root = Path(os.environ.get('RUNNER_TEMP') or tempfile.gettempdir())
        before = set(stage_root.glob('thekain-signing-*'))

        def intact(output):
            if output.exists() or set(stage_root.glob('thekain-signing-*')) != before or verify(source, commit, proof) != proof:
                raise RuntimeError('REHEARSAL_CLEANUP_OR_SOURCE_CHANGED')

        def rejected(label, changed, expected_code, corrupt=None):
            output = root / label
            def tool(arguments, environment):
                if corrupt and len(arguments) > 1 and Path(arguments[1]).name == 'inspect-android.py':
                    argument = '--aab' if corrupt == 'unsigned-aab-entry' else '--' + corrupt
                    path = Path(arguments[arguments.index(argument) + 1])
                    if corrupt == 'unsigned-aab-entry':
                        with zipfile.ZipFile(path, 'a') as archive:
                            archive.writestr('base/unsigned-regression.txt', b'unsigned owned fixture')
                    else:
                        path.write_bytes(path.read_bytes()[:-1])  # Damage the signed copy, never the unsigned upload.
                real_tool(arguments, environment)
            with patch.dict(os.environ, {**values, **changed}), \
                    (patch('artifact_signing.run_tool', side_effect=tool) if corrupt else contextlib.nullcontext()):
                try:
                    sign(source, output, commit, proof, tools, java)
                except SigningFailure as error:
                    if error.code != expected_code:
                        raise RuntimeError('REHEARSAL_WRONG_FAILURE_CODE') from None
                else:
                    raise RuntimeError('REHEARSAL_INVALID_SIGNATURE_ACCEPTED')
            intact(output)

        with patch.dict(os.environ, values):
            sign(source, root / 'signed', commit, proof, tools, java)
            result = json.loads((root / 'signed/inspection.json').read_text())
            if not (result['signed'] and result['certificate_sha256'] == fingerprint and result['production_ready'] is False):
                raise RuntimeError('REHEARSAL_SIGNED_INSPECTION_FAILED')
        for name, code in [('ANDROID_KEYSTORE_BASE64', 'MISSING_KEYSTORE'),
                ('THEKAIN_STORE_PASSWORD', 'MISSING_STORE_PASSWORD'), ('THEKAIN_KEY_ALIAS', 'MISSING_KEY_ALIAS'),
                ('THEKAIN_KEY_PASSWORD', 'MISSING_KEY_PASSWORD')]:
            rejected(code.lower(), {name: ''}, code)
        rejected('wrong-alias', {'THEKAIN_KEY_ALIAS': 'absent-fixture-alias'}, 'APK_SIGNING_FAILED')
        rejected('broken-base64', {'ANDROID_KEYSTORE_BASE64': '!'}, 'INVALID_KEYSTORE_BASE64')
        rejected('wrong-store-password', {'THEKAIN_STORE_PASSWORD': 'wrong-fixture-password'}, 'APK_SIGNING_FAILED')
        rejected('wrong-key-password', {'THEKAIN_KEY_PASSWORD': 'wrong-fixture-password'}, 'APK_SIGNING_FAILED')
        rejected('wrong-certificate', {'ANDROID_SIGNING_CERT_SHA256': '0' * 64}, 'SIGNED_OUTPUT_VERIFICATION_FAILED')
        rejected('corrupt-signed-apk', {}, 'SIGNED_OUTPUT_VERIFICATION_FAILED', 'apk')
        rejected('corrupt-signed-aab', {}, 'SIGNED_OUTPUT_VERIFICATION_FAILED', 'aab')
        rejected('unsigned-aab-entry', {}, 'SIGNED_OUTPUT_VERIFICATION_FAILED', 'unsigned-aab-entry')
        intact(root / 'absent-output')
    print('::notice title=Signing rehearsal::PASS: disposable RSA-4096 PKCS12, signed APK/AAB verified; 12 negative cases rejected, private staging cleaned, unsigned input unchanged; no key/signed test output retained or uploaded')


def main():
    try:
        rehearse()
    except (RuntimeError, ValueError, OSError, KeyError, subprocess.SubprocessError):
        print('::error title=Signing rehearsal::SIGNING_REHEARSAL_FAILED: no test key or signed output is accepted.')
        return 1
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
