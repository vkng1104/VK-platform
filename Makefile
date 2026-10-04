.PHONY: install dev-web dev-api build lint typecheck test check

install:
	npm install

dev-web:
	npm run dev:web

dev-api:
	cd apps/api && go run ./cmd/server

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
