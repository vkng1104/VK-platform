# Repository Instructions

Keep this file small. Load the relevant repository skill for detailed workflow guidance:

- Feature implementation, commits, push, and PR handoff: `.agents/skills/feature-delivery/SKILL.md`
- Test design or test changes: `.agents/skills/bottom-up-testing/SKILL.md`
- Plans created by an AI agent: `.agents/skills/write-project-plan/SKILL.md`

## Required Rules

- Before implementing a feature, create or switch to a dedicated branch named `khanh/feat/<feature-name>`, with `<feature-name>` in lowercase kebab-case.
- Finish each feature as exactly two isolated commits unless the user explicitly requests a different split: first `feat: <concise description>` for implementation, then `test: <concise description>` for its tests.
- Preserve unrelated user changes. Stage only files that belong to the current feature or its tests.
- Apply the repository's bottom-up testing strategy. Put branch coverage at the layer that owns the behavior instead of duplicating it through high-level tests.
- When the branch is complete and verified, prepare a concise PR title and a bullet-based PR body that follows `.github/pull_request_template.md`, then push the branch to `origin`.
- Never open or merge a pull request unless the user explicitly asks. The user owns PR creation by default.
- Store every new AI-authored plan at `plans/<Mon-YYYY>/<Weekday>-<DD>-<kebab-case-purpose>.md`. Use the plan's local creation date in `Asia/Ho_Chi_Minh`.
- `plans/` and `docs/` are intentionally Git-ignored but remain available locally. Do not force-add their contents unless the user explicitly asks.
