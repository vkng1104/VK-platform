# VK Platform

VK Platform is a systems-focused engineering portfolio. The monorepo contains a Next.js frontend, a Go API, and PostgreSQL.

## Applications

- `apps/web` — Next.js portfolio with project pages and a service status page.
- `apps/api` — Go HTTP API with health, project-catalog, and reusable email-verification domains.

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

## Email OTP verification

Email verification is disabled by default. When enabled, the Go API creates short-lived, purpose-bound challenges in PostgreSQL and sends six-digit codes through the Gmail API. It does not grant access to a resource by itself; a consuming feature decides what a successful verification authorizes.

Create a Google Cloud OAuth client, enable the Gmail API, and authorize the sender account once with offline access and only the `https://www.googleapis.com/auth/gmail.send` scope. Follow Google's [Gmail API authorization guide](https://developers.google.com/workspace/gmail/api/auth/web-server) to obtain a refresh token. Then set these values in the ignored `.env` file:

```dotenv
EMAIL_PROVIDER=gmail
EMAIL_FROM_ADDRESS=owner@gmail.com
GMAIL_CLIENT_ID=...
GMAIL_CLIENT_SECRET=...
GMAIL_REFRESH_TOKEN=...
EMAIL_OTP_PEPPER=replace-with-at-least-32-random-characters
EMAIL_RATE_LIMIT_SECRET=replace-with-a-different-32-character-secret
```

Use different random values for the OTP pepper and rate-limit secret. Never use the regular Gmail password, commit working credentials, or expose these variables to the frontend. Personal Gmail is intended only for the low-volume portfolio flow; the sender is isolated behind an interface so it can be replaced by a transactional provider later.

When configured, the API exposes:

```text
POST /api/v1/email-verifications
POST /api/v1/email-verifications/{id}/verify
```

The start request accepts an email and the supported `restricted_resource_access` purpose. Codes expire after five minutes, allow five attempts, enforce resend and destination/requester/global throttles, and can succeed only once.

Verification-email copy lives under `apps/api/internal/platform/mail/email_templates/`. Subject, plain-text, and HTML files use Go template fields such as `{{ .Code }}` and `{{ .ExpiresAt }}` and are embedded in the API binary at build time, so deployment does not require mounting template files.

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

The command exports the source before changing anything locally, never writes to the source database, preserves the local migration version, excludes transient email-verification challenges, and replaces the remaining local public-table data. Only use production data when you are authorized to store it locally.

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
