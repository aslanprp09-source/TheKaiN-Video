"""Source-built, fixed-input Linux/x86_64 JNI lab. No product or signing integration."""
import argparse
import ctypes
import errno
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import shutil
import stat
import struct
import subprocess
import sys
import tarfile
import urllib.request
import zipfile
import posixpath

ROOT = Path(__file__).resolve().parent
PROFILE = json.loads((ROOT / 'profile.json').read_text())
DISABLED = ('_bz2', '_ctypes', '_lzma', '_sqlite3', '_zstd', '_tkinter',
            'readline', '_curses', '_curses_panel', '_gdbm', '_dbm', '_uuid', '_decimal')


def require(value, code):
    if not value: raise RuntimeError(code)


def digest(path):
    with Path(path).open('rb') as stream: return hashlib.file_digest(stream, 'sha256').hexdigest()


def deny_build_network():
    """Inherited seccomp filter: no socket/connect/socketpair in build subprocesses.

    Applied only in the single-threaded builder's child, after fixed-input intake.
    No fallback to an unfenced build. This is not a general filesystem sandbox.
    """
    if platform.system() != 'Linux' or platform.machine() != 'x86_64':
        raise RuntimeError('UNSUPPORTED_BUILD_HOST')
    class Filter(ctypes.Structure):
        _fields_ = [('code', ctypes.c_ushort), ('jt', ctypes.c_ubyte),
                    ('jf', ctypes.c_ubyte), ('k', ctypes.c_uint)]
    class Program(ctypes.Structure):
        _fields_ = [('len', ctypes.c_ushort), ('filter', ctypes.POINTER(Filter))]
    # arch check, then deny the x86_64 socket/connect/socketpair syscalls.
    ops = [(0x20,0,0,4), (0x15,1,0,0xc000003e), (0x06,0,0,0x80000000),
           (0x20,0,0,0), (0x35,4,0,0x40000000), (0x15,3,0,41), (0x15,2,0,42), (0x15,1,0,53),
           (0x06,0,0,0x7fff0000), (0x06,0,0,0x00050000 | errno.EPERM)]
    filters = (Filter * len(ops))(*(Filter(*op) for op in ops))
    program = Program(len(ops), filters)
    libc = ctypes.CDLL(None, use_errno=True)
    if libc.prctl(38, 1, 0, 0, 0) or libc.prctl(22, 2, ctypes.byref(program), 0, 0):
        raise RuntimeError('BUILD_NETWORK_FENCE_UNAVAILABLE')


def clean_env(work, ndk_bin=None):
    env = {'PATH': '/usr/bin:/bin', 'LANG': 'C.UTF-8', 'LC_ALL': 'C.UTF-8',
           'TMPDIR': str(work / 'tmp'), 'GIT_CONFIG_NOSYSTEM': '1',
           'GIT_CONFIG_GLOBAL': '/dev/null', 'GIT_TERMINAL_PROMPT': '0',
           'PYTHONHASHSEED': '0', 'SOURCE_DATE_EPOCH': '1790790909'}
    if ndk_bin: env['PATH'] = str(ndk_bin) + ':' + env['PATH']
    return env


def run(args, cwd, env, log, fenced=True):
    with Path(log).open('ab') as output:
        subprocess.run([str(a) for a in args], cwd=cwd, env=env,
            stdout=output, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL,
            check=True, preexec_fn=deny_build_network if fenced else None)


