"""Same-run receipt for explicit internal main APK. Never signs, publishes or repins protected APK89."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import struct
import subprocess
import zipfile
if __package__:
    from .delivery import certificate,tools,verify_runtime_assets
    from .build import digest,require,PROFILE
else:
    from delivery import certificate,tools,verify_runtime_assets
    from build import digest,require,PROFILE

ROOT=Path(__file__).resolve().parents[2]/'android'


def verify(folder):
    folder=Path(folder);receipt=json.loads((folder/'main-report.json').read_text());native=json.loads((folder/'build-report.json').read_text())
    source=os.environ['GITHUB_SHA']
    require(receipt['source']==native['repository_commit']==source,'MAIN_SOURCE')
    require(receipt['production_ready'] is False and receipt['automatic_publication'] is False and receipt['protected_signing'] is False,'MAIN_INTERNAL_ONLY')
    for name,package in (('main-internal.apk','com.thekain.video.rebuild'),('main-tests.apk','com.thekain.video.rebuild.test')):
        apk=folder/name;require(digest(apk)==receipt['apk_sha256'][name],'MAIN_APK_HASH')
        require(certificate(apk)==receipt['debug_certificate_sha256']==native['debug_certificate_sha256'],'MAIN_SAME_RUN_DEBUG_CERT')
        text=subprocess.check_output([str(tools()/'aapt'),'dump','badging',str(apk)],text=True)
        require(re.findall(r"^package: name='([^']+)'",text,re.M)==[package],'MAIN_PACKAGE')
        require(re.findall(r"^sdkVersion:'(\d+)'",text,re.M)==['26'] and re.findall(r"^targetSdkVersion:'(\d+)'",text,re.M)==['35'],'MAIN_SDK')
        if name=='main-internal.apk':require(re.search(r"^package: .*versionCode='3'",text,re.M),'MAIN_VERSION')
    apk=folder/'main-internal.apk'
    subprocess.run([str(tools()/'zipalign'),'-c','-P','16','4',str(apk)],check=True)
    with zipfile.ZipFile(apk) as z:
        require(len(z.namelist())==len(set(z.namelist())),'MAIN_DUPLICATE_ZIP')
        verify_runtime_assets(z,native)
        inventory=json.loads(z.read('assets/third_party_internal/inventory.json'))
        require(inventory['repository_commit']==source and inventory['configuration']=='debugRuntimeClasspath','MAIN_EFFECTIVE_INVENTORY')
        require(inventory['production_ready'] is False and inventory['automatic_publication'] is False,'MAIN_LEGAL_FLAGS')
        external=inventory['native_bindings'];expected_paths={f'lib/{abi}/libandroidx.graphics.path.so' for abi in PROFILE['abis']}
        require(set(external)==expected_paths and all(re.fullmatch('[0-9a-f]{64}',v) for v in external.values()),'MAIN_BASELINE_NATIVE_BINDINGS')
        require(any(x['component']=='androidx.graphics:graphics-path:1.0.1' for x in inventory['components']),'MAIN_BASELINE_NATIVE_COMPONENT')
        expected=dict(external)
        for abi,data in native['abis'].items():
            for item in data['elf'].values():expected[f'lib/{abi}/'+item['packaged_name']]=item['sha256']
        actual={n for n in z.namelist() if n.endswith('.so')};require(actual==set(expected),'MAIN_NATIVE_SET')
        for path,sha in expected.items():
            blob=z.read(path);require(hashlib.sha256(blob).hexdigest()==sha,'MAIN_NATIVE_HASH')
            require(blob[:4]==b'\x7fELF' and blob[4]==2 and blob[5]==1,'MAIN_NATIVE_FORMAT')
            offset=struct.unpack_from('<Q',blob,32)[0];size,count=struct.unpack_from('<HH',blob,54)
            require(size>=56 and offset+size*count<=len(blob),'MAIN_ELF_BOUNDS')
            align=[struct.unpack_from('<Q',blob,offset+i*size+48)[0] for i in range(count) if struct.unpack_from('<I',blob,offset+i*size)[0]==1]
            require(align and min(align)>=16384,'MAIN_ELF_16K')
        for name,item in native['license_notices'].items():require(hashlib.sha256(z.read('assets/legal/'+name)).hexdigest()==item['sha256'],'MAIN_NATIVE_NOTICE')
    with zipfile.ZipFile(folder/'main-tests.apk') as z:require(not any(n.endswith('.so') for n in z.namelist()),'MAIN_TEST_NATIVE')
    print('Internal main source/APK/debug-certificate/assets/ELF16KiB receipt PASS; physical ARM64 NOT_RUN; not APK89 verification or product release approval')


def pack(folder):
    folder=Path(folder)
    shutil.copy2(ROOT/'app/build/outputs/apk/debug/app-debug.apk',folder/'main-internal.apk')
    shutil.copy2(ROOT/'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk',folder/'main-tests.apk')
    receipt=dict(source=os.environ['GITHUB_SHA'],apk_sha256={name:digest(folder/name) for name in ('main-internal.apk','main-tests.apk')},
                 debug_certificate_sha256=certificate(folder/'main-internal.apk'),production_ready=False,automatic_publication=False,protected_signing=False,
                 scope='EXPLICIT_INTERNAL_MAIN_ANALYZER_TEST; no phone update or publication')
    (folder/'main-report.json').write_text(json.dumps(receipt,indent=2)+'\n');verify(folder)


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('command',choices=('pack','verify'));parser.add_argument('--folder',type=Path,required=True)
    args=parser.parse_args();pack(args.folder) if args.command=='pack' else verify(args.folder)
