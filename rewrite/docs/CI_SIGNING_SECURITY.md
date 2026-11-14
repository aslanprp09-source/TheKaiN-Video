# Rebuild CI and signing boundary

Scoped hardening [#13](https://github.com/aslanprp09-source/TheKaiN-Video/issues/13) and Android signing fix [#6 / PR #20](https://github.com/aslanprp09-source/TheKaiN-Video/pull/20). PR target `rebuild-2026-11-11`. Public distribution remains BLOCKED by [M6 gates](../release/qa-gates.json).

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

The existing `release-signing` environment and reviewer configuration are preserved. No workflow creates an environment, adds secrets or generates a permanent signing identity. An unsigned policy audit succeeds while reporting BLOCKED; a requested signing attempt fails closed when any requirement is missing or unreadable. The first real-key internal QA signing is now verified by [run #69](https://github.com/aslanprp09-source/TheKaiN-Video/actions/runs/43574136079), after review and merge of PR #20; see the [bounded evidence record](../release/evidence/android-signing-run-69.json). This historical pass does not authorize future runs or public distribution.

Before GitHub schedules that job, a separate unprivileged `signing-policy` job reads environment/ref metadata through read-only GitHub API requests. It requires:

- Exact repository `aslanprp09-source/TheKaiN-Video`, manual `workflow_dispatch`, branch `refs/heads/rebuild-2026-11-11` and entry workflow `rewrite-ci.yml`.
- `GITHUB_SHA` equal to the current rebuild branch tip. Historical/rerun commits that are no longer the tip fail closed.
- An existing `release-signing` environment with only user `KaiN1One` (ID `355790290`) as required reviewer, `prevent_self_review: true`, an initiator different from that reviewer and `can_admins_bypass: false`.
- Custom deployment branch restrictions containing exactly one **branch** policy, `rebuild-2026-11-11`. Wildcards, tags, all-protected-branches, extra or incomplete/paginated policy responses are rejected.
- Explicit `build_packages=true`, `sign_android=true`, all four required CI checks and the complete unsigned packaging/rehearsal workflow successful. Signing defaults off.

No preflight step creates or updates environment settings. A missing environment cannot be implicitly created by the normal signing path. The protected job repeats the policy check **after environment approval and before key material is exposed**, so a changed branch tip or weakened protection is rejected. Only one signing job runs at a time; a newer request does not cancel an active signer. API denial/network failure is BLOCKED, never “assumed protected.” GitHub's actual reviewers and branch restrictions enforce the server-side boundary; do not approve historical workflows predating this hardening.

### Owner setup, before adding secrets

For the current signing setup, **do not recreate the existing environment, replace the permanent key or re-enter secrets**. The owner has confirmed PKCS12 / `PrivateKeyEntry`, two independently restored encrypted backups, four environment secrets and the public certificate variable. Run #69 separately confirmed a successful real-key protected build. Independent artifact re-verification and physical-device acceptance remain pending. The current integration cannot list environment secret metadata (HTTP 403); that integration permission does not explain the Actions `workflow_call` behavior below.

On initial setup only, configure `release-signing`, require KaiN1One, prevent self-review, disable administrator bypass and allow only the named rebuild branch. Confirm the repository's plan supports enforcement; otherwise leave signing blocked. Preserve `rebuild-reviewed-changes` with independent PR review and the four required checks.

Read-only checks:

```sh
gh api repos/aslanprp09-source/TheKaiN-Video/environments/release-signing
gh api repos/aslanprp09-source/TheKaiN-Video/environments/release-signing/deployment-branch-policies
```

Run the unsigned workflow on rebuild, inspect `signing-policy` output and actual GitHub protection before adding secrets. Then configure environment-only `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`; configure public `ANDROID_SIGNING_CERT_SHA256` as 64 hex characters. Alias must match `[A-Za-z0-9][A-Za-z0-9_.-]{0,127}`. Base64 is a single line. No real key/password belongs in chat, source, logs or artifacts.

Keep an encrypted offline backup under owner control; document recovery access and certificate fingerprint before first use. Rotation must follow the Android/store-supported signing lineage and validate upgrades from a previously signed version with data/identity intact. Never replace a lost production identity with an automatically generated debug key. Windows Authenticode identity/provider and its equivalent protected job remain separate, unconfigured release work.

## Data-only signing job

### Corrected Environment-secret boundary

At `3c760310019fdc70f086653d5fd51c72a7ce5f19`, `rewrite-ci.yml` called `rewrite-packaging.yml` without a `secrets` mapping. The reusable workflow hosted the real signing job and referenced four `secrets.*` values. In [fresh run #63 / 43428547480](https://github.com/aslanprp09-source/TheKaiN-Video/actions/runs/43428547480), policy, unsigned verification and disposable signing passed; the real step failed. The owner-provided log observation identifies all four secret-backed environment variables as empty, while the public certificate variable resolved. `artifact_signing.py` rejected this configuration before decoding/writing the key.

[GitHub's reusable-workflow documentation](https://github.com/github/docs/blob/66f5790db61fc9d07eb9b7d3525f2994cd2910b9/content/actions/how-tos/reuse-automations/reuse-workflows.md#L75) explicitly requires the caller to pass the secret name even when it exists only in the called job's environment: “If the caller workflow doesn't pass an environment secret, the secret resolves to an empty string in the reusable workflow.” Required `workflow_call.secrets` declarations check that a name was passed, not that a usable value exists. Repository/organization secrets and public configuration variables have different availability rules; environment approval alone does not declare this cross-workflow contract.

The fix keeps `android-signed-internal-qa` as a **native job in `rewrite-ci.yml`**, with `environment: release-signing`. It resolves environment secrets directly after the server-side approval; no signing credential or secret name is forwarded across `workflow_call`. The builder returns exactly five **public** outputs: authorization, same-run unsigned artifact ID, APK hash, AAB hash and manifest hash. Its completion requires policy, native Windows packaging, unsigned Android packaging and the disposable rehearsal to finish successfully. The native signer additionally waits for all four CI checks.

This avoids both blanket `secrets: inherit` and a named credential contract on a workflow that also runs builds/tests. `workflow_policy.py` rejects named/blanket forwarding, secret declarations/consumers in the reusable builder, secret access through dot/bracket/serialized contexts outside the signing step, altered output bindings and a signer moved back across the call boundary. Both workflows retain the same pinned Actions and read-only permissions. The helper's direct dispatch is unsigned-only; protected signing is accepted only through Clean rebuild. The helper is not in default `main`, so it is not a standalone Run workflow route in the current GitHub UI.

The unsigned job performs all Gradle compilation/tests/Lint and inspection, then uploads a dedicated input artifact. Its outputs bind the exact artifact ID, APK, AAB and manifest hashes to this run/commit. The signer downloads that ID from the current run; no caller-supplied repository, run ID, artifact name or source ref is accepted.

Before any key is decoded, `artifact_signing.py` verifies the independent job-output hashes, full source commit/channel, canonical checksums, file set/sizes, inspection flags and absence of symlinks/extra files. The manifest is not trusted to authenticate itself. The same checks run again in the secret-bearing helper. Signing runs on a fresh hosted Ubuntu job with **no Gradle, project build, build cache or test execution**.

Only the minimal signing step receives the four secrets. A private temporary directory and mode-0600 key file exist only during apksigner/jarsigner calls. Passwords are referenced through their environment variable names, never literal CLI arguments. Those tools receive only the required password variables plus public runtime settings; GitHub tokens and base64 key data are not forwarded. The key file is deleted before repository verification scripts run, and those scripts receive a sanitized environment without credentials. APK apksigner and AAB jarsigner/keytool checks must match the expected public certificate. Failure removes partial signed outputs and the temporary key directory; upload only runs after success. Cancellation/host loss relies additionally on disposal of the fresh hosted runner, with no key/cache artifact persistence.

AAB inspection uses `jarsigner -verify -strict` and requires exit zero. Ordinary `-verify` returns zero even for an extra unsigned ZIP entry (documented severe-warning code 16). The inspector first pins the actual public leaf certificate to the APK/expected SHA-256, then imports **only that public certificate** into an automatically removed verification trust store and specifies its alias to jarsigner. This accepts the owner's self-signed Android certificate without ignoring strict error bits, while rejecting unsigned entries, altered signed content and entries signed by another identity. This trust store contains no private key; its fixed public-only password is not a signing credential. The protected workflow guard also rejects per-step `if` and `shell` overrides, preserving the default success gate for the post-approval checks and signer.

Successful output remains **signed internal QA**, with `production_ready: false`, source manifest and hashes. Signing infrastructure readiness does not close clean Windows 11, physical Android/OEM/accessibility, staging cleanup, license/source audit or final acceptance gates. No release publishing is introduced.

## Reproducible verification

```sh
python -m pip install --require-hashes --only-binary=:all: --no-deps -r rewrite/ci/requirements.lock
python rewrite/ci/workflow_policy.py
python -m unittest discover -s rewrite/ci -p 'test_*.py' -v
gh workflow run rewrite-ci.yml --ref codex/ci-signing-hardening -f build_packages=true -f sign_android=false
```

Negative tests cover tags/main/feature/PR/fork contexts, old commits, missing/unreadable reviewers/branch policy, admin/self-review bypass, mutable/unreviewed Actions, secret scope and token expansion, duplicate YAML keys, tampered manifests/files, traversal/extra files/symlinks, wrong proof and credential cleanup.

`signing_rehearsal.py` additionally uses real unsigned APK/AAB copies and a **disposable RSA-4096 PKCS12 CI test key**, outside the protected signer. It verifies the successful output's real certificate and rejects 12 negative cases: each of four missing secrets, a nonexistent alias, damaged Base64, wrong store/key passwords, a wrong certificate fingerprint, a corrupt signed APK, a corrupt signed AAB and an unsigned entry appended to the signed AAB. Each failure must have the expected safe code, remove private staging/partial output and preserve the unsigned input byte-for-byte. The outer temporary directory removes the fixture key and all signed test copies, including successful ones. This does not use production secrets, enroll a certificate or prove owner-key signing. Local rehearsal uses `CI_UNSIGNED_INPUT`, `GITHUB_SHA` matching its manifest, `JAVA_HOME`, `ANDROID_HOME` and writable `RUNNER_TEMP`; CI uses that run's fresh release outputs. `test_android_signature.py` also exercises real JDK tools with an automatically deleted test key, independently of Android SDK availability; those tests mock only APK tools, not JAR verification.

Existing desktop/debug/emulator/unsigned packaging tests still run with the pinned Actions. No test of a real user key or actual protected environment approval is claimed until owner configuration and a recorded approved run exist.

## First real-key verification after review and merge

The first protected execution of the review/merge/dispatch/approval sequence below is complete in run #69; its real signer and certificate checks passed in Actions. Independent verification of the downloaded artifact and device/upgrade acceptance remain open. Keep the recorded source/evidence for that run; future executions require a fresh reviewed current tip and their own environment approval.

1. Independently review PR #20 and merge only into `rebuild-2026-11-11` after all four required checks pass. Keep issue #6 open. An old failed run executes old source and cannot test this fix.
2. Read the new rebuild HEAD and recheck the existing environment/rules. Keep the owner's current key, four environment secrets and public `ANDROID_SIGNING_CERT_SHA256` unchanged. Expected public certificate: `2E8FBB21ADB81873D257FF685E904B710F7E63DF4C9EA207C6BF2C0B27CB973E`.
3. Start a **new** Clean rebuild run on `rebuild-2026-11-11`, `build_packages=true`, `sign_android=true`. Confirm its `head_sha` is the reviewed current tip. No true signing dispatch is part of pre-merge testing.
4. After CI, packaging and rehearsal succeed, KaiN1One approves `release-signing` through **Review deployments**. The native signing job rechecks the current tip and protection before it accesses credentials. Inspect fixed failure codes if it fails; do not recreate the key or expose secret values.
5. Require `android-signed-internal-qa` success and artifact `M6-Android-signed-INTERNAL-QA`. Verify its manifest/checksums/source commit and APK with `apksigner verify --verbose --print-certs`. Set the public `ANDROID_SIGNING_CERT_SHA256` and rerun `rewrite/release/inspect-android.py --signed` on the downloaded signed APK/AAB using `--apk`, `--aab`, `--tools` and a fresh `--output` directory; this includes strict pinned-certificate AAB verification. Compare **both actual certificate digests** with the expected value and `inspection.json`. A plain `jarsigner -verify` exit zero alone is insufficient. Never treat an unsigned/debug artifact or its ZIP hash as signing proof.
6. Record actual protected-run and artifact evidence in #6. Installation/update with preserved data and a higher `versionCode`, physical-device QA, trusted Windows signing and the remaining M6 release gates still require separate acceptance. No publication is authorized.
