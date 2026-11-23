"""CI-only API36 image readiness. Never starts/warmups an app or changes its limits."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import time


def adb(*args, timeout=15):
    assert os.environ.get('ANDROID_SERIAL') == 'emulator-5554', 'CI emulator only'
    return subprocess.check_output(['adb', '-s', 'emulator-5554', *args], timeout=timeout)


def wait_quiet(read_pressure, now=time.monotonic, pause=time.sleep):
    started=now();quiet_since=started;previous=None;changes=0
    while now()-started<120:
        current=read_pressure()
        assert len(current)<=512*1024, 'Memory-pressure evidence bounds'
        if current!=previous:
            previous=current;quiet_since=now();changes+=1
        if now()-quiet_since>=5:
            return dict(wait_ms=round((now()-started)*1000),quiet_ms=round((now()-quiet_since)*1000),pressure_changes=changes)
        pause(1)
    raise TimeoutError('CI_IMAGE_MEMORY_PRESSURE_DID_NOT_SETTLE')


def run(folder):
    folder.mkdir(parents=True,exist_ok=True)
    report=dict(status='FAIL',stage='IDENTITY',lab_launch_requested=False,python_warmup_requested=False,
        package_force_stopped=False,limits_changed=False)
    try:
        assert adb('shell','getprop','ro.build.version.sdk').strip()==b'36'
        assert adb('shell','getconf','PAGE_SIZE').strip()==b'16384'
        before=adb('shell','pidof','system_server').strip();assert before
        report['stage']='BROADCAST_IDLE'
        queues=adb('shell','am','wait-for-broadcast-idle',timeout=60)
        (folder/'image-broadcast-idle.txt').write_bytes(queues)
        assert b'all broadcast queues are idle' in queues.lower(), 'Image broadcast queues not confirmed idle'
        (folder/'image-memory-before.txt').write_bytes(adb('shell','cat','/proc/meminfo'))
        report['stage']='MEMORY_PRESSURE_QUIET'
        def pressure():
            raw=adb('logcat','-d','-v','threadtime','-s','lowmemorykiller:I','lmkd:I')
            (folder/'image-lmk-readiness.txt').write_bytes(raw)
            return raw
        report.update(wait_quiet(pressure))
        (folder/'image-memory-after.txt').write_bytes(adb('shell','cat','/proc/meminfo'))
        assert adb('shell','pidof','system_server').strip()==before, 'System server changed during readiness'
        report.update(status='PASS',stage='READY',broadcast_queues_idle=True)
        print('::notice::'+json.dumps(report))
    except Exception as error:
        report['failure_class']=type(error).__name__
        print('::error::CI image readiness failed before application tests: '+json.dumps(report))
        raise
    finally:
        (folder/'image-readiness.json').write_text(json.dumps(report,indent=2)+'\n')


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--evidence',type=Path,required=True);a=p.parse_args();run(a.evidence)
