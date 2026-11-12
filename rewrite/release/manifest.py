"""Artifact identity/integrity report; a hash does not assert QA or signing readiness."""
import argparse
import hashlib
import json
from pathlib import Path
import re


def write_manifest(root, commit, platform, channel):
    if not re.fullmatch(r'[0-9a-f]{40}', commit):
        raise ValueError('A full source commit is required')
    root = Path(root).resolve()
    files = []
    for path in sorted(root.rglob('*')):
        if path.is_symlink():
            raise ValueError('Artifact symlinks are not allowed')
        if not path.is_file() or path.name in ('manifest.json', 'SHA256SUMS'):
            continue
        with path.open('rb') as stream:
            digest = hashlib.file_digest(stream, 'sha256').hexdigest()
        files.append({'file': path.relative_to(root).as_posix(), 'bytes': path.stat().st_size, 'sha256': digest})
    if not files:
        raise ValueError('No artifacts')
    report = {'schema': 1, 'source_commit': commit, 'version': '0.1.0-m6', 'platform': platform,
              'channel': channel, 'production_ready': False, 'files': files}
    (root / 'manifest.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    (root / 'SHA256SUMS').write_text(''.join(f"{f['sha256']}  {f['file']}\n" for f in files), encoding='utf-8')
    return report


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('root', type=Path)
    parser.add_argument('--commit', required=True)
    parser.add_argument('--platform', required=True, choices=['windows-x64', 'android'])
    parser.add_argument('--channel', required=True, choices=['unsigned-experimental', 'signed-internal-qa'])
    args = parser.parse_args()
    report = write_manifest(args.root, args.commit, args.platform, args.channel)
    print(json.dumps(report, indent=2))
