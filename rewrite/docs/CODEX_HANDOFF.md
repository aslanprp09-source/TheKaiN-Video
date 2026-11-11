# Handoff to Codex — TheKaiN Video

This file prepares implementation work; it does not itself create or launch a Codex session.

Repository: https://github.com/aslanprp09-source/TheKaiN-Video
Active branch: rebuild-2026-11-11
Implementation directory: rewrite/
Old main branch / existing releases: archival, do not touch.

Owner split:
- ChatGPT project "TheKaiN Video Downloader" — interface, architecture, prioritization, acceptance.
- Codex — source changes, debugging, automated tests, scoped PRs, build documentation.
- User — product direction and approval of releases.

Read first: root AGENTS.md → PRODUCT_UX_V1.md → SYSTEM_ARCHITECTURE_V1.md → ACCEPTANCE_MATRIX.md → RESEARCH.md → current prototype code.

How to start:
1. Open Codex app or cloud at https://chatgpt.com/codex and connect/select this same GitHub repository. Add it to a Codex environment with permissions to work in the repo.
2. Select branch rebuild-2026-11-11 as the base of a task, where supported.
3. Copy the starter task below into Codex. Work on a separate task branch and submit PR to rebuild-2026-11-11.
4. Keep ChatGPT Project as design source; Codex history is separate, shared code and decisions live in GitHub. No direct insertion of Codex chat into a ChatGPT Project is assumed.

Starter task to copy:
Read AGENTS.md and all four rewrite/docs design/handoff files. Work only on the earliest open [Codex/M1] GitHub issue on branch rebuild-2026-11-11. Preserve legacy main and releases. Implement only that issue's acceptance criteria, with offline deterministic tests and explicit failure reporting; use authorized test fixtures. Run Windows unit tests and relevant Android Gradle build. Create a task-specific branch and pull request against rebuild-2026-11-11. Include a concise summary, the exact tests run, screenshots if UI, and limitations. Do not claim verified device performance without device tests.

Official setup help: https://help.openai.com/en/articles/20001545-using-codex-cloud
Codex and ChatGPT Work/Project are separate experiences: https://help.openai.com/en/articles/20001275-chatgpt-work-and-codex

Working principle: one GitHub issue → one Codex task → one PR → tested review → merge on approval. Never autonomously merge into main or publish a new public release.
