# VK Platform

VK Platform is a systems-focused engineering portfolio. The monorepo contains a Next.js frontend, a Go API, and PostgreSQL.

## Applications

- `apps/web` — Next.js portfolio with project, public experience, gated CV, and service status pages.
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

The frontend is available at `http://localhost:3000`, public experience at `http://localhost:3000/experience`, verified CV access at `http://localhost:3000/cv`, the API at `http://localhost:8080`, and PostgreSQL at `localhost:5434` by default. In a second terminal, use `make dev-monitor` to follow only backend logs while testing, `make logs` to follow every service, or `make down` to stop the stack.

The API never runs migrations automatically. Compose runs the migration command as a separate one-shot process before application startup.

## API documentation

The Go API serves its OpenAPI 3.1 contract and a self-contained Swagger UI from the same binary:

- Swagger UI: `http://localhost:8080/api/docs/`
- OpenAPI YAML: `http://localhost:8080/api/docs/openapi.yaml`

The contract describes the complete public API. Email-verification routes appear in the documentation even when that feature is disabled by local server configuration. Swagger UI permits `Try it out` only for GET operations; mutating email-verification requests must be sent deliberately with another HTTP client after the feature is configured.

Update `apps/api/internal/platform/apidocs/openapi.yaml` and its contract tests whenever a public handler contract changes.

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

## Experience and verified CV access

`/experience` renders approved public professional content from `internal/content/cv/public/profile.json`. The `/cv` route never stores, renders, proxies, or downloads the CV document. It reveals a configured public Google Drive URL only after the visitor completes the reusable email OTP flow.

Configure these server-only values in the ignored `.env` file:

```dotenv
CV_ACCESS_SECRET=replace-with-at-least-32-random-characters
CV_GOOGLE_DRIVE_URL=https://drive.google.com/file/d/your-public-file-id/view
```

`CV_GOOGLE_DRIVE_URL` must use HTTPS and a supported `drive.google.com/file/d/...` or `drive.google.com/open?id=...` form. Never prefix either variable with `NEXT_PUBLIC_`, commit the real Drive URL, or include it in public content. The signed access cookie expires after 30 minutes and contains no email address, OTP, Drive URL, or personal data.

After verification, the dialog exposes Copy and Open controls plus an optional link to VirusTotal's URL scanner. The application does not automatically submit the CV URL to VirusTotal or any other third party.

## API error contract

Every API response includes a server-generated `X-Request-ID`. Public JSON errors repeat that value so a user can provide it when asking for support:

```json
{
  "code": "INVALID_OR_EXPIRED_CODE",
  "message": "The verification code is invalid or expired. Request a new code and try again.",
  "request_id": "0a1b2c3d4e5f67890123456789abcdef",
  "retryable": false
}
```

Clients must branch on the stable `code`, not the human-readable `message`. Validation failures can also include a `fields` object. Internal database, email-provider, and configuration details are logged with the same request ID but are never returned to clients.

Email verification uses these public errors:

| HTTP | Code | Client action |
| --- | --- | --- |
| 400 | `INVALID_REQUEST_BODY` | Send one valid JSON request object. |
| 400 | `INVALID_EMAIL` | Correct the email address. |
| 400 | `INVALID_VERIFICATION_PURPOSE` | Use a supported verification purpose. |
| 400 | `INVALID_VERIFICATION_ID` | Start a new verification request. |
| 400 | `INVALID_CODE_FORMAT` | Enter exactly six digits. |
| 422 | `INVALID_OR_EXPIRED_CODE` | Request a new code and try again. Missing, incorrect, expired, used, and attempt-exhausted challenges intentionally share this response. |
| 429 | `EMAIL_VERIFICATION_RATE_LIMITED` | Wait for the `Retry-After` period. |
| 503 | `EMAIL_DELIVERY_UNAVAILABLE` | Retry later. |
| 500 | `INTERNAL_ERROR` | Retry later or provide `request_id` to support. |

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
