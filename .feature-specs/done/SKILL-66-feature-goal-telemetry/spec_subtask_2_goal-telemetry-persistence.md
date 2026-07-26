---
status: Complete
---

# SKILL-66 Subtask 2 - Goal Telemetry Persistence

Parent spec: [.feature-specs/SKILL-66-feature-goal-telemetry/spec.md](./spec.md)
Issue key: SKILL-66

## Scope

Persist the goal telemetry event family through the existing
lifecycle-telemetry seam: record types and mappers in the application layer,
port methods on `LifecycleTelemetryRepository`, and the SQLite implementation
in `LifecycleTelemetryStore`, with a `schema_migrations` entry if new tables or
columns are required. Include the read-side queries the `goal_stats`
aggregation (Subtask 4) will need.

This subtask owns the persistence seam only. It does not emit events
(Subtask 3) and does not expose any stats surface (Subtask 4). Existing
implement/verify telemetry storage is untouched.

## Acceptance Criteria

1. `LifecycleTelemetryRepository` gains methods for the three goal events
   (e.g. `goalStarted(record)`, `goalSubtaskFinished(record)`,
   `goalFinished(record)`) plus the read/aggregate queries needed for stats
   (run counts and terminal-status counts, duration aggregates, per-subtask
   outcome breakdown, most-recent-run lookup), following the interface style
   of the existing event methods.
2. Record types live beside the existing lifecycle records, mapped via the
   `LifecycleTelemetryRecordMappers` pattern, and conform field-for-field to
   the Subtask 1 schema branches (no extra fields, no missing fields).
3. `LifecycleTelemetryStore` implements the new methods inside
   `database.transaction()` / unit-of-work consistently with existing events;
   a `schema_migrations` entry creates any new tables/columns; existing
   tables and migrations are unchanged.
4. Resume-safety key: storage enforces (or stats queries account for) the
   parent-spec dedupe identity `(issue_key, subtask_id, workflow_id)` for
   `goal_subtask_finished`, per the parent open-question resolution recorded
   during this subtask.
5. Malformed persisted rows loud-fail with typed errors on read; no
   best-effort parsing or silent truncation.
6. Tests: `runtime-infra-sqlite` repository tests covering write + read +
   aggregate paths for all three events (including blocked_reason, skipped
   subtasks, and the empty-store case), a migration test if a migration is
   added, and an application persistence-port test covering the new methods.

## Non-Goals

- No emission from GoalRunner (Subtask 3).
- No MCP/CLI surface (Subtask 4) — only the queries it will call.
- Do not alter implement/verify telemetry tables, records, or queries.
- Do not leak infrastructure types through application/port APIs; persistence
  types stay in their owning `model` packages.

## Dependency Notes

Depends on: Subtask 1 (the payload contracts the records mirror).

Provides the durable store Subtask 3 writes through and the aggregate queries
Subtask 4 reads.

## Validation Strategy

`runtime-infra-sqlite` repository + migration tests; application
persistence-port test; `RuntimeArchitectureTest` continues to pass.

## Next Path

Run bill-feature-task on spec_subtask_3_goal-runner-runtime-emission.md.

## Spec Path

.feature-specs/SKILL-66-feature-goal-telemetry/spec_subtask_2_goal-telemetry-persistence.md
