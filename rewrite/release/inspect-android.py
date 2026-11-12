"""Inspect actual APK/AAB identities and signatures; never infer signing from a filename."""
import argparse
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import struct
import zipfile


def run(arguments):
    return subprocess.run([str(a) for a in arguments], capture_output=True, text=True,
                          encoding='utf-8', errors='replace', timeout=60)


def inspect(apk, aab, tools, signed, expected_certificate=None):
    tools = Path(tools)
    badging = run([tools / 'aapt', 'dump', 'badging', apk])
    if badging.returncode or 'application-debuggable' in badging.stdout:
        raise ValueError('Release manifest is invalid or debuggable')
    identity = re.search(r"package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", badging.stdout)
    if not identity or identity.groups() != ('com.thekain.video.rebuild', '2', '0.1.0-m6'):
        raise ValueError('Unexpected release identity')
    apk_check = run([tools / 'apksigner', 'verify', '--verbose', '--print-certs', apk])
    certificate = None
    if signed:
        if not expected_certificate or not re.fullmatch('[0-9a-fA-F]{64}', expected_certificate):
            raise ValueError('A trusted public signing-certificate SHA-256 is required')
        digest = re.search(r'Signer #1 certificate SHA-256 digest: ([0-9a-fA-F]+)', apk_check.stdout)
        if apk_check.returncode or not digest or digest[1].lower() != expected_certificate.lower():
            raise ValueError('APK signature/certificate mismatch')
        certificate = digest[1].lower()
    elif apk_check.returncode != 1 or 'DOES NOT VERIFY' not in apk_check.stdout + apk_check.stderr or 'Missing META-INF/MANIFEST.MF' not in apk_check.stdout + apk_check.stderr:
        raise ValueError('Expected a verified unsigned-APK diagnostic, not a signed APK or tool failure')
    with zipfile.ZipFile(apk) as archive:
        inventory = json.loads(archive.read('assets/third_party/inventory.json'))
        if not inventory['components']:
            raise ValueError('Missing resolved release inventory')
        native = []
        for path in archive.namelist():
            if not path.startswith('lib/') or not path.endswith('.so'):
                continue
            blob = archive.read(path)
            if blob[:4] != b'\x7fELF' or blob[5] != 1 or blob[4] not in (1, 2):
                raise ValueError('Unexpected native binary format')
            is64 = blob[4] == 2
            offset = struct.unpack_from('<Q' if is64 else '<I', blob, 32 if is64 else 28)[0]
            size, count = struct.unpack_from('<HH', blob, 54 if is64 else 42)
            alignments = [struct.unpack_from('<Q' if is64 else '<I', blob, offset + i * size + (48 if is64 else 28))[0]
                          for i in range(count) if struct.unpack_from('<I', blob, offset + i * size)[0] == 1]
            if not alignments or min(alignments) < 16384:
                raise ValueError('Native ELF does not meet the 16 KiB alignment prerequisite')
            native.append({'file': path, 'load_segment_alignments': alignments})
    alignment = run([tools / 'zipalign', '-c', '-P', '16', '4', apk])
    if alignment.returncode:
        raise ValueError('APK ZIP alignment verification failed')
    with zipfile.ZipFile(aab) as archive:
        jar_signers = [p for p in archive.namelist() if re.fullmatch(r'META-INF/[^/]+\.(RSA|DSA|EC)', p, re.I)]
        if bool(jar_signers) != signed:
            raise ValueError('Unexpected AAB signature state')
    if signed:
        jar = run(['jarsigner', '-verify', aab])
        cert = run(['keytool', '-printcert', '-jarfile', aab])
        jar_digest = re.search(r'SHA256:\s*([0-9A-Fa-f:]+)', cert.stdout)
        if jar.returncode or cert.returncode or not jar_digest or jar_digest[1].replace(':', '').lower() != certificate:
            raise ValueError('AAB signature/certificate mismatch')
    return {'application_id': identity[1], 'version_code': int(identity[2]), 'version_name': identity[3],
            'debuggable': False, 'signed': signed, 'certificate_sha256': certificate,
            'runtime_artifacts': len(inventory['components']), 'native_libraries': native,
            'apk_zip_alignment': 'PASS: zipalign -c -P 16 4', 'runtime_16k_device': 'BLOCKED: no device run',
            'physical_device_qa': 'BLOCKED: not recorded', 'production_ready': False}


if __name__ == '__main__':
    p = argparse.ArgumentParser()
    p.add_argument('--apk', type=Path, required=True)
    p.add_argument('--aab', type=Path, required=True)
    p.add_argument('--tools', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--signed', action='store_true')
    args = p.parse_args()
    result = inspect(args.apk, args.aab, args.tools, args.signed, os.environ.get('ANDROID_SIGNING_CERT_SHA256'))
    args.output.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(args.apk, args.output / ('TheKaiN-0.1.0-m6-signed-internal-qa.apk' if args.signed else 'TheKaiN-0.1.0-m6-unsigned.apk'))
    shutil.copyfile(args.aab, args.output / ('TheKaiN-0.1.0-m6-signed-internal-qa.aab' if args.signed else 'TheKaiN-0.1.0-m6-unsigned.aab'))
    (args.output / 'inspection.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
    print(json.dumps(result, indent=2))
