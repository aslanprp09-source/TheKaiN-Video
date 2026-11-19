import copy
import unittest
from .phone_report import inspect


def sample():
    """Synthetic schema fixture only; never written as real evidence."""
    source = 'a'*40
    receipt = {'repository_commit': source, 'apk_sha256': {'lab.apk': 'b'*64}, 'debug_certificate_sha256': 'c'*64}
    cases = []
    for index, (kind, error) in enumerate([(1,0)]+[(2,0)]*6+[(4,1),(4,2),(5,4),(4,4)]):
        cases.append({'id': f'00000000-0000-0000-0000-{index:012d}', 'source_commit': source,
            'outcome': 'TERMINAL', 'kind': kind, 'error': error, 'death_confirmed': True, 'main_python_loaded': False,
            'worker_pid': 200+index, 'parent_pid': 100, 'worker_start_ticks': 1000+index,
            'worker_uid': 10000, 'parent_uid': 10000, 'rss_samples': 10, 'peak_sampled_rss_bytes': 100000000,
            'cpu_ticks': 50, 'clock_ticks_per_second': 100, 'observed_worker_cpu_ms': 500, 'download_enabled': False,
            'machine': 'aarch64', 'pointer_bits': 64, 'openssl_handshake': True, 'python_calls': 1,
            'formats': 1, 'operator_battery_setting': 'standard' if index%2 else 'restricted',
            'requested_fault': 'verified_worker_kill' if kind == 4 and error == 4 else 'none', 'elapsed_ms': 6000})
    cases.append({'id':'00000000-0000-0000-0000-999999999999','source_commit':source,
        'outcome':'INTERRUPTED_OWNER_OR_SESSION','parent_identity_changed':True,'death_confirmed_on_relaunch':True,'error':None})
    return {'schema':1, 'source_commit':source, 'production_ready':False, 'product_integration':False,
        'physical_device_attested':False, 'artifact89_review':'BLOCKED_SEPARATE_GATE',
        'isolation_uid_mode':'shared_application_uid', 'rss_limit_is_instantaneous':False,
        'operator_claimed_physical_device':True, 'device':{'apk_sha256':'b'*64,'certificate_sha256':'c'*64,
            'emulator_hint':False,'process_64bit':True,'abis':['arm64-v8a'],'sdk':36},
        'cases':cases, 'footprint':{'worker_processes':0,'owned_temp_files':0},
        'events':[{'kind':'activity_stop','power':{'interactive':False}}]}, receipt


class PhoneIntakeTest(unittest.TestCase):
    def test_complete_operator_report_still_never_attests_or_updates_release_gate(self):
        report,receipt=sample();result=inspect(report,receipt)
        self.assertEqual(result['intake'],'OPERATOR_REPORT_REQUIRES_REVIEW')
        self.assertFalse(result['physical_device_attested']);self.assertFalse(result['automatic_release_gate_update'])

    def test_wrong_source_apk_certificate_emulator_and_incomplete_cases_are_rejected(self):
        report,receipt=sample()
        mutations=[lambda r:r.update(source_commit='d'*40),lambda r:r['device'].update(apk_sha256='d'*64),
            lambda r:r['device'].update(certificate_sha256='d'*64),lambda r:r['device'].update(emulator_hint=True),
            lambda r:r['device'].update(abis=['x86_64']),lambda r:r['cases'][0].update(machine='x86_64'),
            lambda r:r['cases'][0].update(source_commit='d'*40),lambda r:r['cases'][0].update(death_confirmed=False),
            lambda r:r['cases'][0].update(main_python_loaded=True),lambda r:r['cases'][0].update(observed_worker_cpu_ms=501),
            lambda r:r['cases'].pop(),lambda r:r.update(pending={}),lambda r:r['footprint'].update(worker_processes=1),
            lambda r:r.update(events=[]),lambda r:r.update(private_key='hidden'),
            lambda r:r.update(extra='-----BEGIN PRIVATE KEY-----'),lambda r:r.update(extra='https://private.invalid/?token=secret')]
        for index,mutate in enumerate(mutations):
            with self.subTest(index=index):
                bad=copy.deepcopy(report);mutate(bad)
                with self.assertRaises(ValueError):inspect(bad,receipt)
