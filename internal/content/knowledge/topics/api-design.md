## Start with the public contract

An HTTP endpoint is useful when callers can rely on its behavior, not merely when it returns a successful response. I treat the route, request shape, response shape, status codes, headers, and failure codes as one contract. In VK Platform, the checked-in OpenAPI document records that contract and the real router is tested against representative responses.

That contract stays deliberately separate from internal database records. Transport DTOs use explicit `snake_case` JSON fields, while services and repositories keep models that reflect their own responsibilities. This prevents a persistence refactor from accidentally becoming an API change.

## Keep each layer narrow

The request path follows a modular-monolith boundary:

1. the handler validates transport input and maps public errors;
2. the service owns business rules and collaborator orchestration; and
3. the repository owns SQL, scans, and database-error translation.

This is more code than serializing a row directly, but each layer answers a different class of question. A handler test can prove strict JSON behavior without needing to cover every business combination. A service test can prove a rule without constructing an HTTP request. A repository integration test can prove a query against PostgreSQL instead of a mock.

## Make failures safe and actionable

Stable error codes let clients decide what to do without parsing prose. A request identifier connects the public response to internal logs while keeping infrastructure details private. Unknown JSON fields, oversized bodies, malformed path parameters, and unsupported methods are rejected at the transport boundary before they can blur the service contract.

The goal is not ceremony. It is to make change local: a business-rule change belongs in the service, a query change belongs in the repository, and a public-contract change updates the handler, OpenAPI document, and their focused tests together.
