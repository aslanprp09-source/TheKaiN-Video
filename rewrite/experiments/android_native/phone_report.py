"""Offline intake of an operator-exported lab report; never a device attestation/release gate."""
import argparse
import json
from pathlib import Path
import re


def require(value, code):
    if not value:
        raise ValueError(code)


def private_data(value):
    if isinstance(value, dict):
        for key, item in value.items():
            require(key.lower() not in {'url', 'python_code', 'cookie', 'password', 'authorization', 'private_key'}, 'PRIVATE_FIELD')
            private_data(item)
    elif isinstance(value, list):
        for item in value:
            private_data(item)
    elif isinstance(value, str):
        require('-----BEGIN ' not in value and not re.search(r'https?://', value), 'PRIVATE_CONTENT')


def inspect(report, receipt):
    private_data(report)
    require(report.get('schema') == 1, 'SCHEMA')
    require(report.get('source_commit') == receipt['repository_commit'], 'SOURCE')
    require(report.get('production_ready') is False and report.get('product_integration') is False, 'PRODUCT_GATE')
    require(report.get('physical_device_attested') is False, 'NO_ATTESTATION')
    require(report.get('artifact89_review') == 'BLOCKED_SEPARATE_GATE', 'SEPARATE_QA89_GATE')
    require(report.get('isolation_uid_mode') == 'shared_application_uid' and report.get('rss_limit_is_instantaneous') is False, 'LIMITATIONS')
    device = report['device']
    require(device['apk_sha256'] == receipt['apk_sha256']['lab.apk'], 'APK')
    require(device['certificate_sha256'] == receipt['debug_certificate_sha256'], 'CERTIFICATE')
    require(device.get('emulator_hint') is False and report.get('operator_claimed_physical_device') is True, 'OPERATOR_PHYSICAL_CLAIM')
    require(device.get('process_64bit') is True and 'arm64-v8a' in device.get('abis', []), 'DEVICE_ARM64')
    require(type(device.get('sdk')) is int and device['sdk'] >= 26, 'SDK')
    require('pending' not in report, 'UNFINISHED')
    cases = report['cases']
    require(1 <= len(cases) <= 64 and len({c['id'] for c in cases}) == len(cases), 'CASE_IDS')
    for case in cases:
        require(re.fullmatch('[0-9a-f-]{36}', case['id']) is not None, 'CASE_ID')
        require(case['source_commit'] == receipt['repository_commit'], 'STALE_CASE_SOURCE')
    terminal = [c for c in cases if c.get('outcome') == 'TERMINAL']
    for case in terminal:
        require(case.get('error') in (0, 1, 2, 4), 'UNEXPECTED_RUNTIME_ERROR')
        require(case.get('death_confirmed') is True and case.get('main_python_loaded') is False, 'CONTAINMENT')
        require(case['worker_pid'] > 0 and case['worker_pid'] != case['parent_pid'] and case['worker_start_ticks'] > 0, 'WORKER_IDENTITY')
        require(case['parent_uid'] == case['worker_uid'] and case['parent_uid'] > 0, 'SHARED_UID')
        require(case['rss_samples'] > 0 and case['peak_sampled_rss_bytes'] > 0, 'RSS')
        ticks, hz = case['cpu_ticks'], case['clock_ticks_per_second']
        require(type(ticks) is int and ticks >= 0 and type(hz) is int and 0 < hz <= 100000, 'CPU_COUNTERS')
        require(case['observed_worker_cpu_ms'] == ticks*1000//hz, 'CPU_CONVERSION')
        require(case.get('download_enabled') is False, 'NO_DOWNLOADS')
    def covered(kind, error):
        return [c for c in terminal if c['kind'] == kind and c['error'] == error]
    probes = covered(1, 0)
    require(probes and all(c.get('machine') == 'aarch64' and c.get('pointer_bits') == 64
        and c.get('openssl_handshake') is True and c.get('python_calls') == 1 for c in probes), 'EXECUTED_ARM64_PROBE')
    analyses = covered(2, 0)
    require(len(analyses) >= 6 and all(c.get('formats') == 1 and c.get('python_calls') == 1 for c in analyses), 'REPEATED_ANALYSIS')
    require({'standard', 'restricted'} <= {c.get('operator_battery_setting') for c in analyses}, 'BATTERY_SCENARIOS')
    require(covered(4, 1) and covered(4, 2) and covered(5, 4), 'FAULT_COVERAGE')
    require(any(c.get('requested_fault') == 'verified_worker_kill' for c in covered(4, 4)), 'WORKER_KILL')
    interrupted = [c for c in cases if c.get('outcome') == 'INTERRUPTED_OWNER_OR_SESSION']
    require(interrupted and any(c.get('parent_identity_changed') is True and c.get('death_confirmed_on_relaunch') is True
        and c.get('error') is None for c in interrupted), 'OWNER_RESTART')
    require(len(terminal)+len(interrupted) == len(cases), 'UNKNOWN_OUTCOME')
    footprint = report['footprint']
    require(footprint['worker_processes'] == 0 and footprint['owned_temp_files'] == 0, 'FINAL_FOOTPRINT')
    events = report.get('events', [])
    require(len(events) <= 128 and any(e.get('kind') == 'activity_stop' for e in events)
        and any(e.get('power', {}).get('interactive') is False for e in events), 'BACKGROUND_OBSERVATIONS')
    return {'intake': 'OPERATOR_REPORT_REQUIRES_REVIEW', 'source_commit': report['source_commit'],
        'physical_device_attested': False, 'arm64_probe_reported': True, 'cases': len(cases),
        'max_sampled_rss_bytes': max(c['peak_sampled_rss_bytes'] for c in terminal),
        'max_observed_cpu_ms': max(c['observed_worker_cpu_ms'] for c in terminal),
        'timeout_elapsed_ms': [c['elapsed_ms'] for c in covered(4, 2)],
        'automatic_release_gate_update': False, 'production_ready': False}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--report', type=Path, required=True)
    parser.add_argument('--receipt', type=Path, required=True)
    args = parser.parse_args()
    require(args.report.stat().st_size <= 256*1024, 'REPORT_SIZE')
    print(json.dumps(inspect(json.loads(args.report.read_text()), json.loads(args.receipt.read_text())), indent=2))
