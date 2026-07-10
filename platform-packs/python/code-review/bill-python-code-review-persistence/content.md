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

### Sessions and Transactions

- Verify SQLAlchemy `expire_on_commit` behavior, detached-instance access, relationship lazy loads, and request/session lifetime ownership; reject objects used after their owning session closes.
- Account for SQLAlchemy autoflush before queries and require explicit `no_autoflush` or ordering when premature writes can violate an invariant.
- Verify Django `transaction.atomic` nesting and `on_commit` ordering so external effects occur only after durable success and callbacks observe the intended committed state.
- Require atomicity, explicit isolation assumptions, idempotent retries, deadlock handling, and appropriate optimistic or pessimistic locking where concurrent writes can lose updates or duplicate state.

### Bulk Writes and Migrations

- Verify Django `bulk_create` and `update` intentionally bypass model `save`, signals, validation, and `auto_now`; reject reliance on hooks or timestamps that will never run.
- Require tenant predicates on every bulk read, update, and delete; reject missing scope with the concrete data-loss or cross-tenant modification consequence.
- Require reversible Alembic and Django migrations, paired forward and reverse `RunPython` operations, idempotent backfills, safe null/default transitions, and deploy-compatible ordering.
- Require concurrent or otherwise non-blocking index creation where production table size and database support make a blocking build an availability failure.
- Preserve serialization, enum/state transitions, outbox/event writes, and cache invalidation across partial failures and cross-store boundaries.
- For Blocker or Major findings, describe the concrete data-loss, consistency, or durability failure scenario.