def fetch_source(name, work, source_cache=None):
    pin = PROFILE['sources'][name]
    target = (Path(source_cache) / name) if source_cache else work / 'sources' / name
    env = clean_env(work)
    # Public, fixed HTTPS repositories only; never forward owner/signing credentials.
    for key in ('HTTPS_PROXY', 'HTTP_PROXY', 'ALL_PROXY', 'NO_PROXY', 'SSL_CERT_FILE', 'SSL_CERT_DIR'):
        if key in os.environ: env[key] = os.environ[key]
    if not target.exists():
        target.mkdir(parents=True)
        for args in (['git','init','-q'], ['git','fetch','--depth','1',pin['url'],pin['commit']],
                     ['git','checkout','--detach','FETCH_HEAD']):
            run(args,target,env,work / 'intake.log',False)
    head = subprocess.check_output(['git','rev-parse','HEAD'],cwd=target,env=env,text=True).strip()
    require(head == pin['commit'], 'SOURCE_COMMIT_MISMATCH')
    run(['git','diff','--exit-code','HEAD'],target,env,work / 'intake.log',False)
    run(['git','fsck','--strict'],target,env,work / 'intake.log',False)
    # Build only tracked Git objects, never ignored/generated files from a cache.
    archive=work/(name+'-source.tar')
    run(['git','archive','--format=tar','-o',archive,pin['commit']],target,env,work/'intake.log',False)
    source=work/('source-'+name);source.mkdir()
    with tarfile.open(archive) as stream: stream.extractall(source,filter='data')
    return source


def acquire_ndk(work, cached_archive=None):
    pin = PROFILE['ndk']; archive = Path(cached_archive) if cached_archive else work / 'ndk.zip'
    if not archive.exists():
        # The URL is code-pinned, not a user/profile override. No credentials.
        with urllib.request.urlopen(pin['url'], timeout=90) as response, archive.open('xb') as output:
            count = 0
            while chunk := response.read(1024 * 1024):
                count += len(chunk); require(count <= pin['bytes'], 'NDK_SIZE_LIMIT')
                output.write(chunk)
    require(archive.stat().st_size == pin['bytes'] and digest(archive) == pin['sha256'], 'NDK_DIGEST_MISMATCH')
    with zipfile.ZipFile(archive) as z:
        entries = z.infolist()
        require(len(entries) < 100000 and sum(e.file_size for e in entries) < 6 * 1024**3, 'NDK_ARCHIVE_LIMIT')
        for e in entries:
            path = posixpath.normpath(e.filename)
            require(not e.filename.startswith('/') and '\\' not in e.filename and
                (path == 'android-ndk-r27d' or path.startswith('android-ndk-r27d/')), 'NDK_ARCHIVE_PATH')
            if stat.S_ISLNK(e.external_attr >> 16):
                require(e.file_size < 1024, 'NDK_SYMLINK_LIMIT')
                link = z.read(e).decode()
                resolved = posixpath.normpath(posixpath.join(posixpath.dirname(path), link))
                require(not link.startswith('/') and resolved.startswith('android-ndk-r27d/'), 'NDK_SYMLINK_ESCAPE')
    subprocess.run(['unzip','-q',str(archive),'-d',str(work)],check=True)
    ndk = work / 'android-ndk-r27d'
    require('Pkg.Revision = ' + pin['version'] in (ndk / 'source.properties').read_text(), 'NDK_VERSION_MISMATCH')
    return ndk


def elf_report(path, machine, readelf):
    raw = path.read_bytes()
    require(raw[:6] == b'\x7fELF\x02\x01' and len(raw) >= 64, 'NOT_ELF64_LE')
    require(struct.unpack_from('<HH',raw,16) == (3,machine), 'ELF_TYPE_OR_ABI_MISMATCH')
    phoff = struct.unpack_from('<Q',raw,32)[0]
    phsize, phcount = struct.unpack_from('<HH',raw,54)
    require(phsize == 56 and phcount < 1000 and phoff + phsize * phcount <= len(raw), 'ELF_PROGRAM_HEADERS')
    loads = []
    for i in range(phcount):
        ptype,flags,offset,vaddr,_,size,_,align = struct.unpack_from('<IIQQQQQQ',raw,phoff+i*phsize)
        if ptype == 1:
            require(align >= 16384 and align & (align-1) == 0 and offset % 16384 == vaddr % 16384
                    and offset + size <= len(raw), 'ELF_16K_ALIGNMENT_FAILED')
            loads.append({'flags':flags,'alignment':align})
    require(loads, 'ELF_LOAD_SEGMENTS_MISSING')
    text = subprocess.check_output([str(readelf),'-d',str(path)],text=True)
    require(not re.search(r'\((RPATH|RUNPATH)\)',text), 'UNEXPECTED_NATIVE_SEARCH_PATH')
    needed = re.findall(r'\(NEEDED\).*\[([^]]+)\]',text)
    require(set(needed) <= {'libc.so','libm.so','libdl.so','libz.so','liblog.so','libandroid.so','libpython3.14.so'},
            'UNREVIEWED_NATIVE_DEPENDENCY')
    return {'sha256':digest(path),'bytes':len(raw),'machine':machine,'loads':loads,'needed':needed}


