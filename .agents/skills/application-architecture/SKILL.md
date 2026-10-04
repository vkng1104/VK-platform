---
name: application-architecture
description: Apply VK-platform frontend Atomic Design and Go modular-monolith boundaries when implementing or reviewing application code, APIs, persistence, migrations, or UI component structure.
---

# Application Architecture

Read the repository-root `DESIGN.md` completely before changing frontend or backend application structure.

Apply its boundaries to the current task:

- Classify reusable frontend UI as atoms, molecules, organisms, or templates; keep route orchestration and feature API code outside those visual layers.
- Keep Next.js components server-side by default and isolate the smallest necessary client boundary.
- Organize Go code by domain with explicit handler, DTO, service, repository, model, and error responsibilities.
- Make handlers decode bounded input, reject unknown fields, map DTOs, invoke services, map domain errors, and emit explicit `snake_case` response DTOs.
- Keep SQL and database error mapping in repositories, business behavior in services, and construction in the command entrypoint.
- Use reversible constrained migrations and real PostgreSQL integration tests for persistence behavior.

Refactor nearby code when it violates these boundaries and the refactor is necessary for the requested feature. Do not reorganize unrelated domains merely for visual consistency.
