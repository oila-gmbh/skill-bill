---
name: bill-rust-code-review-persistence
description: Use when reviewing Rust queries, database clients, mappings, migrations, transactions, locking, and data consistency.
internal-for: bill-code-review
---

# Rust Persistence Review

Review concrete durability and consistency risks.

## Checks

- Keep transaction ownership explicit and cover every related read, write, event, and rollback requirement.
- Verify SQLx, Diesel, SeaORM, driver, or repository mappings preserve nullability, numeric ranges, enums, timestamps, and identifiers.
- Prevent N+1 queries, unbounded result loading, accidental connection retention, and transaction or lock guards held across unrelated `.await` work.
- Review optimistic/pessimistic locking, atomic updates, idempotency, retries, and duplicate delivery for lost updates or repeated effects.
- Ensure migrations are ordered, deployable, reversible when policy requires it, and compatible with mixed application versions.
- Keep schema/query metadata and generated bindings synchronized through repository commands.
- Preserve tenant and authorization scoping in every query path.
- Distinguish not-found, conflict, constraint, timeout, and infrastructure errors when callers depend on them.
- Ensure cancellation or task failure cannot report success before durable commit or leak pooled resources.

Avoid ORM or query-style preferences without a correctness, performance, or maintainability consequence.
