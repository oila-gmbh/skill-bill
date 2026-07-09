---
issue_key: SKILL-109
subtask_id: 2
status: Complete
---

# Subtask 2 — Fix `duration_seconds` + unit consistency

## Scope

Make `duration_seconds` plausible on lifecycle finished events, and standardize the duration unit
across all telemetry events.

## Root cause

Legacy databases predate the `started_at` column on the lifecycle session tables, and
`DatabaseColumnMigrations.ensureFeatureImplementSessionColumns` (`DatabaseColumnMigrations.kt:123`)
does not ensure it. `LifecycleTelemetryDurationSupport.durationSeconds()` returns 0 when `started_at`
is blank, producing the observed MEDIUM p50 ≈ 1s. The multi-day maxes are legitimate long sessions
(`started_at` is preserved across the finished-at UPDATE in `LifecycleTelemetrySaveSupport.kt:174`).
Separately, goal events emit `duration_ms` while every other event emits `duration_seconds`.

## Proposed solution

- Add `ensureColumn(connection, "<table>", "started_at", "TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP")`
  (and `finished_at` if absent) in `DatabaseColumnMigrations.kt` for `feature_implement_sessions`,
  `feature_verify_sessions`, and `quality_check_sessions`.
- Standardize on **`duration_seconds`** as the canonical unit: emit `duration_seconds` on goal
  events (`goal_started`, `goal_subtask_finished`, `goal_finished`, and the new
  `goal_issue_finished`) alongside the existing `duration_ms`, OR document `duration_ms` as the
  goal-canonical field — pick one in implementation and reflect it in the schema + parity tests.
- Add a duration-plausibility assertion to the reliability contract test (Subtask 6): for completed
  runs, `duration_seconds` must be > 0 and bounded by a sane ceiling.

## Acceptance Criteria

1. `DatabaseColumnMigrations` ensures `started_at` exists on `feature_implement_sessions`,
   `feature_verify_sessions`, and `quality_check_sessions` for legacy databases.
2. After migration, `duration_seconds` on a completed `feature_implement_finished` event is the
   real elapsed seconds between session start and finish (no near-zero values from blank
   `started_at`).
3. Goal events expose duration in a single, documented canonical unit consistent with the rest of
   the telemetry contract.
4. A test reproduces a legacy (pre-column) database, runs the migration, and asserts
   `duration_seconds` is non-zero for a finished session.

## Non-goals

- Capping or redefining legitimately long (multi-day) sessions.
- Changing how `started_at` is originally written on the started-event path.

## Dependencies

None. Subtask 6 references the plausibility assertion.

## Validation strategy

- Migration test per AC 4. Inspect emitted payloads locally for plausible `duration_seconds`.
- `(cd runtime-kotlin && ./gradlew check)` green.

## Next path

Subtask 3.
