---
name: bill-php-code-review-persistence
description: Use when reviewing PHP persistence risks including transactions, ORM mappings, query scoping, migrations, concurrency, and data consistency.
internal-for: bill-code-review
---

# Backend Persistence Review Specialist

Review only backend persistence issues that can corrupt data, break consistency, or create high-risk operational regressions.

## Focus

- Transaction boundaries and atomicity
- Query correctness and tenant/filter scoping
- Lost updates, race-prone write patterns, and idempotent persistence behavior
- Migration/schema compatibility risks
- ORM/SQL mapping mismatches that break reads or writes

## Ignore

- Harmless query-style preferences
- Micro-optimizations with no correctness or production impact

## Applicability

Use this specialist for backend/server persistence code only: repositories, ORM models, query builders, SQL, migrations, projections, and similar persistence layers.

## Project-Specific Rules

### Transaction And Consistency

- Do not split one business write across multiple implicit transactions unless partial completion is explicitly intended
- Avoid load-modify-save patterns that can lose concurrent updates when atomic SQL or version checks are required
- Optimistic updates, counters, balances, status transitions, and reservation flows need atomic predicates, version checks, locks, or unique constraints when concurrent requests can collide
- Repository methods must apply required tenant/account/ownership filters consistently
- Upserts, deduplication, and unique-constraint behavior should match the intended idempotency contract
- Migrations must account for existing data, nullability transitions, indexes, and rollout compatibility
- Connections, database sessions, cursors, and statements must be closed reliably using the framework or driver equivalent
- Avoid holding connections across async boundaries or long-running operations where pool exhaustion could occur
- Do not hold persistence transactions open across remote I/O, event publishing, queue dispatch, or other work that should happen after commit unless the project explicitly requires it
- Bulk operations should preserve correctness, not just speed; verify partial-failure behavior
- Projection or derived-table updates must be concurrency-safe; avoid read-modify-write patterns when atomic SQL/update operations are required
- Migration rollout must consider backfills, dual-read/dual-write windows, and replay or rebuild paths when contracts or projections change
- ORM convenience methods must not hide missing filters, accidental N+1 query/write patterns, or silent partial updates in persistence-critical paths
- Check database defaults, casts, enum storage, and timestamp behavior for write/read drift against the intended domain and API contract
- For Critical or Major findings, explain the data-loss, stale-write, cross-tenant, migration, or consistency consequence explicitly

### ORM And Query Boundaries

- Eager/lazy loading choices must not create N+1 reads, over-broad hydration, or stale relation assumptions in persistence-critical or hot paths
- Within transactions and persistence workflows, reused model/entity instances must be refreshed, reloaded, or replaced when stale attributes or loaded relations can change query or write behavior
- Query builders, ORM scopes, cursors, transactions, and persistence records should not leak across service or module boundaries unless the local architecture explicitly accepts that coupling
- Mass update, delete, restore, and bulk-write paths must carry tenant, account, ownership, soft-delete, and business filters as explicitly as single-row paths
- Global scopes, default filters, and implicit ORM events are acceptable local patterns only when their authorization, tenant scoping, lifecycle side effects, and transaction ownership remain visible, tested, and hard to bypass where correctness or security depends on them
- Model/entity events, observers, listeners, and callbacks must not perform cross-boundary business workflows unless the project architecture intentionally routes side effects there
