"""Owned temporary signing test, outside protected jobs. Never uploads keys/signed copies."""
import base64
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
from unittest.mock import patch
from artifact_signing import verify, sign, digest


def main():
    source = Path(os.environ['CI_UNSIGNED_INPUT'])
    commit = os.environ['GITHUB_SHA']
    java = Path(os.environ['JAVA_HOME'])
    tools = Path(os.environ['ANDROID_HOME']) / 'build-tools/35.0.0'
    proof = verify(source, commit)
    password = 'owned-ci-fixture-not-a-secret'
    env = {**os.environ, 'CI_TEST_PASSWORD': password}
    with tempfile.TemporaryDirectory(prefix='thekain-ci-rehearsal-', dir=os.environ.get('RUNNER_TEMP')) as directory:
        root = Path(directory)
        key, cert = root / 'disposable.jks', root / 'public.der'
        common = [java / 'bin/keytool']
        subprocess.run([str(x) for x in common + ['-genkeypair', '-alias', 'ci-fixture', '-keyalg', 'RSA',
            '-keysize', '2048', '-validity', '1', '-dname', 'CN=TheKaiN disposable CI test only', '-keystore', key,
            '-storepass:env', 'CI_TEST_PASSWORD', '-keypass:env', 'CI_TEST_PASSWORD']], env=env, check=True,
            capture_output=True, timeout=60)
        subprocess.run([str(x) for x in common + ['-exportcert', '-alias', 'ci-fixture', '-keystore', key,
            '-storepass:env', 'CI_TEST_PASSWORD', '-file', cert]], env=env, check=True, capture_output=True, timeout=60)
        fingerprint = digest(cert)
        values = {'ANDROID_KEYSTORE_BASE64': base64.b64encode(key.read_bytes()).decode(),
            'THEKAIN_STORE_PASSWORD': password, 'THEKAIN_KEY_PASSWORD': password, 'THEKAIN_KEY_ALIAS': 'ci-fixture',
            'ANDROID_SIGNING_CERT_SHA256': fingerprint}
        with patch.dict(os.environ, values):
            sign(source, root / 'signed', commit, proof, tools, java)
            result = json.loads((root / 'signed/inspection.json').read_text())
            assert result['signed'] and result['certificate_sha256'] == fingerprint and result['production_ready'] is False
            os.environ['ANDROID_SIGNING_CERT_SHA256'] = '0' * 64
            try:
                sign(source, root / 'wrong-certificate', commit, proof, tools, java)
            except RuntimeError:
                assert not (root / 'wrong-certificate').exists()
            else:
                raise AssertionError('Wrong certificate accepted')
        assert verify(source, commit, proof) == proof  # Unsigned upload stays unsigned and byte-identical.
    print('::notice title=Signing rehearsal::PASS: disposable key, APK/AAB verified, wrong fingerprint rejected; no key/signed test output retained or uploaded')


if __name__ == '__main__':
    main()
