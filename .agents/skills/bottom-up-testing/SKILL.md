---
name: bottom-up-testing
description: Design, implement, or review VK-platform tests with bottom-up, layer-owned coverage across the Go backend, TypeScript frontend, Python services, persistence, and HTTP APIs.
---

# Bottom Up Testing

## Strategy

Test dependencies from the leaves upward. If `A` calls `B` and `C`, while `B` calls `D` and `E`, establish `D` and `E` first, then `B` and `C`, then `A`.

Put branch coverage at the layer that owns the behavior:

- Persistence tests prove database queries and stored state.
- Service or use-case tests prove business rules and collaborator orchestration.
- Handler, controller, or API tests prove routing, transport validation, auth, serialization, mapping, and end-to-end wiring.

Do not make a high-level flow test prove every lower-level branch. Do not duplicate already-sufficient lower-level coverage merely to satisfy the dependency shape.

## Persistence Tests

- Use the real repository implementation with the repository's test database or containerized database.
- Do not mock services, handlers, gateways, or unrelated repositories.
- Arrange focused database rows, call the repository method, then assert returned values and persisted state directly.
- Cover filters, insert/update/upsert behavior, nullable values, constraints, status transitions, transaction behavior, and persistence-owned edge cases.
- Keep cleanup deterministic and prevent test-order dependencies.

## Service and Use-Case Tests

- Instantiate the unit under test directly and replace external I/O and lower-layer collaborators at its boundary with mocks, fakes, or stubs.
- Cover meaningful success and failure combinations, optional inputs, boundaries, feature flags, rollback or compensation behavior, and externally visible side effects.
- Assert results and errors first, then meaningful required calls, prohibited calls, and state changes. Avoid assertions on incidental implementation details.
- Prefer table-driven or parameterized tests when they make a behavior matrix explicit without duplicating setup.

## Handler and API Integration Tests

- Exercise the real router or framework test client, authentication, serialization, validation, mapping, and persistence when those are part of the endpoint contract.
- Keep one or two representative happy paths, plus cases owned by the transport layer such as invalid input, authorization, mapping, or failed-request behavior.
- Leave business-rule combinations to service tests and query edge cases to persistence tests.
- Share expensive fixtures only when tests remain independent. Use scoped or unique records for cases that mutate state.

## Go Conventions

- Use the standard `testing` package and repository-established helpers; add third-party assertion or mocking libraries only when they provide a clear project-wide benefit.
- Keep tests in `_test.go` files beside the package they verify. Choose external test packages only when testing the public contract is intentional.
- Prefer table-driven subtests for input and outcome matrices. Give cases behavior-focused names.
- Use `httptest` for HTTP behavior and `t.TempDir()` for filesystem isolation.
- Pass contexts and bounded timeouts through concurrent or I/O code. Avoid sleeps and timing-sensitive assertions.
- Call `t.Parallel()` only when fixtures, environment variables, ports, and persistent state are isolated.
- Run focused package tests during iteration and `go test ./...` before handoff when the repository size and available dependencies make it practical.

For TypeScript and Python, preserve the same layer ownership while using the framework and commands already established in the relevant app.

## Test Handoff

Report exactly which commands ran and their result. If a relevant suite was not run, state why. Keep test files, fixtures, and test-only configuration in the separate `test:` commit required by `.agents/skills/feature-delivery/SKILL.md`.
