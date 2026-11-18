import errno
import platform
from pathlib import Path
import struct
import subprocess
import sys
import tempfile
import unittest

from rewrite.experiments.android_native.build import deny_build_network,elf_report


class NativeIntakeTests(unittest.TestCase):
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
