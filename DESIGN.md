# VK Platform Application Design

This document defines the application-level structure for VK Platform. It combines pragmatic Atomic Design on the frontend with strict modular-monolith boundaries on the backend.

The goal is consistency without ceremony: introduce a layer or abstraction only when it owns a real responsibility.

## 1. Dependency Flow

```text
Next.js route
  -> feature API/client boundary
  -> Go HTTP handler
  -> application service
  -> domain repository interface
  -> PostgreSQL repository
```

Transport, application, domain, and persistence models are separate when their responsibilities differ. Dependencies point inward; infrastructure does not define business behavior.

## 2. Frontend Structure

Use pragmatic Atomic Design under `apps/web/src`:

```text
app/                         Next.js routes and route metadata
components/
  atoms/                     Small visual or native-control primitives
  molecules/                 Reusable combinations of atoms and content
  organisms/                 Complete page sections
  templates/                 Layout-only shells and slots
features/
  <domain>/
    api.ts                   Wire DTOs and HTTP calls
    model.ts                 UI/domain mapping when needed
    format.ts                Domain-specific presentation helpers
lib/                         Cross-domain HTTP and framework utilities
```

### Atomic responsibilities

- **Atoms** are small primitives such as badges, buttons, tags, inputs, or status indicators. They must not fetch data or import higher-level components.
- **Molecules** combine atoms into a focused reusable pattern such as a project card. They receive data and callbacks through props and do not own route-level fetching.
- **Organisms** compose atoms and molecules into a complete section such as project navigation, a featured-project grid, or a project list.
- **Templates** define layout and slots. They do not fetch domain data or contain feature-specific business rules.
- **Routes** own route params, metadata, server-side fetching, `notFound()`, redirects, and loading/error composition. Keep reusable visual markup out of route files.

Do not manufacture one-use atoms merely to satisfy the hierarchy. Extract a component when it has a coherent responsibility, reuse value, or meaningful behavior to test.

### Feature and API boundaries

- Keep backend wire types in `features/<domain>/api.ts` with the API's exact `snake_case` field names.
- Centralize base URL handling, error parsing, timeouts, and shared fetch behavior under `lib/`.
- Map wire DTOs to a separate view model only when the UI benefits from a different shape; do not let mapping logic leak across components.
- Keep domain formatting and selection logic in the feature folder rather than inside atoms or templates.

### Next.js server and client boundaries

- Components are Server Components by default.
- Add `"use client"` only at the smallest interactive boundary that needs hooks, event handlers, browser APIs, or a client-only provider.
- Server routes fetch data and pass serializable props down. Do not move an entire page to the client for one interactive child.

### Frontend conventions

- Use PascalCase component filenames and named exports.
- Name prop types `<Component>Props` and hooks `use<Behavior>`.
- Prefer semantic HTML, visible focus states, labels, useful accessible names, and keyboard-operable interactions.
- Test user-visible behavior through semantic roles and names. Avoid tests coupled to Tailwind classes or component internals.

## 3. Backend Structure

Use a Go modular monolith organized by domain:

```text
apps/api/
  cmd/server/main.go            Composition root only
  internal/
    <domain>/
      model.go                  Domain entities and value types
      dto.go                    HTTP request/response DTOs and mappings
      errors.go                 Sentinel or typed domain errors
      repository.go             Persistence implementation
      service.go                Validation and application orchestration
      handler.go                HTTP adapter and route mounting
    platform/
      database/                 PostgreSQL connection lifecycle
      httpx/                    Shared strict JSON helpers
  migrations/                  Ordered reversible SQL
```

Keep domain packages focused. Do not create a generic repository or generic service framework. Define narrow interfaces near the consumer that needs them and inject implementations through constructors.

### HTTP handler contract

Handlers are transport adapters. For every endpoint, follow this order:

