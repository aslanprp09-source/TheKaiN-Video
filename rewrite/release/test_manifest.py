import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from manifest import write_manifest


class ArtifactManifestTest(unittest.TestCase):
    def test_records_actual_bytes_and_commit_and_repeated_run_excludes_its_own_reports(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'sample.apk').write_bytes(b'owned artifact')
            first = write_manifest(root, 'a' * 40, 'android', 'unsigned-experimental')
            second = write_manifest(root, 'a' * 40, 'android', 'unsigned-experimental')
            self.assertEqual(first, second)
            self.assertEqual(second['files'], [{'file': 'sample.apk', 'bytes': 14,
                'sha256': hashlib.sha256(b'owned artifact').hexdigest()}])
            self.assertFalse(json.loads((root / 'manifest.json').read_text())['production_ready'])

    def test_rejects_missing_source_identity_and_empty_artifact_set(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(ValueError):
                write_manifest(directory, 'main', 'android', 'unsigned-experimental')
            with self.assertRaises(ValueError):
                write_manifest(directory, 'a' * 40, 'android', 'unsigned-experimental')
