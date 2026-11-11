# Instructions for Codex

Active project: TheKaiN Video clean rebuild. Active directory: rewrite/. Base branch: rebuild-2026-11-11. Legacy code, the main branch, tags and releases are archival and MUST NOT be modified without explicit approval.

## Ownership
- UX, architecture and product specifications are documented by the ChatGPT project "TheKaiN Video Downloader". They are design requirements.
- Codex is intended to implement code and tests when a Codex task is started by the user. This file does not itself launch any agent.
- Read rewrite/docs/CODEX_HANDOFF.md, PRODUCT_UX_V1.md, SYSTEM_ARCHITECTURE_V1.md and ACCEPTANCE_MATRIX.md before edits.

## Coding rules
- Code changes only under rewrite/, unless a CI or test file outside it is explicitly part of the current task.
- One scoped GitHub issue per implementation PR. Target PRs at rebuild-2026-11-11, never main.
- UI logic must be separate from extractor/transport/verification/storage; avoid blocking the UI thread.
- No fabricated media qualities, sizes, speed or completed states. 100% transfer does not equal a verified completed download.
- Do not fake pause/resume for backends that lack it, particularly Android DownloadManager.
- Persist task state, resume defensively, use temporary staging and verify outputs before success on production-ready paths.
- Do not bypass DRM or access controls, scrape cookies or user credentials, or use embedded secrets. Redact sensitive URL/query tokens in logs.
- Respect site terms, download only authorized materials, and do not promise universal support.
- Prefer testable modules with typed errors and observable state; no shell=True with user input.
- Use Russian visible text initially, English localization-ready keys, system typography and accessibility labels.
- Document dependency licenses and versions before packaging. Do not run unknown downloaded binaries.

## Baseline commands
Windows tests: python -m unittest discover -s rewrite/windows -p "test_*.py" -v
Android debug: cd rewrite/android && gradle --no-daemon :app:assembleDebug
CI: .github/workflows/rewrite-ci.yml

## Definition of done
For each task: code + automated tests + CI evidence + reproducible manual steps; screenshots for UI; authorized media fixtures for extractor; explicit limitations; PR against the rebuild branch. Only claim actual verified behavior. Real-device testing and signed distribution are separate release requirements.
