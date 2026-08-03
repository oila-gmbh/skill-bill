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

Migrations must be additive and loud-failing for incompatible version drift.
Startup recovery must classify expired or incomplete rows deterministically.
Terminal reconciliation must atomically persist the final classification and
the evidence required to explain it. Duplicate identical events are no-ops;
conflicting duplicate events and duplicate attempts are rejected.

## Test matrix

Cover migration and round-trip serialization for every lifecycle state and
identity, restart recovery for expired and incomplete rows, bounded diagnostic
rejection, duplicate-event idempotency, duplicate-attempt rejection, and
atomic terminal reconciliation after a simulated crash.

## Exclusions

In-memory runner summaries are never authoritative. Retain bounded diagnostic
references, not raw provider output or transcripts.
