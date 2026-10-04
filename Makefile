.PHONY: install dev-web dev-api db-migrate-up db-migrate-down db-seed build lint typecheck test check

install:
	npm install

dev-web:
	npm run dev:web

dev-api:
	cd apps/api && go run ./cmd/server

db-migrate-up:
	cd apps/api && go run ./cmd/migrate up

db-migrate-down:
	cd apps/api && go run ./cmd/migrate down

db-seed:
	cd apps/api && go run ./cmd/seed

build:
	npm run build:web

lint:
	npm run lint:web

typecheck:
	npm run typecheck:web

test:
	npm run test:web
	cd apps/api && go test ./...

check: lint typecheck test build
