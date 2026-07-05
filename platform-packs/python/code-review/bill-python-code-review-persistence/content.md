---
name: bill-python-code-review-persistence
description: Review Python persistence code including SQLAlchemy, Django ORM, raw SQL, migrations, transactions, locking, sessions, and consistency.
internal-for: bill-code-review
---

# Python Persistence Review

Focus on data correctness and persistence lifecycle.

## Review Focus

- ORM usage: SQLAlchemy, Django ORM, peewee, raw SQL, query builders, lazy loading, relationship loading, N+1 risks, and transaction boundaries.
- Migrations: ordering, reversibility, backfill safety, null/default changes, index creation, concurrent deploy compatibility, and data migration idempotency.
- Transactions and locking: atomicity, isolation assumptions, retries, deadlocks, optimistic/pessimistic locking, unique constraints, and idempotent writes.
- Connection and session lifecycle: session scope, connection pooling, async engine/session usage, cleanup, test transactions, and framework-managed sessions.
- Data consistency: serialization to stored fields, enum/state transitions, partial failures, outbox/event writes, cache invalidation, and cross-store consistency.

## Findings Standard

Report concrete ways data can be lost, duplicated, corrupted, queried inefficiently, or left inconsistent across retries and deploys.
