---
name: bill-typescript-code-review-persistence
description: Use when reviewing TypeScript database clients, ORM or query code, transactions, migrations, serialization, and durable consistency.
internal-for: bill-code-review
---

# Persistence Review Specialist

## Focus

- ORM/query builders, raw SQL, mappings, schema validators, and connection lifecycle
- Transaction ownership, isolation, retries, idempotency, and partial failure
- Migrations, constraints, backfills, locking, and mixed-version deployment
- Nullability, dates, decimals, bigint, enums, JSON, identifiers, and data-shape drift

## Ignore

- Query style preferences without correctness or performance impact
- Unrelated schema redesign
- ORM-versus-SQL preferences when contracts are satisfied

## Applicability

Use this specialist when changed TypeScript affects database access, schemas, migrations, transactions, or durable serialization.

## Project-Specific Rules

### TypeScript Persistence Rules

- Verify `TypeScript transaction and storage APIs` preserve consistency invariants; reject a consistency or durability failure.
- Keep one transaction owner per business operation and await every persistence promise before commit or release.
- Validate database results when generated or declared types can drift from deployed schema.
- Preserve the distinction among absent, `undefined`, `null`, database NULL, default, and omitted update fields.
- Check tenant, ownership, soft-delete, and authorization scoping on every reachable query.
- Make retries safe for unique constraints, lost updates, external side effects, and transaction abort semantics.
- Keep migrations compatible with realistic table sizes and mixed application versions.
- Serialize date, decimal, bigint, enum, and JSON values explicitly across runtime and driver boundaries.
- Findings must provide the failing data shape, interleaving, or deployment sequence.
- For Blocker or Major findings, describe the concrete data-loss, consistency, or durability failure scenario.
