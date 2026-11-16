# Android M6 — completed download removal (internal QA)

Scope: Android only, completed downloads only. No public release and no change to the active transfer state machine.

## UX
- In Downloads and Library, each COMPLETED card has a **Remove** action.
- The confirmation offers **Remove from list only**, **Delete device file and record**, and **Cancel**.
- List-only deletes the Room row and leaves the published media file on the device. The resulting orphan is intentionally no longer shown by the app.
- File-and-record first deletes the *exact owned published output*, then deletes the Room row. Failures retain the row and show an error; deleting is irreversible.
- Active, paused, failed and canceled tasks are not removable through this new control.

## Isolation and storage safety
- The repository DELETE query is guarded by `state='COMPLETED'`.
- A mutex serializes removal requests within `AppGraph`.
- Android 10+ accepts only the expected MediaStore Downloads URI and checks the record's expected published filename, relative Downloads path, and owner package before deletion.
- Android 8–9 accepts only the expected FileProvider URI derived from the app-specific Downloads directory and a UUID-scoped filename.
- The feature requests no broad storage permissions and does not use arbitrary URLs, user-supplied paths, or external delete intents.
- File deletion happens before the database deletion; if the app terminates between the two, a completed stale row can remain and be removed from the list separately.

## Test plan
- Compose instrumentation: cancel, list-only, file-and-record actions are distinct.
- M4 integration: list-only preserves the file; file removal respects task ownership; simulated legacy app-specific path is deleted safely.
- CI: `android-debug` and `android-device-tests` must pass together with `ci-policy` and `windows-tests` on the PR.
- Still needed: real-device Android 8–9 and 10+ retest, MediaStore behavior on OEM devices, deletion failure UI, and data-preservation upgrade check.

This change does **not** implement YouTube or arbitrary video-service extraction, change the approved branch protection, use signing secrets, or mark any M6 release gate PASS.
