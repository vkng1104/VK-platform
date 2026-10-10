TEST_COMPOSE_PROJECT ?= vk-platform-test
SOURCE_ENV ?=

.PHONY: install dev dev-reset dev-prepare dev-web dev-api dev-iam dev-monitor down logs \
	db-migrate-up db-migrate-down db-migrate-iam-up db-seed db-seed-local build \
	lint lint-web lint-api lint-iam typecheck test test-setup test-web test-api test-iam \
	test-integration generate-persistence check-persistence-generation \
	check-persistence-boundaries test-persistence check-persistence check

install:
	npm install

dev-prepare:
	docker compose build api iam
	docker compose up --detach --wait postgres iam-postgres
	docker compose run --rm migrate
	docker compose run --rm iam-migrate

dev: dev-prepare
	docker compose up api iam web

dev-reset:
	docker compose down --volumes --remove-orphans
	docker compose build api iam
	docker compose up --detach --wait postgres iam-postgres
	docker compose run --rm migrate
	docker compose run --rm iam-migrate
	docker compose run --rm seed
	docker compose up api iam web

dev-web:
	npm run dev:web

dev-api:
	cd apps/api && go run ./cmd/server

dev-iam:
	cd apps/iam && ./gradlew bootRun

dev-monitor:
	docker compose logs --follow api iam

down:
	docker compose down

logs:
	docker compose logs --follow

db-migrate-up:
	cd apps/api && go run ./cmd/migrate up

db-migrate-down:
	cd apps/api && go run ./cmd/migrate down

db-migrate-iam-up:
	cd apps/iam && ./gradlew migrate

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
	docker build --target runtime --tag vk-platform-iam:local apps/iam

lint: lint-web lint-api lint-iam

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

lint-iam:
	docker build --target build --tag vk-platform-iam:build apps/iam

typecheck:
	npm run typecheck:web

test: test-setup test-persistence test-web test-api test-iam

test-setup:
	./scripts/test/local-setup_test.sh

test-web:
	npm run test:web

test-api:
	docker build --target test --tag vk-platform-api:test apps/api

test-iam:
	docker build --target test --tag vk-platform-iam:test apps/iam

test-integration:
	@status=0; \
		docker compose --project-name $(TEST_COMPOSE_PROJECT) --profile test build migrate-test iam-migrate-test backend-integration iam-integration || status=$$?; \
		if [ $$status -eq 0 ]; then \
			docker compose --project-name $(TEST_COMPOSE_PROJECT) --profile test run --rm backend-integration || status=$$?; \
		fi; \
		if [ $$status -eq 0 ]; then \
			docker compose --project-name $(TEST_COMPOSE_PROJECT) --profile test run --rm iam-integration || status=$$?; \
		fi; \
		docker compose --project-name $(TEST_COMPOSE_PROJECT) --profile test down --volumes --remove-orphans; \
		exit $$status

generate-persistence:
	./scripts/generate-persistence.sh

check-persistence-generation:
	./scripts/check-persistence-generation.sh

check-persistence-boundaries:
	./scripts/check-persistence-boundaries.sh

test-persistence:
	./scripts/test/persistence-boundaries_test.sh
	./scripts/test/persistence-generation_test.sh

check-persistence: check-persistence-boundaries check-persistence-generation

check: lint typecheck check-persistence test test-integration build
