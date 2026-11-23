"""Describe and verify this job's main debug APK. No release-signing access or keys."""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys

apk = Path(sys.argv[1]).resolve()
output = Path(sys.argv[2])
build_tools = Path(os.environ['ANDROID_HOME']) / 'build-tools' / '35.0.0'


def run(*arguments):
    return subprocess.run(arguments, check=True, capture_output=True, text=True, timeout=60).stdout


signatures = run(str(build_tools / 'apksigner'), 'verify', '--verbose', '--print-certs', str(apk))
certificates = re.findall(r'^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]{64})$', signatures, re.MULTILINE)
assert len(certificates) == 1, 'Expected one verified debug signer'
badging = run(str(build_tools / 'aapt'), 'dump', 'badging', str(apk))
package = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging, re.MULTILINE)
assert package and package[1] == 'com.thekain.video.rebuild', 'Expected the main application APK'
assert re.search(r'^application-debuggable\s*$', badging, re.MULTILINE), 'Expected a debug APK'
minimum = re.search(r"^sdkVersion:'(\d+)'", badging, re.MULTILINE)
target = re.search(r"^targetSdkVersion:'(\d+)'", badging, re.MULTILINE)
assert minimum and target, 'Missing SDK metadata'
checkout = run('git', 'rev-parse', 'HEAD').strip()
source = os.environ.get('BUILD_SOURCE_SHA') or checkout
assert re.fullmatch('[0-9a-f]{40}', source) and re.fullmatch('[0-9a-f]{40}', checkout), 'Invalid source SHA'
with apk.open('rb') as stream:
    digest = hashlib.file_digest(stream, 'sha256').hexdigest()
receipt = dict(schema=1, kind='MAIN_APP_INTERNAL_DEBUG', source_sha=source, tested_checkout_sha=checkout,
               tracked_worktree_dirty=bool(run('git', 'status', '--porcelain', '--untracked-files=no').strip()),
               apk=apk.name, apk_sha256=digest, bytes=apk.stat().st_size,
               application_id=package[1], versionCode=int(package[2]), versionName=package[3],
               min_sdk=int(minimum[1]), target_sdk=int(target[1]), debuggable=True,
               certificate_sha256=certificates[0].lower(), official_apksigner='PASS',
               production_ready=False, automatic_publication=False,
               independent_artifact_review='NOT_RUN',
               install_warning='Debug certificate may differ from the installed app. Do not uninstall or erase data to resolve a signature conflict.')
output.parent.mkdir(parents=True, exist_ok=True)
output.write_text(json.dumps(receipt, indent=2) + '\n')
print(f"Verified main debug APK: versionCode={receipt['versionCode']}; SHA-256={digest}")
print(f"Verified signer certificate SHA-256={receipt['certificate_sha256']}; tracked_worktree_dirty={receipt['tracked_worktree_dirty']}")
