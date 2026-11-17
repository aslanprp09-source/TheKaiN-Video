# Android M6 — completed download removal (internal QA)

Scope: Android only, completed downloads only. No public release and no change to the active transfer state machine.

## UX
- In Downloads and Library, each COMPLETED card has a **Remove** action.
- The confirmation offers **Remove from list only**, **Delete device file and record**, and **Cancel**.
- List-only deletes the Room row and the completed task's private staging copies; it leaves the published device file unchanged. That file is intentionally no longer shown by the app.
- File-and-record deletes the *exact owned published output*, then its private staging copies, then its Room row. A storage/cleanup failure stops before the row is removed. File deletion is irreversible; if a later cleanup/database operation fails, the file can already be absent and the row retained. The error therefore asks the user to check the file rather than promising that both survived.
- A pending operation disables Open and Remove for that row. Duplicate requests serialize; an already missing record has a separate result which never claims its file was deleted.
- Active, paused, failed and canceled tasks are not removable through this new control.

## Isolation and storage safety
- The repository DELETE query is guarded by `state='COMPLETED'`.
- A tested coordinator serializes confirmed removal and terminal notification publication. A removed record cannot recreate a completed notification; notification cleanup failure cannot report an already removed row as a failed deletion. Delayed workers tolerate a missing row.
- Android 10+ accepts only the expected MediaStore Downloads item URI, completed/non-pending state, expected UUID filename, relative Downloads path and owner package. Those same properties are bound to the DELETE predicate, closing the query/delete ownership gap. A zero delete count is accepted only when a follow-up query confirms that the row is absent; inspection failure retains the history row.
- Android 8–9 accepts only the exact FileProvider URI derived from the app-specific Downloads directory and a canonical UUID filename. Links, non-regular files and unexpected URIs are rejected. Missing files support an explicit retry after interruption.
- Private staging cleanup is bounded to the exact completed UUID, numeric attempt directories, and regular `payload.part` files. It validates the full structure first, never follows symlinks, and refuses unknown content. Other UUIDs, active tasks and their recovery data remain untouched. This targeted removal does not close the broader M6 automatic retention/cleanup gate.
- The feature requests no broad storage permissions and does not use arbitrary URLs, user-supplied paths, or external delete intents.
- File deletion happens before database deletion; if the app terminates between the two, a completed stale row can remain. Repeating file-and-record is idempotent for an already missing owned output, or the user can choose list-only. Coroutine cancellation is propagated and never converted into a false failure message.

## Test plan
- JVM tests cover ordering, storage/DB failure and partial deletion, all non-completed states, duplicate requests, cancellation, terminal notification races, missing rows, private staging boundaries/links/unknown files and preservation of other tasks.
- Portable instrumentation uses owned MP4 bytes and actual MediaStore/FileProvider publication: both modes, another task's URI, missing output/retry, non-completed rows, unsafe pending/non-regular outputs, and a synthetic provider ownership-race predicate check. No third-party video source is contacted.
- Compose tests in Downloads/Library cover both choices, cancel and disabled pending controls. Native screenshots are captured under `files/removal-evidence/` and collected into the API 35 device artifact.
- The four required CI context names remain `ci-policy`, `windows-tests`, `android-debug`, `android-device-tests`. Additional hosted `android-legacy-device-tests` jobs run this portable class on **actual API 26 and API 29**, asserting the runtime API. The main emulator remains API 35. The legacy jobs skip independent verifier mode and receive no signing Environment/secret. A forced `api=28` test on API 35 alone is not claimed as an Android 8 runtime test.
- Local build/JVM/Lint results and actual CI run links/counts are recorded in PR #23 after execution. This Cloud instance has no emulator/KVM; device PASS requires the hosted runs, not compilation alone.
- Still needed: physical deletion QA on Android 8–9 and modern OEM devices, storage/permission failure UI on a phone, crash-window recovery and data-preservation upgrade. A debug APK cannot update the owner's signed installation with another certificate; a future same-key internal QA build needs its own protected approval.

## Owner's physical-device observations

Reported device: Samsung Galaxy S24 Ultra, Android 17, One UI 9.0. Owner reports normal UI and successful analysis/download of a direct MP4. The installed build/ZIP hash and detailed case evidence were not supplied; these observations do not validate PR #23's new deletion paths.

Owner reports YouTube produces “Страница слишком большая для безопасного анализа”; lord-of-the-rings.ru does not respond. These are reported symptoms, not a proved outage or supported extractor result. The current bounded native HTML/direct-media analyzer does not implement YouTube/JavaScript players/HLS/DASH. Keep response/redirect/time/security bounds. Extension research is a separate follow-up after this feature; no yt-dlp/Python/JS component is added by this PR.

## Local validation — 2026-11-16

- Debug and Release JVM reports: **47 tests each**, zero failures/errors/skips.
- `assembleDebug`, `assembleDebugAndroidTest`, `assembleRelease`, `bundleRelease`: SUCCESS; release signing configuration remained absent (unsigned internal build).
- Standalone `lintDebug` and `lintRelease` after compilation: SUCCESS. The earlier combined build/lint attempt stopped with an internal Kotlin/FIR analysis exception; it is retained in the PR validation history, with no detector disabled or baseline added.
- CI policy/signing/verifier suite: **78 tests PASS**; release manifest suite **2 PASS**; workflow policy: two workflows, six pinned Actions, zero violations; `git diff --check` passed.
- Emulator execution and screenshot inspection are not claimed from this local Cloud machine. Fresh API 26/29/35 execution is required in GitHub CI on the updated PR head; links and actual counts are recorded in PR #23.

This change does **not** implement YouTube or arbitrary video-service extraction, change the approved branch protection, use signing secrets, or mark any M6 release gate PASS.
