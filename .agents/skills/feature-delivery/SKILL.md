---
name: feature-delivery
description: Deliver a repository feature through the required branch, isolated implementation and test commits, verification, remote push, and concise PR handoff. Use whenever implementing or finalizing a feature in VK-platform.
---

# Feature Delivery

## Start the Feature

1. Inspect the current branch and working tree. Preserve unrelated changes and never include them in feature commits.
2. Derive a short lowercase kebab-case feature name from the requested behavior.
3. Before the first implementation edit, create or switch to `khanh/feat/<feature-name>`.
4. Do not reuse a completed feature branch for unrelated work.

## Implement and Commit

Produce exactly two feature-specific commits unless the user explicitly requests a different split:

1. Implement the feature and run relevant existing checks. Commit only the implementation files with `feat: <concise description>`.
2. Add or update unit and integration tests according to `.agents/skills/bottom-up-testing/SKILL.md`. Commit only test code, fixtures, and test-only configuration with `test: <concise description>`.

Use explicit paths when staging. If a necessary file contains unrelated user edits, stop before staging it and ask how the user wants the overlap handled.

## Finalize the Branch

1. Run the focused tests for the changed behavior, followed by the broader relevant suite when practical.
2. Inspect the final diff and recent commits. Confirm that the branch contains the isolated `feat:` commit followed by the isolated `test:` commit and that no unrelated files are included.
3. Prepare a concise PR title using the dominant Conventional Commit type, normally `feat: <concise description>`.
4. Fill `.github/pull_request_template.md` without removing its sections:
   - Use short bullet points under `Why`, `What`, and `Solution`.
   - Check every applicable change-type box and leave the rest unchecked.
   - Under `Test Plan`, name what changed, list each unit or integration command, and state `Passed locally`, `Failed`, or `Not run` with a short reason.
   - If manual verification is required, list the Postman, browser, CLI, or other steps and include `Evidence: <!-- Attach image/video here -->`.
   - If automated tests are sufficient, do not invent manual steps or an evidence placeholder.
   - Under `Related Issues`, link relevant tickets, PRs, or commits using forms such as `Related to #123`, `Follows #123`, or `Follows commit <hash>`. Use `None` when there is no relation.
5. Push the ready branch with `git push -u origin khanh/feat/<feature-name>`.
6. Give the user the PR title and completed PR body. Do not run any command that opens or merges a PR unless the user explicitly requests it.
