"""Read-only, fail-closed audit before GitHub schedules the protected signing job."""
import argparse
import json
import os
from pathlib import Path
import re
import urllib.error
import urllib.request

REPOSITORY = 'aslanprp09-source/TheKaiN-Video'
BRANCH = 'rebuild-2026-11-11'
REF = 'refs/heads/' + BRANCH
ENVIRONMENT = 'release-signing'
WORKFLOWS = {f'{REPOSITORY}/.github/workflows/{name}@{REF}'
             for name in ('rewrite-ci.yml', 'rewrite-packaging.yml')}


def assess(context, environment, branches, tip):
    errors = []
    if context.get('repository') != REPOSITORY:
        errors.append('WRONG_REPOSITORY')
    if context.get('event') != 'workflow_dispatch':
        errors.append('MANUAL_DISPATCH_REQUIRED')
    if context.get('ref') != REF or context.get('workflow_ref') not in WORKFLOWS:
        errors.append('UNTRUSTED_REF_OR_WORKFLOW')
    sha = context.get('sha', '')
    if not re.fullmatch('[0-9a-f]{40}', sha) or sha != tip:
        errors.append('CURRENT_BRANCH_TIP_REQUIRED')
    if not isinstance(environment, dict) or environment.get('name') != ENVIRONMENT:
        return errors + ['PROTECTED_ENVIRONMENT_MISSING']
    if environment.get('can_admins_bypass') is not False:
        errors.append('ADMIN_BYPASS_MUST_BE_DISABLED')
    rules = environment.get('protection_rules', [])
    reviews = [r for r in rules if r.get('type') == 'required_reviewers']
    reviewers = [v for rule in reviews for v in rule.get('reviewers', [])]
    if not reviews or any(r.get('prevent_self_review') is not True for r in reviews) or not reviewers:
        errors.append('INDEPENDENT_REVIEW_REQUIRED')
    actors = {context.get('actor'), context.get('triggering_actor')}
    independent = [v for v in reviewers if v.get('type') in ('User', 'Team') and
                   isinstance(v.get('reviewer', {}).get('id'), int) and v['reviewer']['id'] > 0 and
                   (v['type'] == 'Team' or v['reviewer'].get('login') not in actors)]
    if reviewers and not independent:
        errors.append('NO_INDEPENDENT_REVIEWER')
    if environment.get('deployment_branch_policy') != {'protected_branches': False, 'custom_branch_policies': True}:
        errors.append('EXPLICIT_BRANCH_POLICY_REQUIRED')
    policies = branches.get('branch_policies', []) if isinstance(branches, dict) else []
    if not isinstance(branches, dict) or branches.get('total_count') != len(policies) or len(policies) != 1 or \
            policies[0].get('name') != BRANCH or policies[0].get('type') != 'branch':
        errors.append('ONLY_REBUILD_BRANCH_ALLOWED')
    return errors


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


def api(path):
    token = os.environ.get('GH_TOKEN')
    if not token:
        raise RuntimeError('API_AUTH_UNAVAILABLE')
    request = urllib.request.Request('https://api.github.com/repos/' + REPOSITORY + '/' + path,
        headers={'Authorization': 'Bearer ' + token, 'Accept': 'application/vnd.github+json',
                 'X-GitHub-Api-Version': '2022-11-28', 'User-Agent': 'TheKaiN-signing-policy'})
    try:
        with urllib.request.build_opener(NoRedirect()).open(request, timeout=20) as response:
            return json.load(response)
    except urllib.error.HTTPError as error:
        if error.code == 404:
            return None
        raise RuntimeError('API_POLICY_UNREADABLE') from None
    except (OSError, ValueError):
        raise RuntimeError('API_POLICY_UNREADABLE') from None


def audit(requested):
    context = {k: os.environ.get(v, '') for k, v in {
        'repository': 'GITHUB_REPOSITORY', 'event': 'GITHUB_EVENT_NAME', 'ref': 'GITHUB_REF',
        'workflow_ref': 'GITHUB_WORKFLOW_REF', 'sha': 'GITHUB_SHA', 'actor': 'GITHUB_ACTOR',
        'triggering_actor': 'GITHUB_TRIGGERING_ACTOR'}.items()}
    try:
        if context['repository'] != REPOSITORY:
            raise RuntimeError('WRONG_REPOSITORY')
        environment = api('environments/' + ENVIRONMENT)
        branches = api('environments/' + ENVIRONMENT + '/deployment-branch-policies?per_page=100') if environment else None
        branch = api('git/ref/heads/' + BRANCH)
        tip = (branch or {}).get('object', {}).get('sha')
        errors = assess(context, environment, branches, tip)
    except (RuntimeError, TypeError, AttributeError) as error:
        errors = [str(error) if isinstance(error, RuntimeError) else 'API_POLICY_UNREADABLE']
    return {'requested': requested, 'eligible': not errors, 'authorized': requested and not errors,
            'production_ready': False, 'reasons': errors or ['PROTECTED_INTERNAL_QA_ONLY']}


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--requested', required=True, choices=('true', 'false'))
    args = p.parse_args()
    report = audit(args.requested == 'true')
    if os.environ.get('GITHUB_OUTPUT'):
        with Path(os.environ['GITHUB_OUTPUT']).open('a', encoding='utf-8') as stream:
            stream.write('authorized=' + str(report['authorized']).lower() + '\n')
    print(json.dumps(report))
    print('::notice title=Signing policy::' + ('AUTHORIZED internal QA' if report['authorized'] else 'BLOCKED: ' + ', '.join(report['reasons'])))
    return 1 if report['requested'] and not report['authorized'] else 0


if __name__ == '__main__':
    raise SystemExit(main())
