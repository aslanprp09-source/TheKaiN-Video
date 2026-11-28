from copy import deepcopy
import unittest
from rewrite.experiments.android_native import test_readiness_report
from rewrite.experiments.android_native.terminal_report import inspect


class TerminalIntakeTest(unittest.TestCase):
    def fixture(self):
        barrier, _, source = test_readiness_report.ReadinessIntakeTest().fixture()
        race = dict(case='race', source_commit=source, api=36, pages=16384, production_ready=False,
                    actual_tls=True, actual_binder=True, rows=[])
        serial = 0
        for mode in ('death', 'cancel', 'ipc'):
            for attempt in range(3):
                proofs = []
                for _ in range(2):
                    serial += 1; p = deepcopy(barrier['proofs'][0])
                    p.update(id=f'{serial:08x}-0000-0000-0000-000000000000', instance=f'{serial:08x}-0000-0000-0000-000000000000',
                             terminal_origin='OWNER_CLOSE', terminal_category='TYPED', requests=1, payload_bytes=5)
                    proofs.append(p)
                retired, fresh = proofs
                code = dict(death=4, cancel=1, ipc=5)[mode]
                retired.update(failure=0 if mode == 'cancel' else code,
                    terminal_origin=dict(death='BINDER_DEATH', cancel='OWNER_CLOSE', ipc='REPLY_RPC')[mode],
                    terminal_category=dict(death='DEAD_OBJECT', cancel='TYPED', ipc='PROTOCOL')[mode])
                before = deepcopy(retired)
                before.update(failure=0, terminal_origin='NONE', terminal_category='NONE', binder_dead=False,
                              terminated=False, kernel_process_gone=False, lease_clean=False)
                race['rows'].append(dict(mode=mode, attempt=attempt, returned=code, before=before, retired=retired, fresh=fresh))
        kernel = dict(case='kernel', source_commit=source, api=36, pages=16384, production_ready=False,
            simulated_parser_only=True, dying_missing_rss=True, zombie_not_absent=True, unknown_refused=True,
            malformed_refused=True, access_denied_refused=True, pid_change_refused=True, actual_absence_errno_only=True, pid_reuse=True,
            exiting_flag_before_zombie=True, exiting_not_absent=True, invalid_flags_refused=True, exit_regression_refused=True)
        return race, kernel, source

    def test_no_pass_for_missing_or_mismatched_cause_stale_process_or_unconfirmed_cleanup(self):
        race, kernel, source = self.fixture()
        self.assertEqual(inspect(race, kernel, source, 36, 16384)['real_worker_proofs'], 18)
        exiting = deepcopy(race)
        exiting['rows'][0]['retired'].update(terminal_origin='KERNEL_WATCH',terminal_category='PF_EXITING',kernel_flags=4)
        self.assertEqual(inspect(exiting,kernel,source,36,16384)['real_worker_proofs'],18)
        changes = [
            lambda r, k: r.update(source_commit='e'*40), lambda r, k: k.update(pages=4096),
            lambda r, k: r['rows'].pop(), lambda r, k: r.update(actual_tls=False),
            lambda r, k: r.update(actual_binder=False), lambda r, k: r['rows'][0].update(returned=1),
            lambda r, k: r['rows'][0].update(returned=5), lambda r, k: r['rows'][6].update(returned=4),
            lambda r, k: r['rows'][0]['retired'].update(failure=5),
            lambda r, k: r['rows'][3]['retired'].update(failure=1),
            lambda r, k: r['rows'][0]['before'].update(binder_dead=True),
            lambda r, k: r['rows'][0]['before'].update(ready_published=False),
            lambda r, k: r['rows'][0]['before'].update(id='e'*36),
            lambda r, k: r['rows'][0]['retired'].update(kernel_process_gone=False),
            lambda r, k: r['rows'][0]['retired'].update(lease_clean=False),
            lambda r, k: r['rows'][0]['retired'].update(service_absent=False),
            lambda r, k: r['rows'][0]['retired'].update(retire_ms=2501),
            lambda r, k: r['rows'][0]['fresh'].update(payload_bytes=0),
            lambda r, k: r['rows'][0]['fresh'].update(instance=r['rows'][0]['retired']['instance']),
            lambda r, k: r['rows'][6]['retired'].update(terminal_origin='KERNEL_WATCH'),
            lambda r, k: k.update(zombie_not_absent=False), lambda r, k: k.update(access_denied_refused=False),
            lambda r, k: k.update(simulated_parser_only=False), lambda r, k: k.update(pid_change_refused=False),
            lambda r, k: k.update(exiting_flag_before_zombie=False), lambda r, k: k.update(exiting_not_absent=False),
            lambda r, k: k.update(invalid_flags_refused=False), lambda r, k: k.update(exit_regression_refused=False),
            lambda r, k: r['rows'][0]['retired'].update(terminal_origin='KERNEL_WATCH',terminal_category='PF_EXITING',kernel_flags=0),
        ]
        for index, change in enumerate(changes):
            r, k = deepcopy(race), deepcopy(kernel); change(r, k)
            with self.subTest(index=index), self.assertRaises((AssertionError, KeyError)):
                inspect(r, k, source, 36, 16384)
