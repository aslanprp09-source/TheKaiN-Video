# Independent verification of the original signed run #69 artifact

Refs #6. Implementation and fixture tests do **not** constitute verification of
the real artifact. Until a separately reviewed hosted execution finishes, the
official independent apksigner result is **NOT_RUN**. The existing
`android-signed-artifact-review` gate remains BLOCKED. No gate is changed here.

## Source and authority

The committed [source pins](../ci/android-verifier-source.json) identify repository
`aslanprp09-source/TheKaiN-Video`, run **43574136079 / #69**, source
`c1b8752d8eb4dfa065704edfa8b9c891168764cf`, branch `rebuild-2026-11-11`,
artifact **14105834067**, name `M6-Android-signed-INTERNAL-QA`.

The original ZIP is **13614516 bytes**, SHA-256
`3e78443334f1b1b39b7ec95ee79e3598857670f48f3906b9a0d60457296a7546`.
The public signing-certificate SHA-256 is
`2e8fbb21adb81873d257ff685e904b710f7e63df4c9ea207c6bf2c0b27cb973e`.
The five individual sizes/digests are separately pinned. Pins come from the
owner's handoff and previously recorded Actions metadata, not from a downloaded
artifact's self-description. The source API was read again on 2026-11-16:
successful run, matching artifact digest/size, `expired=false`,
`expires_at=2026-11-21T08:24:28Z`. Availability is rechecked on every execution;
this observation is not a binary/signature PASS.

## Entry point and review boundary

Use the existing registered Clean rebuild workflow, ID **414273011**, path
`.github/workflows/rewrite-ci.yml`. The Actions API currently lists it as active
and run #69 proves a previous `workflow_dispatch` on rebuild. Neither rebuild
workflow exists in archival `main`; a new branch-only workflow is therefore not
introduced and `main` is not changed. A past dispatch does not guarantee that a
future API request or the GitHub Run workflow button will be available. Confirm
the actual dispatch response and resulting run; an HTTP error is NOT_RUN.

1. Open the scoped implementation PR against rebuild. All four ordinary required
   PR checks (`ci-policy`, `windows-tests`, `android-debug`, `android-device-tests`)
   must pass. Get a fresh independent APPROVED review from **KaiN1One** on its
   exact final head. Previous PR approvals do not count.
2. Merge normally only after GitHub's strict branch rules allow it, without
   administrator bypass. Read the new rebuild HEAD. This verifier requires the
   current tip to be a merge commit associated with a merged PR into rebuild,
   with KaiN1One's latest review APPROVED on that PR's exact final head, independent
   from its author. For this initial execution, run immediately on this PR's
   merged tip. Squash/rebase or later unrelated tips must still satisfy these
   API checks; never bypass a rejected review check.
3. Dispatch **only after that review/merge** using the registered workflow:

   ```sh
   gh workflow run 414273011 --repo aslanprp09-source/TheKaiN-Video \
     --ref rebuild-2026-11-11 \
     -f verify_artifact=true -f build_packages=false -f sign_android=false
   ```

   Confirm that the new run's `head_sha` is the reviewed current tip, event is
   `workflow_dispatch`, and `android-independent-verifier` actually ran. The
   policy job still tests disposable fixtures; Windows/Android application
   builds, packaging, signing rehearsal and real signer are skipped in this
   explicit mode. Conflicting `verify_artifact` plus build/signing inputs fail
   policy. Ordinary push/PR/manual CI keeps all four required checks.
4. If the dispatch endpoint refuses the branch workflow, record the response
   without credentials and leave official verification NOT_RUN. Do not modify
   main, publish, rebuild run #69 or use another source as a substitute. Resolve
   any alternative entry point through a separately reviewed rebuild-only change.

The script also checks the effective branch rules: strict required checks from
GitHub Actions, independent last-push review, stale-review dismissal, resolved
threads, no force push/deletion and active rulesets without bypass actors. API
denial fails closed. It has no CLI option to skip review or replace the pins.

## Verification and access

The verifier uses a fresh GitHub-hosted Ubuntu 24.04 runner, preinstalled official
Android Build Tools **35.0.0**, and pinned setup-java for JDK **17**. It records
actual tool versions and captures complete apksigner, keytool and jarsigner output
as log files. A missing tool/version error fails the job; no SDK installation on
the owner's Windows system is needed.

