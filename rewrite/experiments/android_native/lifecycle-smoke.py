"""Fixed CI-only lifecycle driver. Never connects to a phone or accepts a PID argument."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import time

PACKAGE = 'com.thekain.runtime.lab'
RUNNER = PACKAGE+'.test/androidx.test.runner.AndroidJUnitRunner'


def adb(*args, timeout=30):
    assert os.environ.get('ANDROID_SERIAL') == 'emulator-5554', 'CI emulator only'
    return subprocess.check_output(['adb', '-s', 'emulator-5554', *args], timeout=timeout)


def own_read(path):
    assert path in ('files/qa-evidence/owner-marker.json',), 'Fixed owned marker only'
    return adb('exec-out', 'run-as', PACKAGE, 'cat', path)


def proc_identity(pid):
    assert type(pid) is int and 1 < pid < 2**22
    try:
        raw = adb('exec-out', 'run-as', PACKAGE, 'cat', f'/proc/{pid}/stat').decode()
    except subprocess.CalledProcessError:
        return None
    fields = raw[raw.rindex(')')+2:].split()
    return fields[0], int(fields[19])


def run(evidence, delivery):
    evidence.mkdir(parents=True, exist_ok=True)
    adb('install', '-r', '-t', str(delivery/'probe.apk'))
    probe = adb('shell', 'am', 'instrument', '-w', 'com.thekain.runtime.probe/.BoundaryProbe').decode()
    (evidence/'boundary-probe.txt').write_text(probe)
    for field in ('different_uid=true', 'service_non_exported=true', 'external_bind_denied=true', 'boundary_probe=PASS'):
        assert field in probe, 'Independent UID boundary probe failed: '+field
    assert 'INSTRUMENTATION_CODE: -1' in probe
    # Delete only our test marker, never the journal/assets, before explicit seed.
    adb('shell', 'run-as', PACKAGE, 'rm', '-f', 'files/qa-evidence/owner-marker.json')
    with (evidence/'owner-seed.txt').open('wb') as log:
        driver = subprocess.Popen(['adb', '-s', 'emulator-5554', 'shell', 'am', 'instrument', '-w', '-e',
            'class', PACKAGE+'.LabOwnerRestartTest#seed', RUNNER], stdout=log, stderr=subprocess.STDOUT)
        try:
            until = time.monotonic()+50
            marker = None
            while time.monotonic() < until:
                try:
                    raw = own_read('files/qa-evidence/owner-marker.json')
                    assert len(raw) <= 4096
                    marker = json.loads(raw)
                    break
                except (subprocess.CalledProcessError, json.JSONDecodeError):
                    if driver.poll() is not None:
                        raise AssertionError('Seed instrumentation exited before durable marker')
                    time.sleep(.2)
            assert marker is not None, 'Seed marker not ready'
            for key in ('parent_pid', 'parent_start_ticks', 'worker_pid', 'worker_start_ticks', 'uid'):
                assert type(marker[key]) is int and marker[key] > 0, 'Invalid marker integer'
            parent, worker = marker['parent_pid'], marker['worker_pid']
            assert parent != worker and re.fullmatch('[0-9a-f-]{36}', marker['id'])
            parent_identity, worker_identity = proc_identity(parent), proc_identity(worker)
            assert parent_identity and parent_identity[0] != 'Z' and parent_identity[1] == marker['parent_start_ticks'], 'Owner identity changed'
            assert worker_identity and worker_identity[0] != 'Z' and worker_identity[1] == marker['worker_start_ticks'], 'Worker identity changed'
            cmdline = adb('exec-out', 'run-as', PACKAGE, 'cat', f'/proc/{parent}/cmdline').split(b'\0')[0]
            assert cmdline == PACKAGE.encode(), 'Refuse non-owner kill'
            uid = int(adb('shell', 'run-as', PACKAGE, 'id', '-u').decode().strip())
            assert uid == marker['uid'], 'Owner UID mismatch'
            # SIGKILL only this package's validated main PID, not force-stop (which kills both PIDs).
            adb('shell', 'run-as', PACKAGE, 'kill', '-9', str(parent))
            until = time.monotonic()+5
            while time.monotonic() < until:
                current = proc_identity(worker)
                if current is None or current[0] == 'Z' or current[1] != marker['worker_start_ticks']:
                    break
                time.sleep(.05)
            else:
                raise AssertionError('Orphan worker survived owner Binder death')
            (evidence/'owner-death.json').write_text(json.dumps({**marker,
                'worker_death_before_relaunch': True, 'used_force_stop': False})+'\n')
            driver.wait(timeout=10)
        finally:
            if driver.poll() is None:
                driver.terminate()
                driver.wait(timeout=5)
    verify = adb('shell', 'am', 'instrument', '-w', '-e', 'class', PACKAGE+'.LabOwnerRestartTest#verify', RUNNER, timeout=90).decode()
    (evidence/'owner-verify.txt').write_text(verify)
    assert re.search(r'^OK \(1 test\)\s*$', verify, re.M), 'Owner restart verification failed'
    assert not any(s in verify for s in ('FAILURES!!!', 'shortMsg=', 'INSTRUMENTATION_FAILED'))
    # Only bounded, intentionally public QA evidence. Never export the whole private files tree/PEMs.
    archive = adb('exec-out', 'run-as', PACKAGE, 'tar', '-C', 'files', '-cf', '-', 'qa-evidence')
    assert len(archive) <= 16*1024*1024, 'QA evidence archive too large'
    (evidence/'qa-evidence.tar').write_bytes(archive)
    print('::notice::'+json.dumps({'background_lifecycle': 'PASS', 'external_uid_bind': 'DENIED',
        'owner_pid_kill': 'PASS', 'orphan_death_before_relaunch': True, 'arm64_execution': 'NOT_RUN',
        'production_ready': False}))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--evidence', type=Path, required=True)
    parser.add_argument('--delivery', type=Path, required=True)
    args = parser.parse_args()
    run(args.evidence, args.delivery)
