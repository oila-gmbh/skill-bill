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

### Rust Persistence Rules

- Require one explicit transaction owner through `sqlx::Transaction`, `diesel::Connection::transaction`, `sea_orm::DatabaseTransaction`, or the detected raw client; reject nested ownership that commits partial state.
- Ensure every error path calls the client's rollback semantics or safely relies on transaction `Drop`; flag an aborted connection reused in an invalid state or work committed after failure.
- Verify borrowed executors and row streams such as `fetch` remain within the connection or transaction lifetime; reject pool starvation or data read outside its consistency boundary.
- Require concurrent tasks to acquire separate `Pool` connections rather than sharing one mutable transaction; reject serialization bugs, runtime borrow failure, or deadlock.
- Ensure tenant, owner, authorization, and soft-delete predicates are present in `query!`, Diesel DSL, SeaORM filters, and raw SQL paths; reject cross-scope data exposure.
- Require read-modify-write operations to use `SELECT ... FOR UPDATE`, an atomic update, or an optimistic version predicate; flag lost updates under a concrete interleaving.
- Verify retry handling recognizes `sqlx::Error::Database` serialization and deadlock codes and restarts the whole transaction; reject partial retry that corrupts state or repeats external effects.
- Ensure external side effects occur after commit or through a durable outbox record in the same `Transaction`; reject messages emitted for data that later rolls back.
- Require `SET TRANSACTION ISOLATION LEVEL` and lock order to match the business invariant; flag phantom reads, write skew, or deadlocks hidden by the default connection policy.
- Require `ALTER TABLE` schema expansion to remain compatible with mixed application versions before contraction; reject deployment failure when old code observes the new schema.
- Require large backfills to use bounded `LIMIT` batches, stable checkpoints, and resumable progress; reject resource exhaustion, long table locks, or operational restart data loss.
- Verify `chrono::DateTime<Utc>`, `rust_decimal::Decimal`, UUIDs, nullable fields, and database enums preserve precision and meaning; reject truncation, timezone drift, or invalid decoding.
- For Blocker or Major findings, describe the concrete data-loss, consistency, or durability failure scenario.
