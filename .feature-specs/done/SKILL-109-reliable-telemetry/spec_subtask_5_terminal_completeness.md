---
issue_key: SKILL-109
subtask_id: 5
status: Complete
---

# Subtask 5 — Terminal-event completeness (kill session leakage)

## Scope

Ensure every started flow session emits exactly one terminal `finished` event, driving
feature_verify/feature_implement/quality_check leakage from ~40% toward the runtime flow's ~1.4%,
and produce the `abandoned` signal for goal issues.

## Root cause

Sessions are created and emit `started` immediately via MCP tools. If the calling skill/agent is
interrupted or errors after `started` but before `finished`, no terminal event is emitted.
`StaleSessionReconciler` exists but is **tests-only** and marks sessions stale without emitting a
`finished` telemetry event.

## Proposed solution

- Wire `StaleSessionReconciler` into a **production reconcile hook** (e.g. invoked at runtime
  startup and/or on a periodic schedule), so stale `feature_implement_sessions`,
  `feature_verify_sessions`, and `quality_check_sessions` are reconciled in production.
- Have the reconciler **emit a terminal `finished` telemetry event** (`completion_status = stale`,
  or a documented `abandoned` value) for each session it marks stale, so started→finished becomes
  ~1:1.
- Reuse the same reconciler hook to emit `goal_issue_finished` with `status = abandoned`
  (introduced in Subtask 1) for goal issues whose last segment was blocked and that have had no
  activity for N days.
- Ensure the reconcile is idempotent (never emits a second terminal event for a session that later
  emits its own).

## Acceptance Criteria

1. `StaleSessionReconciler` runs in production (not only under test) on a defined trigger.
2. A session marked stale emits exactly one terminal `finished` telemetry event.
3. After reconciliation, started→finished is ~1:1 for feature_implement, feature_verify, and
   quality_check within a bounded window (target leakage < 5%).
4. A goal issue with no activity for N days and a last-blocked segment emits a
   `goal_issue_finished` event with `status = abandoned`.
5. Reconciliation is idempotent: a session that later emits its own `finished` does not receive a
   duplicate terminal event.

## Non-goals

- Adding try/finally terminal emission inside every skill (the reconciler is the centralized fix).
- Defining the exact staleness threshold (N) — pick a sensible default in implementation and make
  it configurable.

## Dependencies

Depends on Subtask 1 (`goal_issue_finished` `abandoned` status + stats store). Feeds Subtask 6
(leakage assertion).

## Validation strategy

- Integration test: start a session, abort before finished, run the reconciler, assert exactly one
   stale `finished` event is emitted and none is emitted on a second reconcile.
- Post-deploy: re-run the PostHog leakage aggregation; confirm < 5%.
- `(cd runtime-kotlin && ./gradlew check)` green.

## Next path

Subtask 6.
