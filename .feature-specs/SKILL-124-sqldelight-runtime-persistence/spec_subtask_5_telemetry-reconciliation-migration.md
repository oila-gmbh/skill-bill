---
status: Ready
parent_spec: ./spec.md
subtask_id: 5
---

# SKILL-124 · Subtask 5 — Telemetry, outbox, and reconciliation migration

## Scope

Migrate operational telemetry persistence and recovery queries to SQLDelight.

1. Migrate lifecycle telemetry session creation, updates, terminal-event
   idempotency, duration fields, duplicate detection, and mode attribution.
2. Migrate quality-check and goal telemetry writes and reads while retaining
   payload serialization at the existing contract boundary.
3. Migrate telemetry outbox enqueue, pending selection, success/failure marking,
   ordering, limits, and retry state.
4. Migrate reconciliation-state persistence, stale candidate selection,
   activity/progress timestamps, stale-session updates, and goal-progress
   recovery.
5. Preserve immediate-transaction behavior for races currently guarded by
   `BEGIN IMMEDIATE`, or replace it only with a proven SQLDelight transaction
   mode providing equivalent writer serialization.
6. Retain append-only/idempotent invariants and explicit typed decoding for
   telemetry wire values and JSON payloads.
7. Remove replaced JDBC mapping/binding helpers while keeping data-migration code
   until subtask 7.

## Acceptance Criteria (this subtask)

1. `LifecycleTelemetryRepository`, `TelemetryReconciliationRepository`, and
   `TelemetryOutboxRepository` ordinary runtime operations use generated
   queries.
2. Quality, feature-task, feature-verify, goal, and review lifecycle telemetry
   retains current insert/update/finish/idempotency and duplicate-event behavior.
3. Outbox polling remains ordered and bounded; pending, synced, error, and retry
   state survive restart and concurrent access.
4. Stale reconciliation selects the same candidates, uses the same progress
   authority, and performs atomic state/event changes without duplicate terminal
   emission.
5. `BEGIN IMMEDIATE`-dependent races have explicit concurrency tests proving no
   lost update, duplicate event, or partial commit.
6. Invalid telemetry rows and payloads retain typed loud-fail behavior.
7. Superseded runtime JDBC query/mapping code is deleted, while historical
   migration transformations remain intact for subtask 7.

## Non-Goals

- Changing telemetry event schemas, payload fields, upload protocol, or product
  metrics.
- Altering stale thresholds or idle-policy semantics.
- Migrating review analytics owned by subtask 6.
- Taking over schema migration versioning.

## Dependencies

- Subtasks 1 through 4 must be complete.

## Validation Strategy

- Run lifecycle, goal, quality, outbox, reconciliation, and stale-session parity
  tests.
- Add multi-connection race tests around immediate writer transactions and
  duplicate terminal emission.
- Test restart recovery with pending outbox and partially progressed sessions.
- Run `cd runtime-kotlin && ./gradlew :runtime-infra-sqlite:check :runtime-application:check :runtime-cli:check :runtime-mcp:check`.

## Next Path

Run `bill-feature-task` on
`.feature-specs/SKILL-124-sqldelight-runtime-persistence/spec_subtask_5_telemetry-reconciliation-migration.md`.