1. Read path/query parameters or decode a transport request DTO.
2. Bound JSON request bodies before decoding.
3. Reject unknown JSON fields.
4. Accept exactly one JSON object and require EOF afterward.
5. Map transport DTOs to application/service requests explicitly.
6. Invoke the service; do not implement business or persistence rules in the handler.
7. Map domain errors with `errors.Is` to stable status codes and public error codes.
8. Log unexpected internal causes with request context without returning SQL or infrastructure details.
9. Map domain results to response DTOs explicitly.
10. Serialize JSON through the shared response helper.

Use a 1 MiB default maximum JSON body unless an endpoint documents a smaller bound. All public JSON fields, including nested fields and timestamps, require explicit `snake_case` tags.

Read-only endpoints still use response DTOs and explicit domain-to-transport mapping. Do not serialize repository records or domain entities directly.

### Services

- Services own input validation, business rules, ordering, and collaborator orchestration.
- Services know nothing about HTTP status codes, JSON, or concrete database clients.
- Accept application request structs and return domain/result structs.
- Return sentinel or typed domain errors and wrap underlying causes with `%w` so callers can use `errors.Is`.
- Inject clocks and external dependencies when behavior depends on them. Validate required constructor dependencies.

### Repositories and PostgreSQL

- Repositories own parameterized SQL, scans, persistence record mapping, and database error translation.
- Map `pgx.ErrNoRows` and relevant SQLSTATE values to domain errors. Never expose raw database errors through HTTP.
- Accept a transaction explicitly for atomic multi-write operations.
- Keep queries explicit and aligned with indexes; avoid generic CRUD abstractions.
- Use bounded contexts for connection startup, queries, and shutdown.

### Migrations and seed data

- Use ordered `.up.sql` and `.down.sql` migration pairs.
- Prefer UUID primary keys, plural `snake_case` table names, `TIMESTAMPTZ`, explicit foreign keys, and database-enforced `NOT NULL`, `CHECK`, `UNIQUE`, and relationship invariants.
- Add indexes for actual query shapes, not speculatively.
- Keep migrations structural. Put development/demo records in an idempotent seed file or seed command.
- Never auto-run migrations from the API process; migration and startup are separate operations.

### Composition root

`cmd/server/main.go` loads configuration, opens infrastructure, constructs repositories and services, mounts handlers, configures bounded server timeouts, and owns graceful shutdown. It must not contain SQL or business rules.

## 4. API Contract

Use versioned routes under `/api/v1`. Success responses may use a named resource envelope when returning a collection. Errors use a stable shape:

```json
{
  "code": "PROJECT_NOT_FOUND",
  "message": "The requested project was not found."
}
```

Messages are safe for clients. Logs retain the wrapped internal cause.

## 5. Testing Ownership

- **Persistence integration tests** run real repository code and migrations against PostgreSQL. They prove scans, filters, ordering, nullable values, constraints, and database-owned invariants.
- **Service tests** instantiate services with focused fakes or stubs and prove validation, business combinations, orchestration, and error propagation.
- **Handler tests** use the real router with `httptest`. They prove routing, strict decoding, DTO mapping, `snake_case` serialization, stable error mapping, and non-leaking failures.
- **Frontend feature tests** prove API URLs, wire mapping, error behavior, and any feature-owned selection or formatting.
- **Component and route tests** cover meaningful conditional UI and user flows without duplicating already-proven lower-layer branches.

Build-tag slow integration suites when they need external services. Keep fixtures isolated and cleanup deterministic. Report integration suites as not run when their required test dependency is unavailable; never silently replace PostgreSQL behavior with mocks.

## 6. Current Project Data Flow

Projects are a database-backed, read-only public catalog:

```text
Next.js Server Component
  -> projects feature API client
  -> GET /api/v1/projects or /api/v1/projects/{slug}
  -> project HTTP handler
  -> project service
  -> PostgreSQL project repository
```

The public API exposes only published projects. Administrative writes, authentication, and a CMS are separate future features. PostgreSQL is the single runtime source of truth; do not keep a second Markdown project catalog after migration.
