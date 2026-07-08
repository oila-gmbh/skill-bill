---
issue_key: SKILL-109
subtask_id: 1
---

# Subtask 1 — Goal issue-level terminal event + blocked sub-categorization

## Scope

Make goal outcome readable per-issue without bespoke SQL, and make `blocked` sub-categorizable.
Add a new `skillbill_goal_issue_finished` event that fires once per issue at a terminal outcome,
backed by a small incremental stats store, and enrich `goal_finished` with the stop reason.

## Root cause

`goal_finished.status` (`GoalRunnerTelemetryEmitter.kt:103`) is `if (report is Completed) "completed" else "blocked"`,
emitted per segment with a segment-scoped `workflow_id` (`:38`). `issue_key` is the stable unit of
work but has no terminal event and no issue-keyed aggregation path. The result: 87% of invocations
read as `blocked` while true issue completion is 60.6%.

## Proposed solution

- **New event** `skillbill_goal_issue_finished` (runtime-internal emission, not an MCP tool):
  - Schema branch `$defs/goalIssueFinishedEvent` in `orchestration/contracts/telemetry-event-schema.yaml`
    with `event_name.const: "goal_issue_finished"` and `additionalProperties: false`.
  - `emitGoalIssueFinished()` in `runtime-infra-sqlite/.../db/telemetry/GoalTelemetryEmitSupport.kt`
    patterned after `emitGoalFinished()`; payload builder `goalIssueFinishedPayload()` in
    `GoalTelemetryPayloadSupport.kt`; request model `GoalIssueFinishedRequest` in
    `runtime-application/.../application/model/LifecycleTelemetryRequests.kt`.
  - Parity coverage in `runtime-mcp/src/test/.../GoalTelemetryEmissionEventParityTest.kt`.
  - Payload: `issue_key`, `parent_workflow_id`, `status` (`completed` | `abandoned`),
    `subtasks_complete/blocked/skipped` (final), `total_invocations`, `total_blocks`,
    `total_resumes`, `first_started_at`, `finished_at`, `duration_ms`, `mode`.
- **Issue stats store**: new `goal_issue_progress` table (keyed by `parent_workflow_id`,
  `issue_key`) in `runtime-infra-sqlite/.../db/core/DatabaseSchema.kt` + DAO. Increment
  `total_invocations` (and `total_resumes` when `resumed`) on `goalStarted`; increment
  `total_blocks` on a blocked `goalFinished`; set `first_started_at` once. Read at completion.
  Additive table → no migration of legacy rows → migration-safe.
- **Emit hooks**: in `GoalRunnerTelemetryEmitter`, update the stats store in `goalStarted`/`goalFinished`;
  emit `goal_issue_finished` from `GoalRunner.closeGoalTelemetrySegment` (`GoalRunner.kt:137`) when
  the segment report is `Completed`. `abandoned` is emitted by the stale-session reconciler (Subtask 5).
- **Sub-categorize blocked**: add `stop_reason` (value = `GoalRunnerStopReason` name) to
  `goal_finished` — `GoalFinishedRequest`, `GoalRunnerTelemetryEmitter.kt:97`, schema branch. Add
  `abandoned` to `goalFinishedStatusEnum` in the schema. Keep `status` for back-compat.

## Acceptance Criteria

1. A goal issue that completes emits exactly one `skillbill_goal_issue_finished` event with
   `status = completed` and `parent_workflow_id` equal to the constant goal parent workflow id.
2. `goal_issue_finished` carries non-null `total_invocations`, `total_blocks`, `total_resumes`, and
   `first_started_at` matching the issue's actual segment history.
3. `skillbill_goal_finished` carries a `stop_reason` value drawn from the `GoalRunnerStopReason`
   enum for every non-completed segment.
4. `goalFinishedStatusEnum` in `telemetry-event-schema.yaml` permits `completed`, `blocked`, and
   `abandoned`.
5. A parity test in `GoalTelemetryEmissionEventParityTest.kt` validates the
   `goalIssueFinishedEvent` schema branch and a representative envelope.
6. The `goal_issue_progress` table is created idempotently and does not alter existing rows on
   legacy databases.

## Non-goals

- Detecting abandonment at emission time (impossible without future knowledge) — handled
  retrospectively by the reconciler in Subtask 5.
- Changing the goal runner's blocking/selection logic.

## Dependencies

None. Subtask 5 consumes the `abandoned` status this subtask introduces.

## Validation strategy

- Unit test: run a goal through two blocked segments then a completed segment; assert one
  `goal_issue_finished` with `total_invocations = 3`, `total_blocks = 2`, `total_resumes` correct,
  and `goal_finished.stop_reason` set on each blocked segment.
- Parity test per AC 5. `(cd runtime-kotlin && ./gradlew check)` green.

## Next path

Subtask 2.
