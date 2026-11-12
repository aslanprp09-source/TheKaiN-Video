# Rebuild CI and signing boundary

Scoped task [#13](https://github.com/aslanprp09-source/TheKaiN-Video/issues/13), following experimental M6. PR target `rebuild-2026-11-11`. Public distribution remains BLOCKED by [M6 gates](../release/qa-gates.json).

## Reviewed Actions and permissions

Every external `uses:` in `rewrite-ci.yml` and `rewrite-packaging.yml` uses a full 40-character commit SHA from an inspected stable upstream release. [actions-lock.json](../ci/actions-lock.json) records release URLs, versions, commits and the inspected Node 24 runtime. These Actions run on current GitHub-hosted Linux/Windows runners; old self-hosted runners are not a supported signing target.

| Action | Release | Commit |
|---|---|---|
| actions/checkout | v7.0.1 | `3d3c42e5aac5ba805825da76410c181273ba90b1` |
| actions/setup-python | v7.1.0 | `6d4fd22b20465ccc0f7354476588e8df7c9ca0e0` |
| actions/setup-java | v6.2.0 | `77dd5b2ce225ea11469928ac317850245a74ae78` |
| actions/upload-artifact | v7.0.2 | `cf430e030ddbb5b0abf93d22962f4752f3646cd9` |
| actions/download-artifact | v8.0.2 | `9000827ccba6bdab643e8b6fd33ac0654aef8333` |
| gradle/actions/setup-gradle | v6.4.1 | `8da46b539cd8fe6ab27ca75128919a18d757604c` |

The relative reusable-workflow reference stays `./.github/workflows/rewrite-packaging.yml`: GitHub resolves it from the **same caller commit**. It is not a remote floating ref. Archival workflows are deliberately unchanged under AGENTS.md; no main/tag/release publication path is added. Do not put signing keys in repository/organization secrets that legacy workflows could receive. Store future keys only in the protected environment.

Both active workflows default to `contents: read`; policy readers and the reusable caller additionally permit `actions: read`. No write, OIDC, blanket secret inheritance or persisted checkout credentials. Android evidence now stays entirely in the Actions artifact (42 PNGs, contact sheet and ZIP); it no longer creates Git data blobs. Windows preview annotations continue without repository write access.

The `ci-policy` job rejects floating/unreviewed Actions, duplicate YAML keys, expanded triggers/permissions, persisted credentials, secret forwarding, weakened signer dependencies, unexpected signing steps, builds/caches in the signer and cross-run artifact selection. Its parser is PyYAML 6.0.3 (MIT), wheel hashes pinned in `rewrite/ci/requirements.lock`; this is a CI-only dependency. The guard is a regression check, not a defense against someone authorized to change the workflow, lock and guard together: source review and GitHub protection remain necessary.

Updates require review of upstream release notes, the exact tag-to-commit mapping, action.yml runtime/inputs and executable/dependency changes. Update full SHAs and inventory together in a scoped PR, run policy tests and the complete unsigned CI before accepting an update. Never resolve “latest” automatically at workflow execution time. Pinning Actions does not make runner images, SDK/package downloads or every transitive dependency hermetic; those retain the M6 inventory/distribution gates.

## Signing is explicitly gated

As inspected on 12 November 2026, this repository had **no `release-signing` environment**. This change does not create an environment, choose reviewers, add secrets or create a user signing identity. An unsigned policy audit succeeds while reporting BLOCKED; a requested signing attempt fails closed when any requirement is missing or unreadable. The real protected signing job must remain skipped until owner setup and review are complete.

Before GitHub schedules that job, a separate unprivileged `signing-policy` job reads environment/ref metadata through read-only GitHub API requests. It requires:

- Exact repository `aslanprp09-source/TheKaiN-Video`, manual `workflow_dispatch`, branch `refs/heads/rebuild-2026-11-11` and one of the two reviewed rebuild workflow paths.
- `GITHUB_SHA` equal to the current rebuild branch tip. Historical/rerun commits that are no longer the tip fail closed.
- An existing `release-signing` environment with required reviewers, `prevent_self_review: true`, an independent user/team and `can_admins_bypass: false`.
- Custom deployment branch restrictions containing exactly one **branch** policy, `rebuild-2026-11-11`. Wildcards, tags, all-protected-branches, extra or incomplete/paginated policy responses are rejected.
- Explicit `sign_android=true` and successful unsigned Android/native Windows jobs. Signing defaults off.

No preflight step creates or updates environment settings. A missing environment cannot be implicitly created by the normal signing path. The protected job repeats the policy check **after environment approval and before key material is exposed**, so a changed branch tip or weakened protection is rejected. Only one signing job runs at a time; a newer request does not cancel an active signer. API denial/network failure is BLOCKED, never “assumed protected.” GitHub's actual reviewers and branch restrictions enforce the server-side boundary; do not approve historical workflows predating this hardening.

### Owner setup, before adding secrets

Review/merge the hardening PR into rebuild first. Under repository Settings → Environments, configure `release-signing`, choose independent reviewers, prevent self-review, disable administrator bypass and allow only the named rebuild branch. Required reviewer identities and the actual protection settings are owner decisions. Confirm the repository's plan supports enforcement; if not, leave signing blocked rather than reducing protection. Protect the rebuild branch/workflow changes with reviewed PRs as appropriate for the repository's maintainer setup.

Read-only checks:

```sh
gh api repos/aslanprp09-source/TheKaiN-Video/environments/release-signing
gh api repos/aslanprp09-source/TheKaiN-Video/environments/release-signing/deployment-branch-policies
```

Run the unsigned workflow on rebuild, inspect `signing-policy` output and actual GitHub protection before adding secrets. Then configure environment-only `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`; configure public `ANDROID_SIGNING_CERT_SHA256` as 64 hex characters. Alias must match `[A-Za-z0-9][A-Za-z0-9_.-]{0,127}`. Base64 is a single line. No real key/password belongs in chat, source, logs or artifacts.

Keep an encrypted offline backup under owner control; document recovery access and certificate fingerprint before first use. Rotation must follow the Android/store-supported signing lineage and validate upgrades from a previously signed version with data/identity intact. Never replace a lost production identity with an automatically generated debug key. Windows Authenticode identity/provider and its equivalent protected job remain separate, unconfigured release work.

## Data-only signing job

The unsigned job performs all Gradle compilation/tests/Lint and inspection, then uploads a dedicated input artifact. Its outputs bind the exact artifact ID, APK, AAB and manifest hashes to this run/commit. The signer downloads that ID from the current run; no caller-supplied repository, run ID, artifact name or source ref is accepted.

Before any key is decoded, `artifact_signing.py` verifies the independent job-output hashes, full source commit/channel, canonical checksums, file set/sizes, inspection flags and absence of symlinks/extra files. The manifest is not trusted to authenticate itself. The same checks run again in the secret-bearing helper. Signing runs on a fresh hosted Ubuntu job with **no Gradle, project build, build cache or test execution**.

Only the minimal signing step receives the four secrets. A private temporary directory and mode-0600 key file exist only during apksigner/jarsigner calls. Passwords are referenced through their environment variable names, never literal CLI arguments. Those tools receive only the required password variables plus public runtime settings; GitHub tokens and base64 key data are not forwarded. The key file is deleted before repository verification scripts run, and those scripts receive a sanitized environment without credentials. APK apksigner and AAB jarsigner/keytool checks must match the expected public certificate. Failure removes partial signed outputs and the temporary key directory; upload only runs after success. Cancellation/host loss relies additionally on disposal of the fresh hosted runner, with no key/cache artifact persistence.

Successful output remains **signed internal QA**, with `production_ready: false`, source manifest and hashes. Signing infrastructure readiness does not close clean Windows 11, physical Android/OEM/accessibility, staging cleanup, license/source audit or final acceptance gates. No release publishing is introduced.

## Reproducible verification

```sh
python -m pip install --require-hashes --only-binary=:all: --no-deps -r rewrite/ci/requirements.lock
python rewrite/ci/workflow_policy.py
python -m unittest discover -s rewrite/ci -p 'test_*.py' -v
gh workflow run rewrite-ci.yml --ref codex/ci-signing-hardening -f build_packages=true -f sign_android=false
```

Negative tests cover tags/main/feature/PR/fork contexts, old commits, missing/unreadable reviewers/branch policy, admin/self-review bypass, mutable/unreviewed Actions, secret scope and token expansion, duplicate YAML keys, tampered manifests/files, traversal/extra files/symlinks, wrong proof and credential cleanup.

`signing_rehearsal.py` additionally uses real unsigned APK/AAB copies and a **disposable owned CI test key**, outside the protected signer. It exercises both signing tools and actual certificate verification, deliberately rejects a wrong fingerprint, checks that unsigned inputs remain byte-identical and deletes every test key/signed copy before artifact upload. This does not configure production secrets, enroll a certificate or claim a trusted signature. Local rehearsal can use the M6 owned unsigned fixtures with `CI_UNSIGNED_INPUT`, `GITHUB_SHA` matching their manifest, `JAVA_HOME`, `ANDROID_HOME` and a writable `RUNNER_TEMP`; CI performs it on that run's fresh release outputs.

Existing desktop/debug/emulator/unsigned packaging tests still run with the pinned Actions. No test of a real user key or actual protected environment approval is claimed until owner configuration and a recorded approved run exist.