Only `contents: read`, `actions: read`, and `pull-requests: read` are granted to
this job. PR read access proves the new independent review; no issues write,
deployments write, OIDC, signing Environment, private key, signing secret,
`secrets: inherit`, cache or artifact code execution is used. The short-lived
GITHUB_TOKEN enters only the verifier API step and is removed from tool process
environments. Checkout credentials are not persisted. API redirects are disabled;
the original artifact ZIP redirect is accepted only over HTTPS to GitHub Actions
storage, with **no Authorization forwarded**. Signed storage URLs and arbitrary
exception text are never written to the report or job output.

Before processing content, both artifact metadata and the run-specific artifact
listing must agree with the pins, source repository IDs/branch/SHA, successful
source run, availability and future retention timestamp. Download is bounded to
the pinned ZIP size. ZIP size and SHA-256 are verified on the original response
bytes, before opening it. The archive is never regenerated to prove its digest.

The ZIP must contain exactly the five pinned regular files. Duplicate names,
traversal, directories, links, unexpected entries, encrypted/unsupported ZIP
methods and excessive sizes are rejected. Files are read with explicit limits
and CRC verification, then written to controlled flat filenames. All file hashes,
manifest source/channel/records, inspection identity and canonical SHA256SUMS
must agree. APK/AAB are data inputs, never installed or executed.

APK verification runs `apksigner verify --verbose --print-certs`, requires exit
zero, exactly one signer matching the pinned certificate and valid v2/v3 schemes.
AAB must have exactly one signature block and one public certificate. Its DER
SHA-256 must match **before** import into a fresh temporary truststore. That store
contains only the pinned public certificate; its public-only password is not a
signing credential. `jarsigner -verify -strict` must exit zero with that exact
alias. No warning/strict error bits are ignored. All temporary archive contents
and truststore files are automatically removed.

## Report and Issue #6

`M6-Android-independent-verification` retains **only** `report.json`, `summary.md`
and tool logs for seven days, including failure output. The JSON contains source
pins, verifier commit/run, review proof, actual/expected ZIP and file digests,
certificate results, versions, individual PASS/FAIL/NOT_RUN statuses, safe error
codes, and overall status. A failed stage stops subsequent checks at NOT_RUN and
forces overall FAIL. Workflow/checkout/JDK startup failure before the script may
produce no JSON: the failed job is never evidence of PASS. Cancellation is also
not PASS. The script writes the same status table to the job summary.

After observing the completed real run, update Issue #6 separately with the PR,
reviewed/verifier/source commits, verifier run and report artifact link, actual
checks/digests/certificates/tool versions and any failure or NOT_RUN. The workflow
does not post comments or grant itself issues write. Do not close the issue or
mark the entire artifact-review gate PASS merely because signatures passed:
assess its full criteria and retain the remaining M6 blockers.

Physical Android (including 8–9), installation/upgrade to a higher versionCode
with preserved data, OEM/background/accessibility/download recovery, real 16 KiB
runtime, unstable UI-test investigation, clean Windows 11/Authenticode,
source/license/retention/cleanup audit and final acceptance remain outstanding.
`production_ready=false`, `automatic_publication=false` throughout.

## Tests

```sh
python -m pip install --require-hashes --only-binary=:all: --no-deps -r rewrite/ci/requirements.lock
python rewrite/ci/workflow_policy.py
python -m unittest discover -s rewrite/ci -p 'test_*.py' -v
python -m unittest discover -s rewrite/release -p 'test_*.py' -v
```

With existing SDK 35 and JDK 17 selected through `ANDROID_HOME` and `JAVA_HOME`,
the suite creates a disposable RSA PKCS12, a minimal owned APK and a JAR-format
AAB fixture. It runs real apksigner/keytool/jarsigner, rejects changed signatures,
unsigned AAB entries, other certificates and ambiguous signer blocks, and deletes
the fixture key before verification. Source/provenance/ZIP/metadata/API and report
failures, absent/failed/timed-out tools and workflow credential/permission/cache
boundaries are tested separately. Fixture runs never download or verify the
owner's run #69. The crypto class requires preinstalled tools in GitHub CI; local
environments without them explicitly SKIP it and must report that limitation.
