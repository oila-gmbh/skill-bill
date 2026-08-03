# Follow-up specification: delegated review persistence

**Order:** 4 of 9  
**Depends on:** `spec_followup_application.md`  
**Purpose:** make lifecycle rows and event history restart-safe, bounded, and
atomically reconciled.

## Scope and targets

- `runtime-kotlin/runtime-infra-sqlite/src/main/kotlin/skillbill/infrastructure/sqlite/review/ReviewPersistenceSupport.kt`
- `runtime-kotlin/runtime-infra-sqlite/src/main/kotlin/skillbill/db/core/DatabaseSchema.kt`
- `runtime-kotlin/runtime-infra-sqlite/src/main/kotlin/skillbill/db/review`
- `runtime-kotlin/runtime-infra-sqlite/src/test/kotlin/skillbill/review`

## Required persistence behavior

Persist versioned lifecycle snapshots and append-only event history with review,
sequence, packet, assignment, provider, worker, attempt, and diagnostic identity.
Enforce unique event ids, sequence ordering, current-attempt uniqueness, and
bounded payload size at the database and decode seams. Validate stored payloads
with the same schema used for new writes.

The lifecycle snapshot persistence mapping is explicit and round-trips these
fields without consulting current process configuration:

| Snapshot field | Durable column or payload key | Read-time invariant |
|---|---|---|
| `total_process_slots` | `total_process_slots` | positive integer |
| `coordinator_slots` | `coordinator_slots` | positive and less than total |
| `worker_slots` | `worker_slots` | equals total minus coordinator |
| `selected_worker_count` | `selected_worker_count` | equals unique selected assignments |
| `predicted_wave_count` | `predicted_wave_count` | equals ceiling(selected / worker slots) |
| `actual_wave_count` | `actual_wave_count` | reconciles with persisted wave membership |

The write and decode seams validate the complete mapping before admission. A
missing field, a mismatched formula, or a snapshot that only becomes coherent
after reading live configuration is rejected and cannot be used for restart
recovery.

Migrations must be additive and loud-failing for incompatible version drift.
Startup recovery must classify expired or incomplete rows deterministically.
Terminal reconciliation must atomically persist the final classification and
the evidence required to explain it. Duplicate identical events are no-ops;
conflicting duplicate events and duplicate attempts are rejected.

At terminal reconciliation, persist `terminal_persisted_at` and
`evidence_expires_at = terminal_persisted_at + 30 days` in the same transaction
as the terminal classification. Provide a clock-driven
`pruneExpiredReviewEvidence(now)` operation invoked during startup recovery and
by the periodic maintenance tick. It selects terminal reviews whose
`evidence_expires_at <= now` and, per review, atomically deletes lifecycle
event/evidence rows and bounded diagnostic payloads before setting
`evidence_pruned_at`. The terminal snapshot, identities, aggregate status, and
retention marker remain for restart accounting. A failed transaction rolls
back both deletion and marker update; cleanup is idempotent on repeated runs.
The operation must run at the exact expiry boundary, while a clock value just
before expiry must retain evidence.

## Test matrix

Cover migration and round-trip serialization for every lifecycle state and
identity, restart recovery for expired and incomplete rows, bounded diagnostic
rejection, duplicate-event idempotency, duplicate-attempt rejection, and
atomic terminal reconciliation after a simulated crash. Add round-trip and
restart tests for every capacity field and formula rejection tests for each
incoherent combination. Add fake-clock retention tests immediately before,
exactly at, and after the 30-day expiry; assert that cleanup removes all
event/evidence and diagnostic rows for the review, preserves the terminal
snapshot, rolls back on a simulated failure, and is a no-op when repeated.

## Exclusions

In-memory runner summaries are never authoritative. Retain bounded diagnostic
references, not raw provider output or transcripts.
