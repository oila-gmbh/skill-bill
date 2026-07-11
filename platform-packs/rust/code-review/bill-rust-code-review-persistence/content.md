---
name: bill-rust-code-review-persistence
description: Use when reviewing Rust database access, ORM or query code, transactions, migrations, locking, and durable consistency.
internal-for: bill-code-review
---

# Persistence Review Specialist

## Focus

- SQLx, Diesel, SeaORM, raw SQL, mappings, query scoping, and connection lifecycle
- Transaction ownership, isolation, locking, retries, idempotency, and partial failure
- Forward/backward-safe migrations, constraints, backfills, and rollback or recovery
- Async database work, pool pressure, streaming rows, and cancellation

## Ignore

- Query style preferences without correctness or performance impact
- Schema redesign unrelated to the changed behavior
- ORM-versus-SQL preferences when the repository contract is satisfied

## Applicability

Apply when Rust changes read or write durable state, schema, caches used as truth, migrations, or transaction boundaries.

## Project-Specific Rules

- Keep one clear transaction owner per business operation and propagate failures without committing partial state.
- Ensure futures, row streams, and borrowed transaction handles cannot escape their valid connection or transaction lifetime.
- Check every query for tenant, ownership, soft-delete, and authorization scoping where applicable.
- Make retries safe for unique constraints, lost updates, external side effects, and transaction-abort semantics.
- Keep migrations compatible with mixed application versions and realistic table sizes; avoid long locks and unbounded backfills.
- Preserve nullability, enum, time, decimal, and identifier semantics across Rust types and database types.
- Findings must provide the failing interleaving, data shape, or deployment sequence and use only canonical severities.
