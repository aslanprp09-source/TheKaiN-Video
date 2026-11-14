from copy import deepcopy
import unittest
from unittest.mock import patch
import os
from signing_policy import audit
from signing_policy import assess, REPOSITORY, REF, BRANCH


def fixture():
    context = {'repository': REPOSITORY, 'event': 'workflow_dispatch', 'ref': REF,
        'workflow_ref': REPOSITORY + '/.github/workflows/rewrite-ci.yml@' + REF,
        'sha': 'a' * 40, 'actor': 'builder', 'triggering_actor': 'builder'}
    env = {'name': 'release-signing', 'can_admins_bypass': False,
        'deployment_branch_policy': {'protected_branches': False, 'custom_branch_policies': True},
        'protection_rules': [{'type': 'required_reviewers', 'prevent_self_review': True,
                             'reviewers': [{'type': 'User', 'reviewer': {'id': 355790290, 'login': 'KaiN1One'}}]}]}
    branches = {'total_count': 1, 'branch_policies': [{'name': BRANCH, 'type': 'branch'}]}
    return context, env, branches, 'a' * 40


class SigningPolicyTest(unittest.TestCase):
    def test_unsigned_audit_reports_blocked_without_authorizing_or_failing_the_build(self):
        context, _, _, tip = fixture()
        env = {'GITHUB_REPOSITORY': context['repository'], 'GITHUB_SHA': tip, 'GITHUB_REF': REF,
               'GITHUB_WORKFLOW_REF': context['workflow_ref'], 'GITHUB_EVENT_NAME': context['event']}
        for requested in (False, True):
            with patch.dict(os.environ, env), patch('signing_policy.api', side_effect=[None, {'object': {'sha': tip}}]):
                report = audit(requested)
                self.assertFalse(report['authorized'])
                self.assertIn('PROTECTED_ENVIRONMENT_MISSING', report['reasons'])

    def test_unreadable_protection_is_not_assumed_safe(self):
        with patch.dict(os.environ, {'GITHUB_REPOSITORY': REPOSITORY}), \
                patch('signing_policy.api', side_effect=RuntimeError('API_POLICY_UNREADABLE')):
            self.assertEqual(audit(True)['reasons'], ['API_POLICY_UNREADABLE'])

    def test_valid_manual_current_rebuild_with_independent_protection(self):
        self.assertEqual(assess(*fixture()), [])

    def test_missing_environment_never_authorizes_implicit_creation(self):
        context, _, branches, tip = fixture()
        self.assertIn('PROTECTED_ENVIRONMENT_MISSING', assess(context, None, branches, tip))

    def test_main_feature_tag_pr_fork_event_and_old_tip_are_rejected(self):
        for field, value in [('ref', 'refs/heads/main'), ('ref', 'refs/heads/codex/test'),
            ('ref', 'refs/tags/rebuild-2026-11-11'), ('ref', 'refs/pull/14/merge'),
            ('event', 'push'), ('event', 'pull_request_target'), ('repository', 'fork/TheKaiN-Video'),
            ('workflow_ref', REPOSITORY + '/.github/workflows/unreviewed.yml@' + REF),
            ('workflow_ref', REPOSITORY + '/.github/workflows/rewrite-packaging.yml@' + REF), ('sha', 'b' * 40)]:
            with self.subTest(field=field, value=value):
                context, env, branches, tip = fixture()
                context[field] = value
                self.assertTrue(assess(context, env, branches, tip))

    def test_no_review_self_review_and_admin_bypass_fail_closed(self):
        for change in ('no-review', 'self-review', 'admin', 'unknown-admin', 'only-initiator'):
            context, env, branches, tip = fixture()
            if change == 'no-review': env['protection_rules'] = []
            if change == 'self-review': env['protection_rules'][0]['prevent_self_review'] = False
            if change == 'admin': env['can_admins_bypass'] = True
            if change == 'unknown-admin': del env['can_admins_bypass']
            if change == 'only-initiator': env['protection_rules'][0]['reviewers'][0]['reviewer']['login'] = 'builder'
            with self.subTest(change=change): self.assertTrue(assess(context, env, branches, tip))

    def test_only_kain1one_can_be_the_configured_independent_approver(self):
        for change in ('different-login', 'different-id', 'extra-reviewer', 'team', 'initiator', 'rerun-initiator'):
            context, env, branches, tip = fixture()
            reviewers = env['protection_rules'][0]['reviewers']
            if change == 'different-login': reviewers[0]['reviewer']['login'] = 'other-reviewer'
            if change == 'different-id': reviewers[0]['reviewer']['id'] = 202
            if change == 'extra-reviewer': reviewers.append({'type': 'User', 'reviewer': {'id': 202, 'login': 'other-reviewer'}})
            if change == 'team': reviewers[0]['type'] = 'Team'
            if change == 'initiator': context['actor'] = 'KaiN1One'
            if change == 'rerun-initiator': context['triggering_actor'] = 'KaiN1One'
            with self.subTest(change=change): self.assertTrue(assess(context, env, branches, tip))

    def test_wildcards_tags_extra_or_truncated_branch_policy_fail_closed(self):
        for variant in ('wildcard', 'tag', 'extra', 'truncated', 'all-protected'):
            context, env, branches, tip = fixture()
            if variant == 'wildcard': branches['branch_policies'][0]['name'] = '*'
            if variant == 'tag': branches['branch_policies'][0]['type'] = 'tag'
            if variant == 'extra': branches['branch_policies'].append({'name': 'main', 'type': 'branch'}); branches['total_count'] = 2
            if variant == 'truncated': branches['total_count'] = 101
            if variant == 'all-protected': env['deployment_branch_policy'] = {'protected_branches': True, 'custom_branch_policies': False}
            with self.subTest(variant=variant): self.assertTrue(assess(context, env, branches, tip))
