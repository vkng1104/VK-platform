## Let the database own structural truth

PostgreSQL is the runtime source of truth for VK Platform's project catalog and email-verification challenges. Ordered migrations define tables, foreign keys, uniqueness, timestamps, and essential range constraints. Each migration has an explicit reverse operation, and application startup never applies schema changes implicitly.

The database enforces invariants that must hold for every writer. Application services still validate business-facing formats such as slugs, URLs, and user input so callers receive useful domain errors before persistence.

## Write queries for real access patterns

Repositories use explicit, parameterized SQL rather than a generic CRUD layer. Queries and indexes follow actual reads: published project listing, project lookup by slug, and bounded challenge operations. Records are scanned and mapped inside the repository so database details do not escape into services or HTTP responses.

Transactions are passed explicitly when an operation needs atomic reads and writes. Database errors are translated into domain errors before they cross the persistence boundary.

## Test PostgreSQL with PostgreSQL

Query behavior, constraints, nullability, ordering, and transactions belong to integration tests that run the real repository against an isolated PostgreSQL database. Mocks can prove service orchestration, but they cannot prove that SQL scans the correct columns or that a migration enforces the intended invariant.

This bottom-up split keeps tests focused: persistence tests prove stored state, service tests prove rules, and handler tests prove the public transport contract.
