# VK Platform

VK Platform is a systems-focused engineering portfolio. The repository starts as a small monorepo with a Next.js frontend and a Go API.

## Applications

- `apps/web` — Next.js portfolio with project pages and a service status page.
- `apps/api` — Go HTTP API with the platform health endpoint and database-backed project catalog.

Deployment infrastructure is intentionally outside this phase.

## Requirements

- Node.js 22+
- npm 10+
- Go 1.25+
- PostgreSQL 15+

## Install

```bash
npm install
```

## Run locally

Create local PostgreSQL databases, load the sample environment, then migrate and seed the development database:

```bash
createdb vk_platform
createdb vk_platform_test
cp .env.example .env
set -a
source .env
set +a
make db-migrate-up
make db-seed
```

Adjust the database URLs in `.env` if the local PostgreSQL role requires explicit credentials.

The API never runs migrations automatically. `make db-migrate-down` reverses one migration when needed.

Start the API:

```bash
make dev-api
```

Start the frontend in another terminal:

```bash
make dev-web
```

Open `http://localhost:3000` for the portfolio and `http://localhost:3000/status` for API health. The frontend checks `http://localhost:8080` by default. Override the API location with `API_BASE_URL`.

The project API exposes published records through `GET /api/v1/projects`, optional `?featured=true|false` filtering, and `GET /api/v1/projects/{slug}`.

## Verify

```bash
make check
```
