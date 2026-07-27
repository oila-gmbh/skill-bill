---
name: bill-generic-code-review-persistence
description: Technology-neutral persistence review for atomicity, consistency, migrations, ownership, and recovery.
internal-for: bill-code-review
---

# Generic Persistence Review

## Focus

Check transaction boundaries, concurrent writers, uniqueness, ordering, partial writes, migrations, backward reads, deletion, retention, and recovery after interruption. Verify durable state has one owner and derived state can be rebuilt safely.

## Ignore

- Storage preferences without a consistency, migration, or durability consequence.

## Applicability

Use when changes affect transactions, storage, migrations, atomicity, or recovery.

## Project-Specific Rules

### Persistence Rules

- Require each changed `transaction boundary` to preserve atomicity and ownership; reject partial writes that can leave durable state inconsistent.
- For Blocker or Major findings, describe the concrete data-loss, consistency, or durability failure scenario.
