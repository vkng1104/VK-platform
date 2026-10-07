TEST_COMPOSE_PROJECT ?= vk-platform-test
SOURCE_ENV ?=

.PHONY: install dev dev-reset dev-prepare dev-web dev-api dev-monitor down logs \
	db-migrate-up db-migrate-down db-seed db-seed-local build \
	lint lint-web lint-api typecheck test test-setup test-web test-api test-integration check

install:
	npm install

dev-prepare:
	docker compose build api
	docker compose up --detach --wait postgres
	docker compose run --rm migrate

dev: dev-prepare
	docker compose up api web

dev-reset:
	docker compose down --volumes --remove-orphans
	docker compose build api
	docker compose up --detach --wait postgres
	docker compose run --rm migrate
	docker compose run --rm seed
	docker compose up api web

dev-web:
	npm run dev:web

dev-api:
	cd apps/api && go run ./cmd/server

dev-monitor:
	docker compose logs --follow api

down:
	docker compose down

logs:
	docker compose logs --follow

db-migrate-up:
	cd apps/api && go run ./cmd/migrate up

db-migrate-down:
	cd apps/api && go run ./cmd/migrate down

db-seed:
	cd apps/api && go run ./cmd/seed

db-seed-local:
	@if [ "$(SOURCE_ENV)" != "development" ] && [ "$(SOURCE_ENV)" != "production" ]; then \
		echo "Usage: make db-seed-local SOURCE_ENV=development|production" >&2; \
		exit 2; \
	fi
	docker compose build api
	docker compose up --detach --wait postgres
	docker compose run --rm migrate
	SOURCE_ENV=$(SOURCE_ENV) docker compose --profile tools run --rm db-seed-local

build:
	npm run build:web
	docker build --target runtime --tag vk-platform-api:local apps/api

lint: lint-web lint-api

lint-web:
	npm run lint:web

lint-api:
	@files="$$(cd apps/api && gofmt -l .)"; \
		if [ -n "$$files" ]; then \
			echo "Go files need formatting:"; \
			echo "$$files"; \
			exit 1; \
		fi
	cd apps/api && go vet ./...

typecheck:
	npm run typecheck:web

test: test-setup test-web test-api

test-setup:
	./scripts/test/local-setup_test.sh

test-web:
	npm run test:web

test-api:
	docker build --target test --tag vk-platform-api:test apps/api

test-integration:
	@status=0; \
		docker compose --project-name $(TEST_COMPOSE_PROJECT) --profile test build migrate-test backend-integration || status=$$?; \
		if [ $$status -eq 0 ]; then \
			docker compose --project-name $(TEST_COMPOSE_PROJECT) --profile test run --rm backend-integration || status=$$?; \
		fi; \
		docker compose --project-name $(TEST_COMPOSE_PROJECT) --profile test down --volumes --remove-orphans; \
		exit $$status

check: lint typecheck test test-integration build
