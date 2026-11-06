---
name: change-walkthrough
description: Walk through a VK-platform feature, branch, or PR with one concrete end-to-end example - a Mermaid sequence diagram, numbered steps citing file:line, and one state table per database table (for example iam_session.*, iam_identity.*) showing each record at each step with changed values in bold. Use whenever the user asks to walk through, trace, or explain a branch, feature, or change in this repository, asks for "a full example" or "step by step", or wants to see how rows chain across tables. Not for bug-hunting reviews or PR write-ups.
---

# Change Walkthrough (VK-platform)

This is the repository's version of the walkthrough format, and it is self-contained. If a personal `change-walkthrough` skill is also loaded, follow this one inside VK-platform: it adds where facts live in this codebase.

The goal is to replay one realistic story through the change, so the reader can predict what the code does and what every affected table holds after each step. Use one set of actors with clock times, cite the code behind every claim, and show one state table per database table.

## Where facts live in this repository

- **The change**
  - The base branch is `master`, and feature branches are named `khanh/feat/<name>`.
  - A branch under review may have its pushed commits reset into the working tree. In that case, diff the working tree against `master`, and find the pushed tip with `git reflog` or `origin/<branch>`.
- **Schema**
  - Flyway migrations live in `apps/iam/src/main/resources/db/migration/V*__*.sql` (IAM) and `apps/api/migrations/` (Go API).
  - The generated `apps/<service>/dbdiagram/*.dbml` files list every column, index, and CHECK constraint of the current schema. Choose table columns from them, and cite constraints by name.
- **Request flow**
  - Order each step's citations along the flow in `DESIGN.md`: controller → application service → port → infrastructure adapter.
  - Cross-module effects go through events. Follow listeners such as `@TransactionalEventListener` and note their phase: `BEFORE_COMMIT` runs inside the same transaction.
- **Framework behavior** (Spring Security / Authorization Server, Spring Data, Hibernate)
  - Check the version in `apps/<service>/build.gradle.kts`.
  - Read that version's sources jar instead of relying on memory: `~/.gradle/caches/modules-2/files-2.1/<group>/<artifact>/<version>/*/<artifact>-<version>-sources.jar`, opened with `unzip -p <jar> <path/Class.java>`.
- **Config values** that shape the story (TTLs, flags): `apps/iam/src/main/resources/application.yml` and `.env.example`.
- **Dates**: use clock times in the story. If you write dates, use `Asia/Ho_Chi_Minh`.

## Before writing

1. Read the schema of every table the flow touches.
2. Trace each flow end to end and note every write and every check that can reject the request.
3. Collect the config values the story depends on.
4. When expected and actual behavior differ, show both. Label the actual behavior "suspected, not run" unless a test or a run shows it.

Then design the story:

- Use names and clock times that make durations obvious.
- Order the scenes: setup → normal use → each new behavior → its failure or abuse path.
- Give every record a short label (`S1`, `A1`, `R1`) and every secret or hash a placeholder (`#RT1`). Use the same labels everywhere.

## Output format

Use this structure:

    ## Example: <one-line scenario>

    <2–4 sentences: the actors, what exists before step 1, and the config values that matter.>

    ### Sequence

    ```mermaid
    sequenceDiagram
        participant B as Alice's BFF
        participant F as Framework /oauth2/*
        participant C as IAM code
        participant DB as Postgres
        B->>F: 1. POST /oauth2/token
        F->>C: findByToken(...) then save(...)
        C->>DB: UPDATE A1 · INSERT R1
    ```

    ### Steps

    **Step 1 — <name> (09:00).** Trigger, then checks, then writes, one claim per sentence, each cited like [JpaOAuth2AuthorizationService.java:164](apps/iam/src/main/java/.../JpaOAuth2AuthorizationService.java:164).
    Writes: A1 updated, R1 created. (Or: Writes: none, and why.)

    ### Tables

    <one table per database table, in the order the data flows>

    ### What the example shows

    ### Gaps found while tracing

Rules:

- **Sequence diagram**
  - Write Mermaid `sequenceDiagram`, prefixing each message with its step number instead of using `autonumber`.
  - Keep to about six participants.
- **Steps**
  - Cite `path:line` for repository code, migration or constraint names for schema rules, and `Class.method()` for framework code.
  - End each step with a `Writes:` line.
- **Tables**
  - Add one row per record per step at which the record was created or changed, labelled `<record> @ step N`, with 🆕 on creation.
  - Put a value in **bold** when it changed since that record's previous row. Show unchanged values in full and not bold.
  - End each row with a `Changed` column that names the changed columns, or says `created`.
  - Include only the columns the story needs, about eight at most, and list the omitted columns under each table.
  - Write labels instead of UUIDs, clock times instead of full timestamps, and `NULL` explicitly.

Example:

| sessions | status | last_refreshed_at | revoked_at | version | Changed |
|---|---|---|---|---|---|
| 🆕 S1 @ step 0 | active | 08:59 | NULL | 0 | created |
| S1 @ step 3 | active | **09:05** | NULL | **1** | last_refreshed_at, version |
| S1 @ step 5 | **compromised** | 09:05 | **09:12** | **2** | status, revoked_at, version |

- **Closing sections**
  - **What the example shows**: 3–5 bullets naming the rules the story demonstrated.
  - **Gaps found while tracing**: each place where the code seems to differ from its intent, with a citation and the label "suspected, not run".

## Saving and follow-ups

- Reply in chat by default.
- To keep a copy, save it as `docs/<service>-<topic>-walkthrough.md`, for example `docs/iam-refresh-token-rotation-walkthrough.md`. `docs/` is Git-ignored on purpose, so never force-add it.
- If the branch has review notes (`docs/*-pr-review.md`), list any suspected issues found while tracing and offer to add them there. Don't edit the notes unasked.