def build(work, abis, source_cache=None, ndk_archive=None):
    require(PROFILE['mimalloc'] is False, 'UNREVIEWED_ALLOCATOR')
    work.mkdir(parents=True,exist_ok=False); (work/'tmp').mkdir()
    sources = {n:fetch_source(n,work,source_cache) for n in PROFILE['sources']}
    ndk = acquire_ndk(work, ndk_archive)
    wheel = work/'yt_dlp.whl'
    with urllib.request.urlopen('https://files.pythonhosted.org/packages/7f/3d/16ea7d5638cf585991f2c5705e8083688dde633d32d0871f2404e0f4d827/yt_dlp-2026.10.26-py3-none-any.whl',timeout=60) as r:
        raw=r.read(4*1024*1024)
    require(hashlib.sha256(raw).hexdigest()=='a05c5b5d8562f7e8a62f5334348b406af4930d56d3a65f8021d14af0e22339ae','YTDLP_DIGEST_MISMATCH');wheel.write_bytes(raw)
    tools = ndk/'toolchains/llvm/prebuilt/linux-x86_64/bin'
    env = clean_env(work)
    # Prove the inherited fence before executing any configure/make subprocess.
    probe = 'import socket,errno\ntry: socket.socket()\nexcept OSError as e: assert e.errno==errno.EPERM\nelse: raise RuntimeError("NETWORK_FENCE_FAILED")'
    run([sys.executable,'-I','-c',probe],work,env,work/'fence.log')
    host = work/'host';host.mkdir()
    run([sources['cpython']/'configure','--without-ensurepip','--disable-test-modules'],host,env,work/'host.log')
    run(['make','-j4'],host,env,work/'host.log')
    package = work/'package'; (package/'assets').mkdir(parents=True)
    report = {'schema':1,'scope':'minimal-source-built-JNI-lab','profile':PROFILE,
              'source_archives':{n: digest(work/(n+'-source.tar')) for n in sources},
              'yt_dlp_wheel_sha256':digest(wheel),
              'network_fence':'seccomp socket/connect/socketpair denied for configure/make children',
              'android_execution':'NOT_RUN','production_ready':False,'automatic_publication':False,'abis':{}}
    for abi in abis:
        pin = PROFILE['abis'][abi]
        ssl = work/('openssl-'+abi);ssl.mkdir();sslprefix=ssl/'prefix'
        target_env = clean_env(work,tools)
        target_env.update(ANDROID_NDK_ROOT=str(ndk),CC=pin['triplet']+'26-clang',AR='llvm-ar',RANLIB='llvm-ranlib',
                          CFLAGS='-O2 -fPIC',LDFLAGS='-Wl,--no-rosegment -Wl,-z,max-page-size=16384')
        run(['perl',sources['openssl']/'Configure',pin['openssl'],'-D__ANDROID_API__=26',
             'no-shared','no-tests','no-module','no-apps','no-asm','--prefix='+str(sslprefix)],ssl,target_env,work/(abi+'-openssl.log'))
        run(['make','-j4'],ssl,target_env,work/(abi+'-openssl.log'))
        run(['make','install_sw'],ssl,target_env,work/(abi+'-openssl.log'))
        target = work/('python-'+abi);target.mkdir();prefix=target/'prefix'
        (target/'Modules').mkdir()
        (target/'Modules/Setup.local').write_text('*disabled*\n'+'\n'.join(DISABLED)+'\n')
        target_env.update(CFLAGS='-O2 -fPIC -D__BIONIC_NO_PAGE_SIZE_MACRO',
            LDFLAGS='-Wl,--no-rosegment -Wl,-z,max-page-size=16384 -Wl,--no-undefined -Wl,--exclude-libs,ALL -lm -ldl',
            PKG_CONFIG_LIBDIR=str(sslprefix/'lib/pkgconfig'))
        run([sources['cpython']/'configure','--host='+pin['triplet'],'--build=x86_64-pc-linux-gnu',
             '--with-build-python='+str(host/'python'),'--prefix='+str(prefix),'--enable-shared',
             '--without-static-libpython','--without-ensurepip','--disable-test-modules','--without-mimalloc',
             '--with-openssl='+str(sslprefix),'--with-openssl-rpath=no'],target,target_env,work/(abi+'-python.log'))
        run(['make','-j4'],target,target_env,work/(abi+'-python.log'))
        run(['make','install','COMPILEALL_OPTS=-j1'],target,target_env,work/(abi+'-python.log'))
        lib = prefix/'lib/python3.14'; dyn=lib/'lib-dynload'
        require(any(dyn.glob('_ssl.*.so')) and any(dyn.glob('_hashlib.*.so')), 'TLS_MODULES_MISSING')
        require(not any(p.name.startswith(n+'.') for n in DISABLED for p in dyn.glob('*.so')), 'DISABLED_MODULE_PACKAGED')
        dest = package/'assets/runtime'/abi
        shutil.copytree(prefix/'lib',dest/'lib',ignore=shutil.ignore_patterns('__pycache__','*.pyc','test','tests',
            'idlelib','tkinter','turtledemo','ensurepip','site-packages','pkgconfig','*.a','*.so'))
        # All executable code stays in Android's installed native library directory.
        (dest/'lib/python3.14/lib-dynload').mkdir(parents=True,exist_ok=True)
        native = package/'jniLibs'/abi;native.mkdir(parents=True)
        observed = {}
        for path in [prefix/'lib/libpython3.14.so',*sorted(dyn.glob('*.so'))]:
            observed[path.name] = elf_report(path,pin['machine'],tools/'llvm-readelf')
            packaged = path.name if path.name=='libpython3.14.so' else 'libpyext_'+path.name
            observed[path.name]['packaged_name'] = packaged
            shutil.copy2(path,native/packaged)
        # Install only the already hash-locked pure-Python wheel, never native extras.
        site = dest/'lib/python3.14/site-packages';site.mkdir(exist_ok=True)
        with zipfile.ZipFile(wheel) as z:
            for e in z.infolist():
                require(not e.filename.startswith('/') and '..' not in Path(e.filename).parts and
                    not e.filename.endswith(('.so','.dll','.pyd','.exe')), 'UNEXPECTED_WHEEL_PAYLOAD')
            z.extractall(site)
        for name in ('spike.py','worker.py'):
            shutil.copy2(ROOT.parent/'android_ytdlp'/name,site/name)
        shutil.copy2(ROOT/'thekain_lab.py',site/'thekain_lab.py')
        shutil.copy2(ROOT/'native_import.py',site/'native_import.py')
        shutil.copy2(ROOT/'bounded_transport.py',site/'bounded_transport.py')
        shutil.copy2(ROOT/'source_analysis.py',site/'source_analysis.py')
        # Own JNI C only; no imported bootstrap that changes ART signal handling.
        run([tools/(pin['triplet']+'26-clang'),'-shared','-fPIC','-O2','-I'+str(prefix/'include/python3.14'),
             ROOT/'jni.c','-L'+str(prefix/'lib'),'-lpython3.14','-llog','-lm','-ldl',
             '-Wl,--no-rosegment','-Wl,-z,max-page-size=16384','-Wl,--no-undefined','-o',native/'libthekain_lab.so'],work,target_env,work/(abi+'-jni.log'))
        observed['libthekain_lab.so'] = elf_report(native/'libthekain_lab.so',pin['machine'],tools/'llvm-readelf')
        observed['libthekain_lab.so']['packaged_name'] = 'libthekain_lab.so'
        # Own source only: libc-only trusted JNI supervisor and standalone exec child (no ART).
        for source,name,extra in (
                ('supervisor_jni.c','libthekain_supervisor.so',['-shared','-fPIC']),
                ('lease_jni.c','libthekain_lease.so',['-shared','-fPIC']),
                ('child_main.c','libthekain_child.so',['-fPIE','-pie','-I'+str(prefix/'include/python3.14'),'-L'+str(prefix/'lib'),'-lpython3.14','-I'+str(sslprefix/'include'),'-L'+str(sslprefix/'lib'),'-lssl','-lcrypto','-lm','-ldl'])):
            run([tools/(pin['triplet']+'26-clang'),'-O2',ROOT/source,*extra,
                 '-Wl,--no-rosegment','-Wl,-z,max-page-size=16384','-Wl,--no-undefined',
                 '-o',native/name],work,target_env,work/(abi+'-'+source+'.log'))
            observed[name]=elf_report(native/name,pin['machine'],tools/'llvm-readelf')
            observed[name]['packaged_name']=name
        files = {str(p.relative_to(dest)):{'bytes':p.stat().st_size,'sha256':digest(p)}
                 for p in sorted(dest.rglob('*')) if p.is_file()}
        (package/'assets'/('inventory-'+abi+'.json')).write_text(json.dumps(files,sort_keys=True)+'\n')
        report['abis'][abi]={'elf':observed,'asset_files':len(files),'asset_bytes':sum(v['bytes'] for v in files.values())}
    legal = package/'assets/legal';legal.mkdir()
    # Preserve root and bundled-library notices; no claim of final distribution signoff.
    notices = {}
    for name,source in sources.items():
        for path in sorted(source.rglob('*')):
            if '.git' in path.parts or not path.is_file():continue
            if re.match(r'^(LICENSE|COPYING|COPYRIGHT|NOTICE)([-_.]|$)',path.name,re.I):
                relative=str(path.relative_to(source)); target=legal/(name+'-'+relative.replace('/','_'))
                shutil.copy2(path,target);notices[target.name]={'source':relative,'sha256':digest(path)}
            # CPython's generated HACL sources carry their MIT notice in the C header.
            if name=='cpython' and 'Modules/_hacl/' in str(path.relative_to(source)) and path.suffix in ('.c','.h'):
                raw=path.read_text(errors='strict')
                header=re.match(r'\s*(/\*.*?\*/)',raw,re.S)
                if header and 'Permission is hereby granted' in header[1]:
                    relative=str(path.relative_to(source));target=legal/('cpython-'+relative.replace('/','_')+'.notice.txt')
                    target.write_text(header[1]+'\n');notices[target.name]={'source':relative,'source_sha256':digest(path),'sha256':digest(target)}
    shutil.copy2(ndk/'NOTICE.toolchain',legal/'NDK-NOTICE.toolchain')
    notices['NDK-NOTICE.toolchain']={'source':'NOTICE.toolchain','sha256':digest(legal/'NDK-NOTICE.toolchain')}
    require({'cpython-LICENSE','openssl-LICENSE.txt','NDK-NOTICE.toolchain'} <= set(notices), 'LICENSE_NOTICES_MISSING')
    require(any('hacl' in name.lower() for name in notices), 'HACL_NOTICE_MISSING')
    report['license_notices']=notices;report['final_distribution_review']='NOT_RUN'
    (package/'build-report.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps({'source_build':'PASS','abis':abis,'android_execution':'NOT_RUN','report':str(package/'build-report.json')}))


if __name__ == '__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--work',type=Path,required=True)
    parser.add_argument('--abis',nargs='+',choices=list(PROFILE['abis']),default=list(PROFILE['abis']))
    parser.add_argument('--source-cache',type=Path)
    parser.add_argument('--ndk-archive',type=Path)
    args=parser.parse_args()
    build(args.work.resolve(),args.abis,args.source_cache,args.ndk_archive)
