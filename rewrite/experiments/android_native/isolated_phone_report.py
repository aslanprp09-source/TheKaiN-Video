"""Review aid for an operator-exported isolated UID report; never device attestation."""
import argparse
from collections import Counter
import json
from pathlib import Path
if __package__:
    from .phone_report import private_data
else:
    from phone_report import private_data


def inspect(report,receipt):
    private_data(report)
    assert report['schema']==1 and report['boundary']=='android_isolated_uid'
    assert report['source_commit']==receipt['repository_commit'], 'Source mismatch'
    assert report['production_ready'] is False and report['product_integration'] is False
    assert report['physical_device_attested'] is False and report['qa89_review']=='BLOCKED_SEPARATE_TASK'
    assert report['rss_is_instantaneous'] is False
    assert not report.get('pending') and not report.get('qa_failure_class'), 'Incomplete/failed operator protocol'
    device=report['device']
    assert device['apk_sha256']==receipt['apk_sha256']['lab.apk'], 'APK mismatch'
    assert device['certificate_sha256']==receipt['debug_certificate_sha256'], 'Certificate mismatch'
    assert device['sdk']>=26 and device['process_64bit'] is True and 'arm64-v8a' in device['abis']
    assert device['hardware'] not in ('ranchu','goldfish') and not device['fingerprint'].startswith('generic'), 'Emulator is not a phone'
    serialized=json.dumps(report)
    assert '-----BEGIN' not in serialized and 'https://' not in serialized and 'http://' not in serialized, 'Unexpected sensitive/URL payload'
    rows=report['cases'];assert 13<=len(rows)<=64
    instances=[]
    for row in rows:
        assert row['error']==row['expected_error'] or (row['expected_error']==-1 and row['error'] in (1,2)), 'Unexpected failure'
        assert row['death_confirmed'] is True and row['main_python_loaded'] is False
        assert row['owned_ipc_temp_files']==0, 'Retained resource files'
        if row['worker_pid']:
            assert row['worker_pid']!=row['parent_pid'] and row['worker_uid']!=row['parent_uid']
            assert 90000<=row['worker_uid']%100000<100000 and row['start_ticks']>0
            assert row['samples']>0 and row['peak_rss_bytes']>0
            instances.append(row['instance'])
        assert row['requests']<=12 and row['payload_bytes']<=2*1024*1024+1
        if row['python_entered']:
            assert 0 < row['resource_input_bytes'] <= 160*1024*1024
            assert row['observed_cpu_ms']==row['cpu_ticks']*1000//row['clock_ticks_per_second']
            assert row['observed_cpu_ms']>0 and row['rlimit_cpu']>0
            assert row['rlimit_as']==row['virtual_before']+256*1024*1024
        if row['error']==0:
            assert row['python_calls']==1 and row['security_denials']==(255 if row['mode']==1 else 31)
    assert len(instances)==len(set(instances)), 'Interpreter reused'
    probes=[r for r in rows if r['operation']==1 and r['mode']==0 and r['error']==0]
    assert probes and all(r['machine']=='aarch64' and r['pointer_bits']==64 and r['pages'] in (4096,16384)
        and r['openssl_handshake'] is True for r in probes), 'No actual ARM64 JNI/OpenSSL probe'
    coverage={(r['operation'],r['mode'],r['error']) for r in rows}
    assert {(1,1,0),(4,2,1),(4,2,2),(5,0,4),(6,0,7),(1,3,5),(1,4,6)}<=coverage, 'Missing sandbox/fault coverage'
    assert all(r['python_entered'] for r in rows if r['mode']==2), 'No actual blocked Python proof'
    last_fault=max(i for i,r in enumerate(rows) if r['expected_error'] in (4,5,6,7))
    analyses=[r for r in rows[last_fault+1:] if r['operation']==2 and r['error']==0]
    assert len(analyses)>=5, 'Five fresh owned analyses after fault recovery required'
    return {'status':'OPERATOR_REPORT_REQUIRES_REVIEW','source_commit':report['source_commit'],
        'sdk':device['sdk'],'operations':len(rows),'worker_instances':len(instances),
        'errors':dict(Counter(r['error'] for r in rows)),
        'max_sampled_rss_bytes':max(r['peak_rss_bytes'] for r in rows),
        'max_resource_input_bytes':max(r['resource_input_bytes'] for r in rows),
        'max_retire_to_exit_ms':max(r['retire_to_exit_ms'] for r in rows),
        'production_ready':False,'physical_device_attested':False,'qa89_review':'BLOCKED_SEPARATE_TASK',
        'limitations':['Operator JSON can be edited; manual review and original same-run receipt required',
            'Owner-loss automatic recovery, prolonged background and deep Doze remain unverified',
            'RSS/CPU are sampled observations, not an instantaneous hard memory cap']}


def main():
    p=argparse.ArgumentParser();p.add_argument('--report',type=Path,required=True);p.add_argument('--receipt',type=Path,required=True)
    a=p.parse_args();assert a.report.stat().st_size<=256*1024 and a.receipt.stat().st_size<=2*1024*1024
    print(json.dumps(inspect(json.loads(a.report.read_text()),json.loads(a.receipt.read_text())),indent=2))


if __name__=='__main__':main()
