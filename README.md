# VK Platform

VK Platform is a systems-focused engineering portfolio. The monorepo contains a Next.js frontend, a Go API, and PostgreSQL.

## Applications

- `apps/web` — Next.js portfolio with project pages and a service status page.
- `apps/api` — Go HTTP API with the platform health endpoint and database-backed project catalog.

## Requirements

- Docker with Docker Compose
- Node.js 22+ and npm 10+ for host-side checks
- Go 1.25+ only when running the API or its checks outside Docker

## Local development

Create the local environment file once:

```bash
cp .env.example .env
```

Reset the Docker database, migrate it, load the idempotent development seed, and run the frontend and API:

```bash
make dev-reset
```

For later starts, keep the current database data while applying any new migrations and running both applications:

```bash
make dev
```

The frontend is available at `http://localhost:3000`, the API at `http://localhost:8080`, and PostgreSQL at `localhost:5434` by default. Use `make down` to stop the stack and `make logs` to follow its logs.

The API never runs migrations automatically. Compose runs the migration command as a separate one-shot process before application startup.

## Seed local data from another environment

Set one or both read-only source URLs in the ignored `.env` file:

```dotenv
SEED_DEVELOPMENT_DATABASE_URL=postgres://...
SEED_PRODUCTION_DATABASE_URL=postgres://...
```

Then copy the selected environment's application data into the local Docker database:

```bash
make db-seed-local SOURCE_ENV=development
make db-seed-local SOURCE_ENV=production
```

The command exports the source before changing anything locally, never writes to the source database, preserves the local migration version, and replaces all other local public-table data. Only use production data when you are authorized to store it locally.

## Verification

```bash
make lint
make typecheck
make test
make test-integration
make build
```

`make test-api` builds the API Dockerfile's `test` stage. `make test-integration` runs the same Dockerfile's `integration-test` stage against an isolated tmpfs PostgreSQL database.

## CI behavior

- `.github/workflows/tests.yml` runs frontend, backend unit, and backend integration tests for pull requests targeting `master`, including every pushed PR commit. It intentionally does not run after merge.
- `.github/workflows/lint.yml` runs frontend lint/type-checking and backend formatting/vet checks for pull requests targeting `master` and pushes to `master`.

Configure the repository's `master` branch protection to require the PR test and lint checks before merging.

## Go module layout

The repository has one Go module at `apps/api`, so a root `go.work` workspace is unnecessary. All repository commands enter that module explicitly, and root `go.work`/`go.work.sum` files are ignored to prevent editor or local-tool regeneration from dirtying the working tree.
