import errno
import hashlib
import io
import json
import os
import platform
from pathlib import Path
import struct
import subprocess
import sys
import tempfile
import unittest
import zipfile

from rewrite.experiments.android_native.build import deny_build_network,elf_report
from rewrite.experiments.android_native.delivery import PROFILE,verify_runtime_assets


class NativeIntakeTests(unittest.TestCase):
    @unittest.skipUnless(sys.platform=='linux','Hosted Android harness runs on Linux')
    def test_meminfo_sampler_survives_absent_worker_between_operations(self):
        with tempfile.TemporaryDirectory() as temp:
            folder=Path(temp)
            adb=folder/'adb'
            adb.write_text('''#!/usr/bin/env bash
set -eu
if [ "$4" = pidof ]; then
  n=0; if [ -f "$MEMINFO_TEST_STATE" ]; then n=$(cat "$MEMINFO_TEST_STATE"); fi
  n=$((n+1)); echo "$n" > "$MEMINFO_TEST_STATE"
  if [ "$n" -lt 3 ]; then exit 1; fi
  echo 4321
else
  echo 'MEMINFO in pid 4321 [own test fixture, not a device measurement]'
fi
''')
            adb.chmod(0o755)
            env={**os.environ,'PATH':str(folder)+os.pathsep+os.environ['PATH'],
                 'ANDROID_SERIAL':'emulator-test','MEMINFO_TEST_STATE':str(folder/'state')}
            with subprocess.Popen(['sleep','2']) as driver:
                with subprocess.Popen(['bash',str(Path(__file__).with_name('sample-meminfo.sh')),
                                       str(driver.pid),str(folder)],env=env,stdout=subprocess.PIPE,
                                      stderr=subprocess.PIPE,text=True) as sampler:
                    driver.wait(timeout=3)
                    stdout,stderr=sampler.communicate(timeout=3)
                    self.assertEqual(sampler.returncode,0,stderr)
            snapshots=list(folder.glob('meminfo-*.txt'))
            self.assertEqual(len(snapshots),1)
            self.assertIn('MEMINFO in pid 4321',snapshots[0].read_text())
            self.assertGreaterEqual(int((folder/'state').read_text()),3)

    @unittest.skipUnless(sys.platform=='linux' and platform.machine()=='x86_64','Linux/x86_64 kernel reference test')
    def test_native_kernel_blocks_sockets_and_rejects_allocation_above_address_space_limit(self):
        source=r"""
#include "runtime_kernel.h"
#include <assert.h>
#include <sys/mman.h>
int main(void) {
    RuntimeKernelLimits limits;
    assert(configure_runtime_limits(64ULL*1024*1024,5,&limits)==0);
    assert(limits.address==limits.before+64ULL*1024*1024 && limits.socket_denied);
    for(int domain=0;domain<3;domain++) {
        int families[]={AF_INET,AF_INET6,AF_UNIX};
        assert(socket(families[domain],SOCK_STREAM,0)==-1 && errno==EPERM);
    }
    void *p=mmap(NULL,128UL*1024*1024,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
    assert(p==MAP_FAILED && errno==ENOMEM);
    puts("KERNEL_BUDGET_PASS");
}
"""
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);(root/'test.c').write_text(source)
            subprocess.run(['gcc','-std=gnu11','-Wall','-Wextra','-Werror','-I',str(Path(__file__).parent),
                str(root/'test.c'),'-o',str(root/'probe')],check=True,capture_output=True,timeout=30)
            result=subprocess.run([str(root/'probe')],capture_output=True,text=True,timeout=10)
            self.assertEqual(result.returncode,0,result.stderr)
            self.assertEqual(result.stdout.strip(),'KERNEL_BUDGET_PASS')

    def test_apk_missing_tampered_or_extra_python_assets_are_rejected_before_device(self):
        payload=b'# own Python package fixture\n';name='lib/python3.14/__phello__/__init__.py'
        raw=json.dumps({name:{'bytes':len(payload),'sha256':hashlib.sha256(payload).hexdigest()}}).encode()
        report={'asset_inventory_sha256':{abi:hashlib.sha256(raw).hexdigest() for abi in PROFILE['abis']},
                'abis':{abi:{'asset_files':1,'asset_bytes':len(payload)} for abi in PROFILE['abis']}}
        for variant in ('valid','missing','changed','extra'):
            with self.subTest(variant=variant):
                stream=io.BytesIO()
                with zipfile.ZipFile(stream,'w') as z:
                    for abi in PROFILE['abis']:
                        z.writestr('assets/inventory-'+abi+'.json',raw)
                        if variant!='missing':z.writestr(f'assets/runtime/{abi}/{name}',payload if variant!='changed' else b'tampered')
                    if variant=='extra':z.writestr('assets/runtime/x86_64/unlisted.py',b'unlisted')
                with zipfile.ZipFile(stream) as z:
                    if variant=='valid':verify_runtime_assets(z,report)
                    else:
                        with self.assertRaises(RuntimeError):verify_runtime_assets(z,report)

    @unittest.skipUnless(sys.platform=='linux' and platform.machine()=='x86_64','Linux/x86_64 source-build fence only')
    def test_build_child_cannot_open_inet_or_unix_sockets(self):
        code=('import socket,errno\nfor domain in (socket.AF_INET,socket.AF_INET6,socket.AF_UNIX):\n'
              ' try: socket.socket(domain)\n except OSError as e: assert e.errno==errno.EPERM\n'
              ' else: raise RuntimeError("NETWORK_NOT_BLOCKED")\nprint("FENCED")')
        result=subprocess.run([sys.executable,'-I','-c',code],preexec_fn=deny_build_network,
                              capture_output=True,text=True,timeout=10)
        self.assertEqual(result.returncode,0,result.stderr)
        self.assertEqual(result.stdout.strip(),'FENCED')

    @unittest.skipUnless(sys.platform=='linux' and platform.machine()=='x86_64','Linux/x86_64 source-build fence only')
    def test_fence_does_not_disable_ordinary_compiler_child_execution(self):
        result=subprocess.run([sys.executable,'-I','-c','print(6*7)'],preexec_fn=deny_build_network,
                              capture_output=True,text=True,timeout=10)
        self.assertEqual((result.returncode,result.stdout.strip()),(0,'42'))

    def test_foreign_abi_and_4k_elf_fail_before_tool_invocation(self):
        raw=bytearray(120);raw[:6]=b'\x7fELF\x02\x01'
        struct.pack_into('<HH',raw,16,3,62)
        struct.pack_into('<Q',raw,32,64);struct.pack_into('<HH',raw,54,56,1)
        struct.pack_into('<IIQQQQQQ',raw,64,1,5,0,0,0,120,120,4096)
        with tempfile.TemporaryDirectory() as temp:
            path=Path(temp)/'fixture.so';path.write_bytes(raw)
            with self.assertRaisesRegex(RuntimeError,'ELF_16K_ALIGNMENT_FAILED'):elf_report(path,62,'/must-not-run')
            with self.assertRaisesRegex(RuntimeError,'ELF_TYPE_OR_ABI_MISMATCH'):elf_report(path,183,'/must-not-run')


if __name__=='__main__':unittest.main()
