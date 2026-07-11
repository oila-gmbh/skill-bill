---
name: bill-python-code-review-persistence
description: Review Python persistence code including SQLAlchemy, Django ORM, raw SQL, migrations, transactions, locking, sessions, and consistency.
internal-for: bill-code-review
---

# Python Persistence Review

Review data correctness and persistence lifecycle.

## Focus

- ORM and raw SQL behavior, migrations, transactions, locking, sessions, bulk writes, tenant scope, and cross-store consistency

## Ignore

- Query style preferences without a correctness, consistency, durability, or material performance consequence
- Schema or ORM code outside the changed transaction and data ownership boundary

## Applicability

Use this specialist for SQLAlchemy, Django ORM, peewee, raw SQL, query builders, migrations, repositories, connection pools, sessions, outboxes, and cache invalidation.

## Project-Specific Rules

### Python Persistence Rules

- Require one explicit owner for SQLAlchemy `Session` or `AsyncSession` creation, commit, rollback, and close; split ownership risks partial commits and connection leaks after exceptions.
- Verify `expire_on_commit` and lazy relationship access before returning ORM objects beyond session scope; detached loads can fail after a response has begun.
- Account for SQLAlchemy `autoflush` before `select` or relationship queries and require `no_autoflush` only around an intentional invariant; premature writes can trigger invalid constraints.
- Reject concurrent use of one `AsyncSession` across `asyncio.gather` tasks; session state is mutable and races can corrupt transaction ordering.
- Require explicit isolation and locking through `with_for_update`, version columns, or database constraints for contested updates; unchecked read-modify-write loses data under concurrency.
- Require rollback and bounded retry around classified serialization failures or deadlocks; retrying arbitrary `IntegrityError` values can duplicate writes or mask invalid data.
- When Django is detected, verify nested `transaction.atomic` savepoints and `transaction.on_commit` callbacks preserve durable ordering; external effects before outer commit risk phantom delivery.
- Verify `bulk_create`, `bulk_update`, and `QuerySet.update` account for bypassed `save`, signals, validation, and `auto_now`; reliance on skipped hooks leaves contract data invalid.
- Require a trusted tenant predicate on every SQLAlchemy, Django ORM, or raw SQL read and write, including upserts; omitted scoping creates cross-tenant exposure or data loss.
- Require Alembic or Django migrations to use expand-and-contract ordering and deploy-compatible defaults; incompatible schema transitions break mixed-version application instances.
- Require `RunPython` or Alembic data migrations and backfills to be resumable, idempotent, bounded, and reversible where possible; interruption otherwise leaves corrupt partial state.
- Verify production index creation uses the database's non-blocking mechanism such as PostgreSQL `CONCURRENTLY` when table size makes locks unsafe; blocking DDL can cause an availability failure.
- Require an atomic outbox or equivalent transaction-owned record before publishing events, and versioned cache invalidation after commit; cross-store partial failure causes missing events or stale reads.
- Verify SQLAlchemy `selectinload` or Django `select_related` usage prevents N+1 access on persisted aggregates; uncontrolled query growth causes latency and resource failure.
- Require unique constraints and explicit `IntegrityError` conflict mapping for idempotent writes; check-then-insert races can corrupt contract data with duplicates.
- For Blocker or Major findings, describe the concrete data-loss, consistency, or durability failure scenario.
