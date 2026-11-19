"""Same-run lab APK receipt verification; never repins product APK89."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import zipfile
if __package__:
    from .build import PROFILE,digest,require
else:
    from build import PROFILE,digest,require


def tools(): return Path(os.environ['ANDROID_HOME'])/'build-tools/35.0.0'


def certificate(apk):
    text=subprocess.check_output([str(tools()/'apksigner'),'verify','--verbose','--print-certs',str(apk)],text=True)
    matches=re.findall(r'^Signer #\d+ certificate SHA-256 digest: ([0-9a-f]+)$',text,re.M)
    require(len(matches)==1,'LAB_APK_SIGNER_COUNT')
    return matches[0]


def verify_runtime_assets(archive,report):
    require(set(report['asset_inventory_sha256'])==set(PROFILE['abis']), 'LAB_ASSET_ABI_SET')
    expected=set()
    for abi,sha in report['asset_inventory_sha256'].items():
        raw=archive.read('assets/inventory-'+abi+'.json')
        require(hashlib.sha256(raw).hexdigest()==sha, 'LAB_INVENTORY_DIGEST')
        inventory=json.loads(raw)
        require(len(inventory)==report['abis'][abi]['asset_files'], 'LAB_ASSET_COUNT')
        total=0
        for name,item in inventory.items():
            require(not name.startswith('/') and '..' not in Path(name).parts and '\\' not in name, 'LAB_ASSET_PATH')
            path=f'assets/runtime/{abi}/{name}';expected.add(path)
            require(path in archive.namelist(), 'LAB_ASSET_MISSING')
            raw=archive.read(path);total+=len(raw)
            require(len(raw)==item['bytes'] and hashlib.sha256(raw).hexdigest()==item['sha256'], 'LAB_ASSET_BYTES_CHANGED')
        require(total==report['abis'][abi]['asset_bytes'], 'LAB_ASSET_TOTAL')
    actual={n for n in archive.namelist() if n.startswith('assets/runtime/') and not n.endswith('/')}
    require(actual==expected, 'LAB_UNEXPECTED_ASSET_SET')


def verify(folder):
    folder=Path(folder);report=json.loads((folder/'build-report.json').read_text())
    require(report['profile']==PROFILE and report.get('repository_commit')==os.environ['GITHUB_SHA'],'LAB_SOURCE_MISMATCH')
    require(set(report['abis'])==set(PROFILE['abis']),'LAB_ABI_SET_MISMATCH')
    for name in ('lab.apk','tests.apk','probe.apk'):
        apk=folder/name
        require(digest(apk)==report['apk_sha256'][name],'LAB_APK_DIGEST_MISMATCH')
        require(certificate(apk)==report['debug_certificate_sha256'],'LAB_CERTIFICATE_MISMATCH')
        text=subprocess.check_output([str(tools()/'aapt'),'dump','badging',str(apk)],text=True)
        expected='com.thekain.runtime.probe' if name=='probe.apk' else 'com.thekain.runtime.lab'+('.test' if name=='tests.apk' else '')
        require(re.findall(r"^package: name='([^']+)'",text,re.M)==[expected],'LAB_PACKAGE_IDENTITY')
        require(re.findall(r"^sdkVersion:'(\d+)'",text,re.M)==['26'],'LAB_MIN_SDK')
        require(re.findall(r"^targetSdkVersion:'(\d+)'",text,re.M)==['35'],'LAB_TARGET_SDK')
    subprocess.run([str(tools()/'zipalign'),'-c','-P','16','4',str(folder/'lab.apk')],check=True)
    with zipfile.ZipFile(folder/'lab.apk') as archive:
        verify_runtime_assets(archive,report)
        for name,sha in report['qa_debug_notices_sha256'].items():
            require(name in ('qa-debug-dependencies.json','qa-debug-Apache-2.0.txt'),'LAB_QA_NOTICE_PATH')
            require(hashlib.sha256(archive.read('assets/legal/'+name)).hexdigest()==sha,'LAB_QA_NOTICE_CHANGED')
        require(set(report['qa_debug_notices_sha256'])=={'qa-debug-dependencies.json','qa-debug-Apache-2.0.txt'},'LAB_QA_NOTICES_MISSING')
        for name,item in report['license_notices'].items():
            require('/' not in name and '\\' not in name, 'LAB_NOTICE_PATH')
            require(hashlib.sha256(archive.read('assets/legal/'+name)).hexdigest()==item['sha256'], 'LAB_NOTICE_BYTES_CHANGED')
        expected={}
        for abi,data in report['abis'].items():
            for name,item in data['elf'].items():
                path=f'lib/{abi}/'+item['packaged_name']
                expected[path]=item['sha256']
        require({n for n in archive.namelist() if n.endswith('.so')}==set(expected),'LAB_UNEXPECTED_ELF_SET')
        for name,sha in expected.items():
            require(hashlib.sha256(archive.read(name)).hexdigest()==sha,'LAB_ELF_BYTES_CHANGED')
    for name in ('tests.apk','probe.apk'):
        with zipfile.ZipFile(folder/name) as archive:
            require(not any(n.endswith('.so') for n in archive.namelist()),'LAB_TEST_APK_UNKNOWN_NATIVE_CODE')
    print('Same-run lab source/APK/certificate/ELF/ZIP receipt: PASS (not product signing or runtime execution)')


def pack(work,output):
    work=Path(work);output=Path(output);output.mkdir(parents=True,exist_ok=False)
    project=Path(__file__).parent/'android/app/build/outputs/apk'
    shutil.copy2(project/'debug/app-debug.apk',output/'lab.apk')
    shutil.copy2(project/'androidTest/debug/app-debug-androidTest.apk',output/'tests.apk')
    shutil.copy2(Path(__file__).parent/'android/probe/build/outputs/apk/debug/probe-debug.apk',output/'probe.apk')
    report=json.loads((work/'package/build-report.json').read_text())
    report['repository_commit']=os.environ['GITHUB_SHA']
    report['asset_inventory_sha256']={abi:digest(work/'package/assets'/('inventory-'+abi+'.json')) for abi in PROFILE['abis']}
    report['apk_sha256']={name:digest(output/name) for name in ('lab.apk','tests.apk','probe.apk')}
    with zipfile.ZipFile(output/'lab.apk') as archive:
        report['qa_debug_notices_sha256']={name:hashlib.sha256(archive.read('assets/legal/'+name)).hexdigest()
            for name in ('qa-debug-dependencies.json','qa-debug-Apache-2.0.txt')}
    report['debug_certificate_sha256']=certificate(output/'lab.apk')
    (output/'build-report.json').write_text(json.dumps(report,indent=2)+'\n')
    verify(output)


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    sub=parser.add_subparsers(dest='command',required=True)
    p=sub.add_parser('pack');p.add_argument('--work',type=Path,required=True);p.add_argument('--output',type=Path,required=True)
    p=sub.add_parser('verify');p.add_argument('--folder',type=Path,required=True)
    args=parser.parse_args()
    if args.command=='pack':pack(args.work,args.output)
    else:verify(args.folder)
