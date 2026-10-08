---
name: write-project-plan
description: Create or update an AI-authored VK-platform implementation plan using the repository's local-only folder and date-based naming convention. Use for planning requests, not ordinary implementation notes.
---

# Write Project Plan

Store every new AI-authored plan at:

```text
plans/<Mon-YYYY>/<DD>-<Weekday>-<kebab-case-purpose>.md
```

Use the plan's creation date in `Asia/Ho_Chi_Minh`:

- `<Mon-YYYY>` uses the English three-letter month and four-digit year, such as `Oct-2026`.
- `<DD>` is the zero-padded day of the month.
- `<Weekday>` uses the full English weekday name, such as `Monday`.
- The purpose is concise lowercase kebab-case.

Keep the numeric day before the weekday in every generated filename.

For example, a caching plan created on Monday, October 5, 2026 belongs at:

```text
plans/Oct-2026/05-Monday-add-response-caching.md
```

Create the month folder when it does not exist. Update an existing plan in place when the request continues the same planned work; do not create a duplicate only because the date changed. Do not rename user-authored or pre-existing plans unless the user requests it.

The `plans/` directory is intentionally Git-ignored. Files there remain readable and editable by local agents. Do not force-add a plan to Git unless the user explicitly asks.
