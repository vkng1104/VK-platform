---
name: application-architecture
description: Apply VK-platform frontend Atomic Design and backend (Go and Java) modular-monolith boundaries when implementing or reviewing application code, APIs, services, repositories, persistence, migrations, or UI component structure.
---

# Application Architecture

Read the repository-root `DESIGN.md` completely before changing frontend or backend application structure.

Apply its boundaries to the current task:

- Classify reusable frontend UI as atoms, molecules, organisms, or templates; keep route orchestration and feature API code outside those visual layers.
- Keep Next.js components server-side by default and isolate the smallest necessary client boundary.
- Organize Go code by domain with explicit handler, DTO, service, repository, model, and error responsibilities.
- Make handlers decode bounded input, reject unknown fields, map DTOs, invoke services, map domain errors, and emit explicit `snake_case` response DTOs.
- Keep SQL and database error mapping in repositories, business behavior in services, and construction in the command entrypoint or configuration.
- Scope each repository to one table. Its methods only read, lock, insert, update, or delete that table's rows, using values the caller passes in. It never writes another table, never calls another repository, and never decides timestamps, reasons, statuses, defaults, or corrections such as clamps.
- Put those decisions in the application service, along with any change that spans tables: the service calls one repository per table inside one transaction. A `*Repository` named after an action instead of a stored thing is a sign its logic belongs in a service. When reviewing, flag any repository adapter that injects more than one table's persistence type or contains business branching.
- Use reversible constrained migrations and real PostgreSQL integration tests for persistence behavior.

Refactor nearby code when it violates these boundaries and the refactor is necessary for the requested feature. Do not reorganize unrelated domains merely for visual consistency.

## Agent Budget

Agents cost the user money, so keep their number small:

- Use at most 2-3 spawned agents for the whole task, across planning, implementation, testing, and review. Subagents, forks, parallel sessions, and workflow agents all count.
- Do quick searches, reads, and small edits yourself instead of handing them to another agent.
- Keep this budget even when a plan or another skill suggests more agents.
- Exceed three only when more agents would clearly finish the work meaningfully faster at a reasonable cost, and state that reason in one line before starting them.
