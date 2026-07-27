---
name: bill-generic-code-review-persistence
description: Technology-neutral persistence review for atomicity, consistency, migrations, ownership, and recovery.
internal-for: bill-code-review
---

# Generic Persistence Review

## Review Focus

Check transaction boundaries, concurrent writers, uniqueness, ordering, partial writes, migrations, backward reads, deletion, retention, and recovery after interruption. Verify durable state has one owner and derived state can be rebuilt safely.

## Evidence

Describe the interleaving, legacy record, or failure point that produces corruption, loss, duplication, or an unrecoverable state.
