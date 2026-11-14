"""Sign verified same-run outputs; no Gradle, project build, cache or secret-bearing logs."""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile

APK = 'TheKaiN-0.1.0-m6-unsigned.apk'
AAB = 'TheKaiN-0.1.0-m6-unsigned.aab'
FILES = {APK, AAB, 'inspection.json'}
SECRET_NAMES = ('ANDROID_KEYSTORE_BASE64', 'THEKAIN_STORE_PASSWORD', 'THEKAIN_KEY_ALIAS', 'THEKAIN_KEY_PASSWORD')
FAILURES = {
    'MISSING_KEYSTORE': 'Set environment secret ANDROID_KEYSTORE_BASE64.',
    'MISSING_STORE_PASSWORD': 'Set environment secret ANDROID_KEYSTORE_PASSWORD.',
    'MISSING_KEY_ALIAS': 'Set environment secret ANDROID_KEY_ALIAS.',
    'MISSING_KEY_PASSWORD': 'Set environment secret ANDROID_KEY_PASSWORD.',
    'INVALID_CERTIFICATE_SHA256': 'Set environment variable ANDROID_SIGNING_CERT_SHA256 to the public certificate SHA-256: exactly 64 hex characters, without separators.',
    'INVALID_KEY_ALIAS': 'The key alias must match the controlled ASCII alias policy.',
    'INVALID_KEYSTORE_BASE64': 'The keystore secret must contain strict single-line Base64.',
    'INVALID_KEYSTORE_SIZE': 'The decoded keystore must be nonempty and at most 1 MiB.',
    'APK_SIGNING_FAILED': 'APK signer failed. Verify the keystore, alias and passwords locally with the owner tool.',
    'AAB_SIGNING_FAILED': 'AAB signer failed. Verify the keystore, alias and passwords locally with the owner tool.',
    'SIGNED_OUTPUT_VERIFICATION_FAILED': 'Signed APK/AAB inspection failed. Verify the expected certificate and release identity; no output is accepted.',
    'SIGNED_MANIFEST_FAILED': 'Signed output manifest creation failed; no output is accepted.',
    'UNEXPECTED_SIGNING_FAILURE': 'Invalid artifact or unexpected signing failure; no output is accepted.',
}


class SigningFailure(RuntimeError):
    """Only fixed allowlisted codes may cross the secret-bearing boundary."""

    def __init__(self, code):
        self.code = code if code in FAILURES else 'UNEXPECTED_SIGNING_FAILURE'
        super().__init__(self.code)


