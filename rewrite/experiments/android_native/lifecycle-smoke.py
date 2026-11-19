"""Fixed CI-only lifecycle driver. Never connects to a phone or accepts a PID argument."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET

PACKAGE = 'com.thekain.runtime.lab'
RUNNER = PACKAGE+'.test/androidx.test.runner.AndroidJUnitRunner'


def adb(*args, timeout=30):
    assert os.environ.get('ANDROID_SERIAL') == 'emulator-5554', 'CI emulator only'
    return subprocess.check_output(['adb', '-s', 'emulator-5554', *args], timeout=timeout)


def own_read(path):
    assert path in ('files/runtime-qa-state.json',), 'Fixed owned journal only'
    return adb('exec-out', 'run-as', PACKAGE, 'cat', path)


def proc_identity(pid):
    assert type(pid) is int and 1 < pid < 2**22
    try:
        raw = adb('exec-out', 'run-as', PACKAGE, 'cat', f'/proc/{pid}/stat').decode()
    except subprocess.CalledProcessError:
        raw = ''
    if ')' not in raw:
        path=f'/proc/{pid}'
        present = subprocess.run(['adb', '-s', 'emulator-5554', 'shell', 'run-as', PACKAGE,
            'ls', '-d', path], timeout=10, capture_output=True)
        if present.returncode == 0 and present.stdout.strip() == path.encode():
            return ('?', None) # Transient/denied stat read of a PID that still exists.
        if present.returncode == 1 and path.encode() in present.stderr and b'No such file or directory' in present.stderr:
            return None
        raise AssertionError('Cannot establish process existence: '+present.stderr.decode(errors='replace')[:200])
    fields = raw[raw.rindex(')')+2:].split()
    assert len(fields) >= 20, 'Incomplete kernel process identity'
    return fields[0], int(fields[19])


def hierarchy(evidence, label):
    adb('shell', 'uiautomator', 'dump', '/sdcard/thekain-qa-window.xml', timeout=15)
    raw = adb('exec-out', 'cat', '/sdcard/thekain-qa-window.xml')
    assert len(raw) <= 1024*1024, 'UI tree limit'
    (evidence/(label+'.xml')).write_bytes(raw)
    return ET.fromstring(raw)


def window_focused(focus):
    # A listed window or focused app alone is not evidence of input focus.
    return all(any(field in line and PACKAGE+'/' in line for line in focus.splitlines())
        for field in ('mCurrentFocus=', 'mFocusedApp='))


def tap(evidence, resource, scroll=False, text=None):
    until=time.monotonic()+20
    while time.monotonic()<until:
        # API29+ prints global focus in DisplayContent, omitted by the 'windows' subcommand.
        focus = adb('shell', 'dumpsys', 'window').decode()
        (evidence/'owner-window.txt').write_text(focus)
        if not window_focused(focus):
            time.sleep(.2)
            continue
        tree = hierarchy(evidence, 'owner-ui')
        for node in tree.iter('node'):
            if node.get('resource-id') != resource or node.get('enabled') != 'true':
                continue
            if text is not None and node.get('text', '').casefold() != text.casefold():
                continue
            bounds = re.fullmatch(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', node.get('bounds', ''))
            if not bounds:
                continue
            left, top, right, bottom = map(int, bounds.groups())
            if not (0 <= left < right <= 10000 and 0 <= top < bottom <= 10000):
                continue
            adb('shell', 'input', 'tap', str((left+right)//2), str((top+bottom)//2))
            return
        if scroll:
            # Choose coordinates only from the actual bounded ScrollView geometry.
            nodes = [n for n in tree.iter('node') if n.get('class') == 'android.widget.ScrollView']
            assert len(nodes) == 1, 'QA scroll container missing'
            values = re.fullmatch(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', nodes[0].get('bounds', ''))
            assert values, 'QA scroll bounds missing'
            left, top, right, bottom = map(int, values.groups())
            assert 0 <= left < right <= 10000 and 0 <= top < bottom <= 10000
            adb('shell', 'input', 'swipe', str((left+right)//2), str(top+(bottom-top)*4//5),
                str((left+right)//2), str(top+(bottom-top)//5), '250')
        else:
            time.sleep(.2)
    raise AssertionError('Fixed QA control unavailable: '+resource)


def dead(identity, start):
    return identity is None or identity[0] == 'Z' or (identity[1] is not None and identity[1] != start)


def run(evidence, delivery):
    evidence.mkdir(parents=True, exist_ok=True)
    adb('install', '-r', '-t', str(delivery/'probe.apk'))
    probe = adb('shell', 'am', 'instrument', '-w', 'com.thekain.runtime.probe/.BoundaryProbe').decode()
    (evidence/'boundary-probe.txt').write_text(probe)
    for field in ('different_uid=true', 'service_non_exported=true', 'external_bind_denied=true', 'boundary_probe=PASS'):
        assert field in probe, 'Independent UID boundary probe failed: '+field
    assert 'INSTRUMENTATION_CODE: -1' in probe
    # A normal launcher process, not instrumentation: test-runner force-stop must not mask Binder death.
    adb('shell', 'input', 'keyevent', '224');adb('shell', 'wm', 'dismiss-keyguard')
    adb('shell', 'am', 'start', '-W', '-n', PACKAGE+'/.LabQaActivity')
    tap(evidence, PACKAGE+':id/lab_hang')
    until = time.monotonic()+20
    pending = None
    while time.monotonic() < until:
        raw = own_read('files/runtime-qa-state.json');assert len(raw) <= 256*1024
        state = json.loads(raw);pending = state.get('pending')
        if pending and pending.get('worker_pid', 0) > 0:
            break
        time.sleep(.1)
    assert pending and pending.get('worker_pid', 0) > 0, 'Python-started journal not ready'
    assert pending['source_commit'] == json.loads((delivery/'build-report.json').read_text())['repository_commit']
    marker = {key:pending[key] for key in ('id','parent_pid','parent_start_ticks','worker_pid','worker_start_ticks')}
    marker['uid'] = int(adb('shell', 'run-as', PACKAGE, 'id', '-u').decode().strip())
    for key in ('parent_pid', 'parent_start_ticks', 'worker_pid', 'worker_start_ticks', 'uid'):
        assert type(marker[key]) is int and marker[key] > 0, 'Invalid marker integer'
    parent, worker = marker['parent_pid'], marker['worker_pid']
    assert parent != worker and re.fullmatch('[0-9a-f-]{36}', marker['id'])
    assert not dead(proc_identity(parent), marker['parent_start_ticks'])
    assert not dead(proc_identity(worker), marker['worker_start_ticks'])
    cmdline = adb('exec-out', 'run-as', PACKAGE, 'cat', f'/proc/{parent}/cmdline').split(b'\0')[0]
    assert cmdline == PACKAGE.encode(), 'Refuse non-owner operation'
    adb('shell', 'run-as', PACKAGE, 'mkdir', '-p', 'files/qa-evidence')
    subprocess.run(['adb','-s','emulator-5554','shell','run-as',PACKAGE,'tee','files/qa-evidence/owner-marker.json'],
        input=json.dumps(marker).encode(), stdout=subprocess.PIPE, check=True, timeout=10)
    tap(evidence, PACKAGE+':id/lab_kill_owner', scroll=True)
    (evidence/'owner-before-kill.png').write_bytes(adb('exec-out','screencap','-p'))
    # Same guarded self-kill + confirmation as the SDK-free phone app. SELinux is unchanged.
    tap(evidence, 'android:id/button1', text='Завершить QA')
    until = time.monotonic()+5
    while time.monotonic() < until:
        if dead(proc_identity(parent),marker['parent_start_ticks']) and dead(proc_identity(worker),marker['worker_start_ticks']):
            break
        time.sleep(.05)
    else:
        raise AssertionError('Owner/worker survived confirmed owner self-kill')
    (evidence/'owner-death.json').write_text(json.dumps({**marker, 'worker_death_before_relaunch':True,
        'owner_death_before_relaunch':True,'used_force_stop':False,'used_shell_sigkill':False,
        'normal_activity_owner':True})+'\n')
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
    try:
        run(args.evidence, args.delivery)
    except Exception:
        # Own CI emulator only; no PEM/raw journal/remote URL dump. Preserve exact failure state.
        for command,name in ((('dumpsys','window'),'failure-window.txt'),
                (('dumpsys','activity','activities'),'failure-activity.txt')):
            try:
                raw=adb('shell',*command).decode(errors='replace');(args.evidence/name).write_text(raw)
                print('\n'.join(line for line in raw.splitlines() if any(term in line for term in
                    ('mCurrentFocus','mFocusedApp','mResumedActivity','mTopResumedActivity')))[-4000:])
            except Exception as capture:
                print('QA diagnostic capture: '+type(capture).__name__)
        try:
            hierarchy(args.evidence,'failure-ui')
            (args.evidence/'failure-screen.png').write_bytes(adb('exec-out','screencap','-p'))
            print((args.evidence/'failure-ui.xml').read_text()[-8000:])
        except Exception as capture:
            print('QA UI capture: '+type(capture).__name__)
        raise
