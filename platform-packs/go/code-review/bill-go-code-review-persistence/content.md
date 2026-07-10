---
name: bill-go-code-review-persistence
description: Use when reviewing Go persistence risks including database/sql, sqlx, sqlc, GORM, Ent, transactions, query scoping, migrations, concurrency, and data consistency.
internal-for: bill-code-review
---

# Backend Persistence Review Specialist

Review only backend persistence issues that can corrupt data, break consistency, or create high-risk operational regressions.

## Focus

- Transaction boundaries and atomicity
- Query correctness and tenant/filter scoping
- Lost updates, race-prone write patterns, and idempotent persistence behavior
- Migration/schema compatibility risks
- SQL, driver, ORM, and generated-query mapping mismatches that break reads or writes

## Ignore

- Harmless query-style preferences
- Micro-optimizations with no correctness or production impact

## Applicability

Use this specialist for backend/server persistence code only: repositories, `database/sql`, sqlx, sqlc, GORM, Ent, SQL, migrations, projections, and similar persistence layers.

## Project-Specific Rules

### Transaction And Consistency

- Do not split one business write across multiple implicit transactions unless partial completion is explicitly intended
- Avoid load-modify-save patterns that can lose concurrent updates when atomic SQL or version checks are required
- Optimistic updates, counters, balances, status transitions, and reservation flows need atomic predicates, version checks, locks, or unique constraints when concurrent requests can collide
- Repository methods must apply required tenant/account/ownership filters consistently
- Upserts, deduplication, and unique-constraint behavior should match the intended idempotency contract
- Migrations must account for existing data, nullability transitions, indexes, and rollout compatibility
- Rows, statements, result sets, and transactions must be closed or finalized reliably using the driver or helper equivalent; place cleanup close to acquisition so early returns, loops, or branching do not leak `Rows` or leave transactions unresolved
- `QueryRow`/`Scan` and equivalent helpers must distinguish `ErrNoRows`, partial reads, and decode failures correctly instead of collapsing them into misleading zero-value success
- Queries and persistence calls should use the correct `context.Context` so deadlines, cancellation, and tracing behave as intended; avoid background-context database calls in request or worker paths
- After `BeginTx`, cleanup must make rollback-on-error explicit so failed or panicking paths do not leave transaction state ambiguous
- Avoid holding connections across goroutine boundaries or long-running operations where pool exhaustion could occur
- Do not hold persistence transactions open across remote I/O, event publishing, queue dispatch, or other work that should happen after commit unless the project explicitly requires it
- Bulk operations should preserve correctness, not just speed; verify partial-failure behavior
- Projection or derived-table updates must be concurrency-safe; avoid read-modify-write patterns when atomic SQL/update operations are required
- Migration rollout must consider backfills, dual-read/dual-write windows, and replay or rebuild paths when contracts or projections change
- ORM or query-helper convenience helpers (`sqlx`, `sqlc`, GORM, Ent, builders) must not hide missing filters, accidental N+1 query/write patterns, implicit hooks, or silent partial updates in persistence-critical paths
- Check database defaults, casts, enum storage, and timestamp behavior for write/read drift against the intended domain and API contract
### ORM, SQL, And Query Boundaries

- Eager/lazy loading choices must not create N+1 reads, over-broad hydration, or stale entity assumptions in persistence-critical or hot paths
- Within transactions and persistence workflows, reused models/entities/query objects must be refreshed, reloaded, or replaced when stale values can change query or write behavior
- Query builders, ORM scopes, cursors, transactions, and persistence records should not leak across service or module boundaries unless the local architecture explicitly accepts that coupling
- Mass update, delete, restore, and bulk-write paths must carry tenant, account, ownership, soft-delete, and business filters as explicitly as single-row paths
- GORM hooks/preloads, Ent eager-loading or transaction clients, sqlc wrappers, and scanner/binder helpers must keep transaction scope, lock semantics, and write ordering explicit
- Model/entity hooks, observers, listeners, and callbacks must not perform cross-boundary business workflows unless the project architecture intentionally routes side effects there
- For Blocker or Major findings, describe the concrete data-loss, consistency, or durability failure scenario.
