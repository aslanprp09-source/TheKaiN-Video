"""Real JAR verification with a disposable key; APK behavior is covered by rehearsal."""
import hashlib
import importlib.util
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import zipfile

spec = importlib.util.spec_from_file_location('android_inspection',
    Path(__file__).parents[1] / 'release/inspect-android.py')
inspection = importlib.util.module_from_spec(spec)
spec.loader.exec_module(inspection)


class AndroidSignatureTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temporary = tempfile.TemporaryDirectory(prefix='thekain-test-public-verification-')
        cls.addClassCleanup(cls.temporary.cleanup)
        cls.root = Path(cls.temporary.name)
        key, cert = cls.root / 'disposable.p12', cls.root / 'public.der'
        # This fixed password protects only an automatically deleted test key.
        password = 'owned-disposable-fixture-only'
        def tool(arguments):
            result = inspection.run(arguments)
            if result.returncode:
                raise RuntimeError('DISPOSABLE_VERIFICATION_FIXTURE_FAILED')
        tool(['keytool', '-genkeypair', '-alias', 'fixture', '-keyalg', 'RSA', '-keysize', '2048',
              '-validity', '1', '-dname', 'CN=TheKaiN disposable regression only', '-storetype', 'PKCS12',
              '-keystore', key, '-storepass', password, '-keypass', password])
        tool(['keytool', '-exportcert', '-alias', 'fixture', '-keystore', key,
              '-storepass', password, '-file', cert])
        cls.fingerprint = hashlib.sha256(cert.read_bytes()).hexdigest()
        cls.unsigned, cls.signed = cls.root / 'unsigned.aab', cls.root / 'signed.aab'
        with zipfile.ZipFile(cls.unsigned, 'w') as archive:
            archive.writestr('base/owned.txt', b'owned regression input')
        tool(['jarsigner', '-keystore', key, '-storepass', password, '-keypass', password,
              '-signedjar', cls.signed, cls.unsigned, 'fixture'])

    def setUp(self):
        temporary = tempfile.TemporaryDirectory(dir=self.root)
        self.addCleanup(temporary.cleanup)
        self.folder = Path(temporary.name)
        self.aab, self.apk = self.folder / 'test.aab', self.folder / 'test.apk'
        shutil.copyfile(self.signed, self.aab)
        with zipfile.ZipFile(self.apk, 'w') as archive:
            archive.writestr('assets/third_party/inventory.json', json.dumps({'components': ['owned']}))

    def inspect(self, fingerprint=None):
        real_run = inspection.run
        def tools(arguments):
            name = Path(arguments[0]).name
            # Isolate JAR verification from Android SDK availability in the policy unit job.
            if name == 'aapt':
                text = "package: name='com.thekain.video.rebuild' versionCode='2' versionName='0.1.0-m6'"
            elif name == 'apksigner':
                text = 'Signer #1 certificate SHA-256 digest: ' + self.fingerprint
            elif name == 'zipalign':
                text = ''
            else:
                return real_run(arguments)
            return subprocess.CompletedProcess(arguments, 0, stdout=text, stderr='')
        with patch.object(inspection, 'run', side_effect=tools):
            return inspection.inspect(self.apk, self.aab, '/unused-sdk', True,
                                      fingerprint if fingerprint is not None else self.fingerprint)

    def test_expected_self_signed_certificate_is_valid_without_ignoring_strict_errors(self):
        self.assertTrue(self.inspect()['signed'])

    def test_unsigned_entry_appended_to_signed_bundle_is_rejected(self):
        with zipfile.ZipFile(self.aab, 'a') as archive:
            archive.writestr('base/unsigned.txt', b'unsigned injected payload')
        with self.assertRaises(ValueError):
            self.inspect()

    def test_modified_signed_payload_is_rejected(self):
        with zipfile.ZipFile(self.aab) as archive:
            records = [(entry, archive.read(entry)) for entry in archive.infolist()]
        with zipfile.ZipFile(self.aab, 'w') as archive:
            for entry, data in records:
                archive.writestr(entry, b'changed payload' if entry.filename == 'base/owned.txt' else data)
        with self.assertRaises(ValueError):
            self.inspect()

    def test_unsigned_bundle_is_rejected(self):
        shutil.copyfile(self.unsigned, self.aab)
        with self.assertRaises(ValueError):
            self.inspect()

    def test_other_certificate_is_rejected(self):
        with self.assertRaises(ValueError):
            inspection.verify_bundle_signature(self.aab, '0' * 64)
