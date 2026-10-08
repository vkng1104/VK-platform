# VK Platform

VK Platform is a systems-focused engineering portfolio. The monorepo contains a Next.js frontend, an independently deployable Go resource API, a Java IAM service, and service-owned PostgreSQL databases.

## Applications

- `apps/web` — Next.js portfolio with project, public experience, gated CV, and service status pages.
- `apps/api` — Go resource API with health and project-catalog domains.
- `apps/iam` — Java 25/Spring Boot IAM service with health and reusable email-verification domains.

## Requirements

- Docker with Docker Compose
- Node.js 22+ and npm 10+ for host-side checks
- Go 1.25+ only when running the resource API or its checks outside Docker
- Java 25 only when running the IAM service or its checks outside Docker

## Local development

Create the local environment file once:

```bash
cp .env.example .env
```

Reset both Docker databases, migrate them, load the resource API's idempotent development seed, and run the frontend and services:

```bash
make dev-reset
```

For later starts, keep the current database data while applying migrations and running all applications:

```bash
make dev
```

The frontend is available at `http://localhost:3000`, public experience at `http://localhost:3000/experience`, verified CV access at `http://localhost:3000/cv`, the resource API at `http://localhost:8080`, and IAM Service at `http://localhost:8081`. Their PostgreSQL databases are exposed at `localhost:5434` and `localhost:5436` by default. In a second terminal, use `make dev-monitor` to follow both backend services, `make logs` to follow every container, or `make down` to stop the stack.

Neither service runs migrations automatically. Compose runs each service's migration command as a separate one-shot process before application startup.

Both backend services keep PostgreSQL credentials separate from their connection URLs. The Go resource API uses `DATABASE_URL`, `DATABASE_USERNAME`, and `DATABASE_PASSWORD`; IAM uses the equivalent `IAM_DATABASE_*` variables. Local examples are defined in `.env.example`, while deployed passwords should come from the platform's secret store rather than a committed environment file.

## API documentation

Each backend service serves its own OpenAPI 3.1 contract and Swagger UI:

- Resource API Swagger UI: `http://localhost:8080/api/docs/`
- Resource API OpenAPI YAML: `http://localhost:8080/api/docs/openapi.yaml`
- IAM Swagger UI: `http://localhost:8081/api/docs/`
- IAM OpenAPI YAML: `http://localhost:8081/api/docs/openapi.yaml`

Each contract describes only the routes owned by that service. IAM documents email-verification routes even when delivery is disabled by local configuration. Swagger UI permits `Try it out` only for GET operations; mutating email-verification requests must be sent deliberately with another HTTP client after the feature is configured.

Update the owning service's OpenAPI YAML and contract tests whenever a public handler contract changes.

## Email OTP verification

Email verification is disabled by default. When enabled, the IAM Service creates short-lived, purpose-bound challenges in its PostgreSQL database and sends six-digit codes through the Gmail API. It does not grant access to a resource by itself; a consuming feature decides what a successful verification authorizes.

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

When configured, the IAM Service exposes:

```text
POST /api/v1/email-verifications
POST /api/v1/email-verifications/{id}/verify
```

The start request accepts an email and the supported `restricted_resource_access` purpose. Codes expire after five minutes, allow five attempts, enforce resend and destination/requester/global throttles, and can succeed only once.

Verification-email copy lives under `apps/iam/src/main/resources/mail/`. Subject, plain-text, and HTML templates use `{{CODE}}` and `{{EXPIRES_AT}}` placeholders and are packaged in the IAM application, so deployment does not require mounting template files.

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

The command exports the source before changing anything locally, never writes to the source database, preserves the local resource-database migration version, excludes the legacy transient email-verification table, and replaces the remaining local public-table data. IAM data is not copied. Only use production data when you are authorized to store it locally.

## Verification

```bash
make lint
make typecheck
make test
make test-integration
make build
```

`make test-api` and `make test-iam` build each service Dockerfile's `test` stage. `make test-integration` runs both integration-test stages against isolated service-owned tmpfs PostgreSQL databases.

## CI behavior

- `.github/workflows/tests.yml` runs frontend, API/IAM unit, setup, and multi-service integration tests for pull requests targeting `master`, including every pushed PR commit. It intentionally does not run after merge.
- `.github/workflows/lint.yml` runs frontend lint/type-checking, API formatting/vet checks, and an IAM compile check for pull requests targeting `master` and pushes to `master`.

Configure the repository's `master` branch protection to require the PR test and lint checks before merging.

## Backend module layout

The resource API remains an independent Go module under `apps/api`. IAM is an independent Gradle project under `apps/iam` using Java 25, Spring Boot, Spring Security, Spring Modulith, JPA/Hibernate, Flyway, and PostgreSQL. Make, Docker, and CI enter each application explicitly.
