"""Additional fixed CI-only abrupt owner-loss test. Shell observations never clear app uncertainty."""
import argparse
import importlib.util
import json
from pathlib import Path
import re
import time

spec=importlib.util.spec_from_file_location('owned_lifecycle',Path(__file__).with_name('lifecycle-smoke.py'))
driver=importlib.util.module_from_spec(spec);spec.loader.exec_module(driver)
PACKAGE=driver.PACKAGE

def run(folder):
    driver.adb('shell','input','keyevent','224');driver.adb('shell','wm','dismiss-keyguard')
    driver.adb('shell','am','start','-W','-n',PACKAGE+'/.IsolatedQaActivity')
    driver.tap(folder,PACKAGE+':id/uid_hang_button',scroll=True)
    until=time.monotonic()+25;lease=None;owner=None
    while time.monotonic()<until:
        try:
            raw=driver.adb('exec-out','run-as',PACKAGE,'cat','files/isolated-pending.json');assert len(raw)<=4096
            lease=json.loads(raw)
            if lease.get('schema')==2 and lease.get('phase')=='ARMED':
                owner=lease['owner_pid']
                tree=driver.hierarchy(folder,'guard-owner-python-started')
                status=' '.join(node.get('text','') for node in tree.iter('node') if node.get('resource-id')==PACKAGE+':id/uid_status')
                match=re.search(r'PID worker: ([0-9]+)',status)
                if match:
                    worker=int(match.group(1));assert worker>1 and worker!=owner
                    lease['observed_started_worker_pid']=worker
                    break
        except Exception: pass
        time.sleep(.1)
    assert lease and lease.get('phase')=='ARMED' and type(owner) is int and owner>1 and lease.get('observed_started_worker_pid',0)>1
    assert re.fullmatch('[0-9a-f-]{36}',lease['id'])
    cmd=driver.adb('exec-out','run-as',PACKAGE,'cat',f'/proc/{owner}/cmdline').split(b'\0')[0]
    assert cmd==PACKAGE.encode(),'Refuse non-owner kill'
    identity=driver.proc_identity(owner);assert identity and identity[1]==lease['owner_start'] and identity[0] not in ('Z','X','?')
    lease['kill_mechanism']='confirmed_guarded_owner_self_sigkill'
    (folder/'guard-owner-before-kill.json').write_text(json.dumps(lease,indent=2)+'\n')
    # The SDK-free launcher's confirmed self-kill. No shell signal, Service
    # unbind, cleanup or lease rewrite precedes this fault.
    driver.tap(folder,PACKAGE+':id/uid_kill_owner_button',scroll=True)
    (folder/'owner-before-selfkill.png').write_bytes(driver.adb('exec-out','screencap','-p'))
    driver.tap(folder,'android:id/button1',text='Завершить QA')
    until=time.monotonic()+8
    while time.monotonic()<until:
        if driver.dead(driver.proc_identity(owner),lease['owner_start']):break
        time.sleep(.1)
    else:raise AssertionError('Old owner death not observed')
    (folder/'guard-owner-after-kill.json').write_text(json.dumps(dict(owner_pid=owner,owner_start=lease['owner_start'],
        owner_death_before_instrumentation=True,kill_mechanism=lease['kill_mechanism'],used_shell_signal=False,used_force_stop=False))+'\n')
    text=driver.adb('shell','am','instrument','-w','-e','class','com.thekain.runtime.lab.OwnerRecoveryTest',driver.RUNNER,timeout=100).decode()
    (folder/'owner-guard-recovery-instrumentation.txt').write_text(text);print(text)
    assert re.search(r'^OK \(1 test\)\s*$',text,re.M) and not any(s in text for s in ('FAILURES!!!','shortMsg=','INSTRUMENTATION_FAILED'))
    (folder/'kernel-guard-recovery.jsonl').write_bytes(driver.adb('exec-out','run-as',PACKAGE,'cat','files/isolated-recovery-evidence.jsonl'))
    (folder/'recovery-operation-evidence.jsonl').write_bytes(driver.adb('exec-out','run-as',PACKAGE,'cat','files/isolated-evidence.jsonl'))
    print('::notice::'+json.dumps({'abrupt_normal_owner_kill':'PASS','explicit_kernel_guard_recovery':'PASS','fresh_owned_analysis':'PASS','os_reboot_required':False,'production_ready':False}))

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--evidence',type=Path,required=True);args=parser.parse_args();run(args.evidence)
