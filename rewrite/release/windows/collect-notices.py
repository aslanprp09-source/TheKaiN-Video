"""Copy installed notices and pinned upstream Qt notice/attribution texts, not code."""
import base64
from concurrent.futures import ThreadPoolExecutor
import hashlib
from importlib import metadata
import json
import os
from pathlib import Path
import re
import shutil
import sys
import urllib.request

UPSTREAM = {
    'qt/qtbase': '025bdad181de241e81bf853c8a2d7bf3d19261a9',
    'qt/qtsvg': '883a58532078d003cefef0a549bfe9f885ce41f5',
    'pyside/pyside-setup': 'f50facb83fc3dc5f3240c854668c7a6dd93871b2',
}


def api(path):
    headers = {'Accept': 'application/vnd.github+json', 'User-Agent': 'TheKaiN-notice-inventory'}
    if os.environ.get('GH_TOKEN'):
        headers['Authorization'] = 'Bearer ' + os.environ['GH_TOKEN']
    request = urllib.request.Request('https://api.github.com/' + path, headers=headers)
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.load(response)


def collect(output):
    output = Path(output)
    output.mkdir(parents=True, exist_ok=True)
    installed = []
    for dist in sorted(metadata.distributions(), key=lambda d: d.metadata['Name'].lower()):
        name = dist.metadata['Name']
        record = {'name': name, 'version': dist.version,
                  'license_declared': dist.metadata.get('License-Expression') or dist.metadata.get('License'),
                  'project_urls': dist.metadata.get_all('Project-URL', []), 'notice_files': []}
        for file in dist.files or []:
            if re.search(r'(^|/)(LICENSE[^/]*|COPYING[^/]*|NOTICE[^/]*)$', str(file), re.I):
                source = Path(dist.locate_file(file))
                if source.is_file():
                    dest = output / 'python-packages' / name / str(file)
                    dest.parent.mkdir(parents=True, exist_ok=True)
                    shutil.copyfile(source, dest)
                    record['notice_files'].append(dest.relative_to(output).as_posix())
        installed.append(record)
    (output / 'python-packages.json').write_text(json.dumps(installed, indent=2), encoding='utf-8')
    python_license = Path(sys.base_prefix) / 'LICENSE.txt'
    if not python_license.is_file():
        raise RuntimeError('CPython LICENSE.txt is required from the build interpreter')
    shutil.copyfile(python_license, output / 'CPython-LICENSE.txt')
    records = []
    for repo, commit in UPSTREAM.items():
        tree = api(f'repos/{repo}/git/trees/{commit}?recursive=1')
        if tree.get('truncated'):
            raise RuntimeError('Incomplete upstream notice inventory')
        selected = [row for row in tree['tree'] if row['type'] == 'blob' and (
            row['path'].startswith('LICENSES/') or
            row['path'].startswith('src/3rdparty/') and re.search(
                r'(^|/)(LICENSE[^/]*|COPYING[^/]*|COPYRIGHT[^/]*|qt_attribution\.json)$', row['path'], re.I))]
        if not selected:
            raise RuntimeError('No upstream licenses found')
        def download(row):
            raw = base64.b64decode(api(f"repos/{repo}/git/blobs/{row['sha']}")['content'])
            assert hashlib.sha1(f'blob {len(raw)}\0'.encode() + raw).hexdigest() == row['sha']
            dest = output / 'upstream' / repo / row['path']
            dest.parent.mkdir(parents=True, exist_ok=True)
            dest.write_bytes(raw)
            return {'repository': repo, 'commit': commit, 'path': row['path'],
                    'git_blob': row['sha'], 'sha256': hashlib.sha256(raw).hexdigest()}
        with ThreadPoolExecutor(max_workers=6) as pool:
            records.extend(pool.map(download, selected))
    (output / 'upstream-notices.json').write_text(json.dumps(records, indent=2), encoding='utf-8')
    shutil.copyfile(Path(__file__).parents[1] / 'EXPERIMENTAL.txt', output / 'README.txt')
    print(f'Collected {len(installed)} Python distributions and {len(records)} pinned upstream notice files')


if __name__ == '__main__':
    collect(sys.argv[1])
