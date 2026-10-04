# VK Platform

VK Platform is a systems-focused engineering portfolio. The repository starts as a small monorepo with a Next.js frontend and a Go API.

## Applications

- `apps/web` — Next.js frontend with the placeholder homepage and service status page.
- `apps/api` — Go HTTP API with the platform health endpoint.

Infrastructure is intentionally outside this phase.

## Requirements

- Node.js 22+
- npm 10+
- Go 1.25+

## Install

```bash
npm install
```

## Run locally

Start the API:

```bash
make dev-api
```

Start the frontend in another terminal:

```bash
make dev-web
```

Open `http://localhost:3000` for the placeholder page and `http://localhost:3000/status` for API health. The frontend checks `http://localhost:8080/healthz` by default. Override the API location with `API_BASE_URL`.

## Verify

```bash
make check
```