def digest(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def unique_pairs(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError('Duplicate metadata keys')
        result[key] = value
    return result


def verify(folder, commit, expected=None):
    folder = Path(folder)
    if not re.fullmatch('[0-9a-f]{40}', commit) or folder.is_symlink() or not folder.is_dir():
        raise ValueError('Invalid artifact identity')
    children = list(folder.iterdir())
    if {p.name for p in children} != FILES | {'manifest.json', 'SHA256SUMS'}:
        raise ValueError('Unexpected artifact contents')
    if any(p.is_symlink() or not p.is_file() or not 0 < p.stat().st_size <= 256 * 1024 * 1024 for p in children):
        raise ValueError('Unsafe artifact file')
    manifest_path = folder / 'manifest.json'
    if manifest_path.stat().st_size > 1024 * 1024:
        raise ValueError('Oversized manifest')
    manifest = json.loads(manifest_path.read_text(encoding='utf-8'), object_pairs_hook=unique_pairs)
    if any(manifest.get(k) != v for k, v in {'schema': 1, 'source_commit': commit, 'platform': 'android',
        'channel': 'unsigned-experimental', 'version': '0.1.0-m6', 'production_ready': False}.items()):
        raise ValueError('Wrong artifact source/channel')
    records = manifest.get('files', [])
    if len(records) != 3 or {r.get('file') for r in records} != FILES:
        raise ValueError('Invalid manifest file set')
    for record in records:
        path = folder / record['file']
        if record.get('bytes') != path.stat().st_size or record.get('sha256') != digest(path):
            raise ValueError('Artifact hash/size mismatch')
    sums = ''.join(f"{r['sha256']}  {r['file']}\n" for r in records)
    if (folder / 'SHA256SUMS').read_text(encoding='utf-8') != sums:
        raise ValueError('Invalid checksum report')
    inspection = json.loads((folder / 'inspection.json').read_text(encoding='utf-8'), object_pairs_hook=unique_pairs)
    if inspection.get('signed') is not False or inspection.get('debuggable') is not False or \
            inspection.get('application_id') != 'com.thekain.video.rebuild':
        raise ValueError('Expected inspected unsigned release')
    proof = {'apk_sha256': digest(folder / APK), 'aab_sha256': digest(folder / AAB),
             'manifest_sha256': digest(manifest_path)}
    if expected is not None and (set(expected) != set(proof) or any(
            not re.fullmatch('[0-9a-f]{64}', expected[k] or '') or proof[k] != expected[k] for k in proof)):
        raise ValueError('Same-run proof mismatch')
    return proof


def public_environment():
    return {k: v for k, v in os.environ.items() if k in
            ('PATH', 'JAVA_HOME', 'LANG', 'LC_ALL', 'TZ', 'TMPDIR', 'ANDROID_SIGNING_CERT_SHA256')}


def run_tool(arguments, env):
    result = subprocess.run([str(a) for a in arguments], env=env, capture_output=True,
                            timeout=180, check=False)
    if result.returncode:
        raise RuntimeError('Signing/verification tool failed: ' + Path(arguments[0]).name)  # No raw stderr/credentials.


def signing_tool(code, arguments, env):
    try:
        run_tool(arguments, env)
    except (RuntimeError, OSError, subprocess.SubprocessError):
        raise SigningFailure(code) from None  # Never forward exception text or tool output.


def sign(folder, output, commit, expected, tools, java_home):
    if expected is None:
        raise ValueError('Independent same-run proof required')
    folder, output = Path(folder), Path(output).absolute()
    verify(folder, commit, expected)  # Before decoding or writing any key.
    folder = folder.resolve()
    if output.exists() or output.is_symlink() or output.is_relative_to(folder):
        raise ValueError('Signing output must be a fresh separate directory')
    certificate = os.environ.get('ANDROID_SIGNING_CERT_SHA256', '')
    values = {name: os.environ.get(name, '') for name in SECRET_NAMES}
    for name, code in zip(SECRET_NAMES, ('MISSING_KEYSTORE', 'MISSING_STORE_PASSWORD', 'MISSING_KEY_ALIAS', 'MISSING_KEY_PASSWORD')):
        if not values[name]:
            raise SigningFailure(code)
    if not re.fullmatch('[0-9a-fA-F]{64}', certificate):
        raise SigningFailure('INVALID_CERTIFICATE_SHA256')
    if not re.fullmatch('[A-Za-z0-9][A-Za-z0-9_.-]{0,127}', values['THEKAIN_KEY_ALIAS']):
        raise SigningFailure('INVALID_KEY_ALIAS')
    try:
        raw = base64.b64decode(values['ANDROID_KEYSTORE_BASE64'], validate=True)
    except ValueError:
        raise SigningFailure('INVALID_KEYSTORE_BASE64') from None
    if not 0 < len(raw) <= 1024 * 1024:
        raise SigningFailure('INVALID_KEYSTORE_SIZE')
    env = public_environment()
    tool_env = {**env, 'THEKAIN_STORE_PASSWORD': values['THEKAIN_STORE_PASSWORD'],
                'THEKAIN_KEY_PASSWORD': values['THEKAIN_KEY_PASSWORD']}
    release = Path(__file__).resolve().parents[1] / 'release'
    try:
        with tempfile.TemporaryDirectory(prefix='thekain-signing-', dir=os.environ.get('RUNNER_TEMP')) as directory:
            private = Path(directory)
            key = private / 'controlled.jks'
            fd = os.open(key, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(fd, 'wb') as stream:
                stream.write(raw)
            apk, aab = private / 'signed.apk', private / 'signed.aab'
            signing_tool('APK_SIGNING_FAILED', [Path(tools) / 'apksigner', 'sign', '--ks', key, '--ks-key-alias', values['THEKAIN_KEY_ALIAS'],
                '--ks-pass', 'env:THEKAIN_STORE_PASSWORD', '--key-pass', 'env:THEKAIN_KEY_PASSWORD',
                '--out', apk, folder / APK], tool_env)
            signing_tool('AAB_SIGNING_FAILED', [Path(java_home) / 'bin/jarsigner', '-keystore', key, '-storepass:env', 'THEKAIN_STORE_PASSWORD',
                '-keypass:env', 'THEKAIN_KEY_PASSWORD', '-signedjar', aab, folder / AAB, values['THEKAIN_KEY_ALIAS']], tool_env)
            key.unlink()  # No key exists while repository verification scripts execute.
            signing_tool('SIGNED_OUTPUT_VERIFICATION_FAILED', [sys.executable, release / 'inspect-android.py', '--signed', '--apk', apk, '--aab', aab,
                      '--tools', tools, '--output', output], env)
            signing_tool('SIGNED_MANIFEST_FAILED', [sys.executable, release / 'manifest.py', output, '--commit', commit,
                      '--platform', 'android', '--channel', 'signed-internal-qa'], env)
    except BaseException:
        if output.exists():
            shutil.rmtree(output)
        raise


def main():
    p = argparse.ArgumentParser()
    p.add_argument('mode', choices=('proof', 'verify', 'sign'))
    p.add_argument('--input', type=Path, required=True)
    p.add_argument('--commit', required=True)
    p.add_argument('--apk-sha')
    p.add_argument('--aab-sha')
    p.add_argument('--manifest-sha')
    p.add_argument('--tools', type=Path)
    p.add_argument('--java-home', type=Path)
    p.add_argument('--output', type=Path)
    args = p.parse_args()
    try:
        expected = None if args.mode == 'proof' else {'apk_sha256': args.apk_sha,
            'aab_sha256': args.aab_sha, 'manifest_sha256': args.manifest_sha}
        proof = verify(args.input, args.commit, expected)
        if args.mode == 'sign':
            if not all((args.tools, args.java_home, args.output)):
                raise ValueError('Signing tools/output required')
            sign(args.input, args.output, args.commit, expected, args.tools, args.java_home)
        if args.mode == 'proof' and os.environ.get('GITHUB_OUTPUT'):
            with Path(os.environ['GITHUB_OUTPUT']).open('a', encoding='utf-8') as stream:
                for key, value in proof.items():
                    stream.write(key + '=' + value + '\n')
        print(json.dumps({'verified': True, 'source_commit': args.commit, **proof}))
        return 0
    except (ValueError, RuntimeError, OSError, KeyError, TypeError, subprocess.SubprocessError) as error:
        code = error.code if isinstance(error, SigningFailure) else 'UNEXPECTED_SIGNING_FAILURE'
        print('::error title=Signing BLOCKED::' + code + ': ' + FAILURES[code] + ' No signed artifact is accepted.')
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
