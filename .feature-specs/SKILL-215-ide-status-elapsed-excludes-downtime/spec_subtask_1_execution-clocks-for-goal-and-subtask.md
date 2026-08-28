# SKILL-215 · Subtask 1 — Execution clocks for goal and current subtask

Parent spec: `.feature-specs/SKILL-215-ide-status-elapsed-excludes-downtime/spec.md`

## Scope

Make the IntelliJ Goal and Subtask elapsed lines measure execution, not calendar time
since first start.

Today the goal line can use `active_duration_ms` from the parent lease heartbeat. The
subtask line still uses child `started_at`. Pause, block, and overnight gaps stay in the
subtask number, which is how it outruns the goal (SKILL-214, 1h 37m vs 1h 29m) and how
resume shows 14 hours.

This subtask owns the durable current-subtask accumulator, the IDE-status wire, and the
plugin mapping. They land together.

Required behaviour:

- Persist a current-subtask execution total next to `GoalRunnerControlState.activeDurationMs`.
  Heartbeats that fold into the goal total fold into the subtask total. A gap capped by
  `GOAL_ACTIVE_HEARTBEAT_GAP_LIMIT_MS` is capped for both. Reacquire after downtime does
  not count the downtime for either.
- When the current subtask id changes, reset only the subtask total. Resume of the same
  id continues it. `clearControlState` keeps both totals when the subtask is unchanged.
- Project that total onto `current_subtask` in `work status` JSON (optional, omitted when
  there is no current subtask or no recorded execution). Reuse the existing live
  `active_duration_as_of` tail for ticking between heartbeats. Do not publish a stale
  anchor when the lease is not live (same rule as the goal field today).
- Declare the field on `current_subtask` in `ide-status-schema.yaml`. Keep
  `IDE_STATUS_CONTRACT_VERSION` at `0.1` unless that const cannot describe an additive
  optional property.
- Plugin `StatusUiMapper` (and `withElapsed`) derives subtask elapsed from that
  accumulator the same way `activeElapsed` derives the goal clock. It does not use
  `elapsed(subtaskStartedAt, now)` for feature-goal snapshots that carry the new total.
- Whenever both durations are present, subtask elapsed is ≤ goal elapsed.
- Paused, blocked, failed, and terminal mapping stay frozen. `withElapsed` still does
  not tick them. Active with `pause_requested` still ticks until the pause is consumed.

Touched areas: goal-runner control state and heartbeat, IDE status schema and projector,
`intellij-plugin` mapper / outcomes / tests. Not CLI `goal status` prose.

## Acceptance Criteria

1. A feature-goal `work status` snapshot of a live current subtask includes that
   subtask's accumulated execution time on `current_subtask`, not only `started_at`.
2. Heartbeat folding and lease reacquire after a pause or overnight gap leave the
   downtime out of both the goal total and the current-subtask total.
3. Changing current subtask id resets the subtask total to execution of the new
   subtask. Resume of the same id does not reset it.
4. `StatusUiMapper` maps that subtask total (plus live tail while `active_duration_as_of`
   is present) to subtask elapsed. A pause gap that shrinks goal elapsed relative to
   child `started_at` does not leave subtask elapsed larger than goal elapsed.
5. After consumed pause, block, or stop, neither plugin clock advances on the 1s ticker.
6. After resume, both clocks continue from the frozen totals plus new execution only.
7. `Pause after current subtask` while still Active keeps ticking until the pause is
   consumed.
8. Schema, projector, and plugin parse tests accept a snapshot with and without the new
   field. An older plugin talking to a new CLI must not crash if the field is present.
   A new plugin talking to an older CLI that omits it keeps today's subtask wall-clock
   fallback, still capped at goal elapsed when the goal total is present.
9. Tests named in parent acceptance criterion 9 pass. No comments are added to changed
   files.

## Non-Goals

- Immediate pause (vs pause after current subtask).
- Accumulators for standalone feature-task-runtime or feature-verify families.
- Removing the goal wall-clock fallback when `active_duration_ms` is absent.
- SKILL-199 poll timeout behaviour.

## Dependency Notes

- No subtask dependency.
- Builds on the existing goal `active_duration_ms` heartbeat accumulator. Does not
  replace it.

## Validation Strategy

- `GoalRunnerControlStore` (or equivalent) tests for dual fold, cap, reacquire, and
  subtask-id reset.
- `IdeStatusService` / projector tests that the subtask total is on the wire when
  recorded and omitted when not.
- `StatusUiMapperTest` for the popup inversion, resume-without-gap, freeze, and cap.
- IDE status schema validator / golden fixtures for the optional field.
- Manual: one-subtask goal, pause, wait, resume. Both lines continue. Subtask never
  exceeds goal.

## Next Path

Prepared for `skill-bill goal SKILL-215`.
