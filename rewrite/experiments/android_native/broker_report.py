"""Strict candidate component evidence; fixture-mode success cannot count as public-policy TLS."""
import argparse
import json
from pathlib import Path
import re

SHA='dd201a3b814b113ca98749902195b996a1e2f608247bdd22d2044b4c0dfd5cb2'
CASES=('address','pinning','tls','cancellation','limits','public')


def inspect(records,source,api,pages):
    assert set(records)==set(CASES) and re.fullmatch('[0-9a-f]{40}',source)
    for name,data in records.items():
        assert data['case']==name and data['source_commit']==source and data['api']==api and data['page_size']==pages
        assert data['production_ready'] is False and data['product_integration'] is False
        assert data['measurement_scope']=='instrumented_process_including_owned_fixture_not_peak'
        assert all(type(data[k]) is int and data[k]>=0 for k in ('elapsed_ms','process_cpu_delta_ms','pss_start_kib','pss_end_kib'))
        assert data['pss_start_kib']>0 and data['pss_end_kib']>0
    required={'address':('public_policy','mixed_rejected','peer_rejected'),
              'pinning':('fixture_only','single_lookup','rebind_rejected'),
              'tls':('fixture_only','wrong_ca_rejected','wrong_host_rejected','credentials_rejected','downgrade_rejected'),
              'cancellation':('fixture_only','body_cancel','dns_cancel','dns_deadline','late_result_refused','actual_quiescence_checked'),
              'limits':('fixture_only','body_limit','header_limit','aggregate_limit','request_limit','identity_only','credentials_absent',
                        'chunked_refused_before_body','unknown_length_refused','truncation_refused'),
              'public':('public_policy','system_tls','owned_fixture')}
    for name,keys in required.items():
        assert all(records[name][key] is True for key in keys)
    assert records['address']['connections']==0 and records['pinning']['redirect_connections']==1
    assert records['cancellation']['dns_threads_bounded']==2
    public=records['public'];assert not public.get('fixture_only',False)
    assert public['bytes']==2428 and public['sha256']==SHA and public['requests']==1
    return dict(controlled_https_component='PASS',tests=6,api=api,actual_page_size=pages,
                public_owned_fixture='SYSTEM_TLS_AND_EXACT_BYTES',python_ipc_integration=False,
                measurements={name:{k:data[k] for k in ('elapsed_ms','process_cpu_delta_ms','pss_start_kib','pss_end_kib')} for name,data in records.items()},
                measurement_scope='instrumented_process_including_owned_fixture_not_peak',
                physical_arm64='NOT_RUN',production_ready=False,automatic_publication=False)


def main():
    parser=argparse.ArgumentParser();parser.add_argument('--evidence',type=Path,required=True)
    parser.add_argument('--source',required=True);parser.add_argument('--api',type=int,required=True);parser.add_argument('--pages',type=int,required=True)
    args=parser.parse_args();text=(args.evidence/'broker-instrumentation.txt').read_text();print(text)
    assert re.search(r'^OK \(6 tests\)\s*$',text,re.M)
    assert len(re.findall(r'^INSTRUMENTATION_STATUS_CODE: 0\s*$',text,re.M))==6
    assert not re.search(r'FAILURES!!!|shortMsg=|INSTRUMENTATION_(?:FAILED|ABORTED)|INSTRUMENTATION_STATUS_CODE: -[1-4]\b',text)
    records={}
    for name in CASES:
        raw=(args.evidence/'broker'/f'{name}.json').read_bytes();assert 0<len(raw)<=16384
        records[name]=json.loads(raw)
    print('::notice::'+json.dumps(inspect(records,args.source,args.api,args.pages)))


if __name__=='__main__':main()
