"""Offline regression tests: no real keys, GitHub writes, or 7-Zip required."""
import importlib.util
import io
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

TOOL = Path(__file__).resolve().parents[1] / 'tools' / 'owner_android_signing.py'
spec = importlib.util.spec_from_file_location('owner_android_signing', TOOL)
owner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(owner)


class OwnerSigningTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.key = self.root / 'owner.p12'
        self.key.write_bytes(b'private owner key fixture\x00')
        (self.root / 'a').mkdir()
        (self.root / 'b').mkdir()
        self.one = self.root / 'a' / 'backup.7z'
        self.two = self.root / 'b' / 'backup.7z'

    def gh_policies(self):
        env = {'name': 'release-signing', 'can_admins_bypass': False,
               'deployment_branch_policy': {'protected_branches': False, 'custom_branch_policies': True},
               'protection_rules': [{'type': 'required_reviewers', 'prevent_self_review': True,
                   'reviewers': [{'type': 'User', 'reviewer': {'id': 355790290}}]}]}
        branches = {'total_count': 1, 'branch_policies': [{'name': 'rebuild-2026-11-11', 'type': 'branch'}]}
        rules = {'id': 27181193, 'enforcement': 'active',
                 'conditions': {'ref_name': {'include': ['refs/heads/rebuild-2026-11-11'], 'exclude': []}},
                 'bypass_actors': [], 'rules': [
                    {'type':'deletion'}, {'type':'non_fast_forward'},
                    {'type':'pull_request','parameters':{'required_approving_review_count':1,
                        'dismiss_stale_reviews_on_push':True,'require_last_push_approval':True,
                        'required_review_thread_resolution':True}},
                    {'type':'required_status_checks','parameters':{'strict_required_status_checks_policy':True,
                        'required_status_checks':[{'context': k,'integration_id':15368}
                          for k in owner.EXPECTED_CHECKS]}}]}
        return env, branches, rules

    def test_owner_tool_never_runs_in_actions(self):
        for values in ({'CI':'true'},{'GITHUB_ACTIONS':'true'}):
            with self.subTest(values=values), patch.dict(os.environ, values, clear=True):
                with self.assertRaises(owner.SetupError):
                    owner.owner_only()

    def test_private_file_rejects_repo_and_overwrite(self):
        with self.assertRaises(owner.SetupError):
            owner.private_file(owner.SCRIPT_ROOT/'do-not-save.p12', False)
        with self.assertRaises(owner.SetupError):
            owner.private_file(self.key, False)
        self.assertEqual(owner.private_file(self.key, True), self.key)

    def test_alias_must_match_controlled_policy(self):
        for alias in ('', '-bad', '../a', 'has space', 'x'*129):
            with self.subTest(alias=alias), self.assertRaises(owner.SetupError):
                owner.alias_ok(alias)
        self.assertEqual(owner.alias_ok('thekain-release.1'), 'thekain-release.1')

    def test_backups_must_be_unique_non_overwriting(self):
        with self.assertRaises(owner.SetupError):
            owner.backup_paths(self.key, self.one, self.one, new=True)
        self.one.touch()
        with self.assertRaises(owner.SetupError):
            owner.backup_paths(self.key, self.one, self.two, new=True)
        with self.assertRaises(owner.SetupError):
            owner.backup_paths(self.key, self.one, self.two, new=False)
        self.two.touch()
        self.assertEqual(owner.backup_paths(self.key, self.one, self.two, new=False)[1], [self.one,self.two])

    def test_generate_uses_keytool_interactive_not_commandline_password(self):
        target = self.root/'new-key.p12'
        commands = []
        def tool(args):
            commands.append(args)
            target.write_bytes(b'generated-keystore')
        with patch.object(owner, 'owner_only'), patch.object(owner, 'executable', return_value='keytool'), patch.object(owner, 'run_interactive', side_effect=tool):
            owner.generate(target, 'release')
        args = commands[0]
        self.assertIn('4096', args)
        self.assertIn('SHA256withRSA', args)
        self.assertNotIn('-storepass', args)
        self.assertNotIn('-keypass', args)

    def test_restore_test_requires_original_file_hash(self):
        self.one.touch()
        self.two.touch()
        calls = []
        def fake_7z(args, cwd=None):
            calls.append(args)
            target = Path(next(a[2:] for a in args if a.startswith('-o'))) / self.key.name
            target.write_bytes(self.key.read_bytes())
        with patch.object(owner, 'run_interactive', side_effect=fake_7z):
            owner.verify_archives(self.key, [self.one, self.two], '7z')
        self.assertEqual(len(calls), 2)
        self.assertTrue(all('-p' in args and '-y' in args for args in calls))

    def test_bad_restored_bytes_fail(self):
        self.one.touch()
        def fake_7z(args, cwd=None):
            target = Path(next(a[2:] for a in args if a.startswith('-o'))) / self.key.name
            target.write_bytes(b'not the key')
        with patch.object(owner, 'run_interactive', side_effect=fake_7z):
            with self.assertRaisesRegex(owner.SetupError, 'comparison'):
                owner.verify_archives(self.key, [self.one,self.two], '7z')

    def test_gh_set_never_places_password_in_process_args(self):
        with patch.object(owner.subprocess, 'run') as child:
            owner.gh_set('gh', 'secret', 'ANDROID_KEYSTORE_PASSWORD', 'TEST_PASSWORD_DO_NOT_USE')
        args, kwargs = child.call_args
        self.assertNotIn('TEST_PASSWORD_DO_NOT_USE', ' '.join(args[0]))
        self.assertEqual(kwargs['input'], 'TEST_PASSWORD_DO_NOT_USE')
        self.assertEqual(args[0][-4:], ['--env','release-signing','--repo',owner.REPOSITORY])

    def test_fingerprint_uses_only_public_cert_and_env_password(self):
        import hashlib
        fake = b'-----BEGIN CERTIFICATE-----\nAA==\n-----END CERTIFICATE-----'
        class Result:
            stdout = fake
        recorded = {}
        def run_child(args, **kwargs):
            recorded['args'] = args
            recorded['env'] = kwargs['env'].copy()
            return Result()
        with patch.object(owner.subprocess, 'run', side_effect=run_child), \
             patch.object(owner.ssl, 'PEM_cert_to_DER_cert', return_value=b'public der'):
            result = owner.fingerprint('keytool', self.key, 'release', 'secret-should-not-be-in-args')
        self.assertEqual(result, hashlib.sha256(b'public der').hexdigest())
        self.assertNotIn('secret-should-not-be-in-args', ' '.join(recorded['args']))
        self.assertEqual(recorded['env']['THEKAIN_LOCAL_STOREPASS'], 'secret-should-not-be-in-args')

    def test_preflight_accepts_only_expected_policy(self):
        env, branches, rules = self.gh_policies()
        with patch.object(owner, 'gh_json', side_effect=[env, branches, rules]):
            owner.check_github('gh')

    def test_preflight_rejects_admin_bypass(self):
        env, branches, rules = self.gh_policies()
        env['can_admins_bypass'] = True
        with patch.object(owner, 'gh_json', side_effect=[env, branches, rules]):
            with self.assertRaisesRegex(owner.SetupError, 'admin bypass'):
                owner.check_github('gh')

    def test_preflight_rejects_wrong_reviewer(self):
        env, branches, rules = self.gh_policies()
        env['protection_rules'][0]['reviewers'][0]['reviewer']['id'] = 1234
        with patch.object(owner, 'gh_json', side_effect=[env, branches, rules]):
            with self.assertRaisesRegex(owner.SetupError, 'reviewer'):
                owner.check_github('gh')

    def test_preflight_rejects_broad_branch_or_weak_checks(self):
        env, branches, rules = self.gh_policies()
        branches['branch_policies'][0]['name'] = '*'
        with patch.object(owner, 'gh_json', side_effect=[env, branches, rules]):
            with self.assertRaisesRegex(owner.SetupError, 'branch'):
                owner.check_github('gh')
        env, branches, rules = self.gh_policies()
        rules['rules'][-1]['parameters']['strict_required_status_checks_policy'] = False
        with patch.object(owner, 'gh_json', side_effect=[env, branches, rules]):
            with self.assertRaisesRegex(owner.SetupError, 'checks'):
                owner.check_github('gh')

    def test_gh_api_failure_does_not_display_response_secret(self):
        from subprocess import CalledProcessError
        with patch.object(owner.subprocess, 'run', side_effect=CalledProcessError(1, [], stderr='private test secret')):
            with self.assertRaises(owner.SetupError) as ex:
                owner.gh_json('gh', 'test')
        self.assertNotIn('private test secret', str(ex.exception))

    def test_private_key_password_is_verified_via_public_csr_and_env_only(self):
        recorded = {}
        def fake_run(args, **kw):
            recorded['args'] = args
            recorded['env'] = kw['env'].copy()
            Path(args[args.index('-file') + 1]).write_text('PUBLIC CSR FIXTURE')
        with patch.object(owner.subprocess, 'run', side_effect=fake_run):
            owner.verify_private_key('keytool', self.key, 'owner', 'STORE-PRIVATE', 'STORE-PRIVATE')
        self.assertIn('-certreq', recorded['args'])
        self.assertIn('-storepass:env', recorded['args'])
        self.assertIn('-keypass:env', recorded['args'])
        self.assertNotIn('STORE-PRIVATE', recorded['args'])
        self.assertNotIn('KEY-PRIVATE', recorded['args'])
        self.assertEqual(recorded['env']['THEKAIN_LOCAL_STOREPASS'], 'STORE-PRIVATE')
        self.assertEqual(recorded['env']['THEKAIN_LOCAL_KEYPASS'], 'STORE-PRIVATE')

    def test_pkcs12_mismatched_key_password_rejected_before_keytool(self):
        with patch.object(owner.subprocess, 'run') as child:
            with self.assertRaisesRegex(owner.SetupError, 'match'):
                owner.verify_private_key('keytool', self.key, 'owner', 'store-pass', 'wrong-key-pass')
        child.assert_not_called()

    def test_private_key_wrong_password_aborts_before_github(self):
        from subprocess import CalledProcessError
        with patch.object(owner.subprocess, 'run',
                          side_effect=CalledProcessError(1, ['keytool'], stderr=b'SENSITIVE')):
            with self.assertRaises(owner.SetupError) as ex:
                owner.verify_private_key('keytool', self.key, 'owner', 'pass', 'pass')
        self.assertNotIn('SENSITIVE', str(ex.exception))

    def test_cancelled_publish_makes_no_writes(self):
        self.one.touch(); self.two.touch()
        with patch.object(owner, 'owner_only'), patch.object(owner, 'executable', side_effect=['keytool','7z','gh']), \
             patch.object(owner, 'verify_archives'), \
             patch.object(owner, 'fingerprint', return_value='a'*64), \
             patch.object(owner, 'verify_private_key'), \
             patch.object(owner, 'check_github'), \
             patch.object(owner.getpass, 'getpass', side_effect=['store','key']), \
             patch('builtins.input', return_value='NO'), patch.object(owner, 'gh_set') as upload:
            with self.assertRaisesRegex(owner.SetupError, 'Cancelled'):
                owner.publish(self.key, 'release', self.one, self.two)
        upload.assert_not_called()


if __name__ == '__main__':
    unittest.main()