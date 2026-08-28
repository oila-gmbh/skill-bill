# SKILL-215 — IDE status elapsed clocks count execution, not calendar time

## Context

On 2026-08-28 the IntelliJ status popup for live goal SKILL-214 showed **Goal elapsed:
1h 29m** and **Subtask elapsed: 1h 37m** while state was `active`, progress `1/1`. A
subtask cannot have been executing longer than the goal that contains it. Operators also
see paused or blocked goals keep growing, and after resume a run that sat overnight reads
as 14 hours of work.

SKILL-148 defined both clocks as wall time from `started_at` / `current_subtask.started_at`.
A later change added `active_duration_ms` on the goal snapshot, accumulated from the parent
execution-lease heartbeat, so the **goal** clock can exclude blocked, paused, and unattended
gaps. The **subtask** clock was never moved onto that model.

### What the popup actually computes today

`StatusUiMapper.activeElapsed` prefers `active_duration_ms` plus a live tail from
`active_duration_as_of` while the lease is live. That is why goal elapsed was the smaller
number on SKILL-214.

`StatusUiMapper` still sets `subtaskElapsed = elapsed(subtaskStartedAt, now)` for Active,
and `elapsed(subtaskStartedAt, settledAt(updatedAt, now))` for paused, blocked, failed, and
stale. `subtaskStartedAt` is the child WorkItem / workflow `started_at` from
`IdeStatusProjector.goalCurrentSubtask`. Resume of the same child does not reset that
instant, so the subtask line is calendar time since first launch, including every pause and
block.

`withElapsed` only advances Active. Paused and blocked are frozen at last map. The subtask
line still already includes the gap, so freeze-at-updatedAt does not undo it. After resume
the goal line continues from the accumulator. The subtask line jumps to `now - startedAt`.

When `active_duration_ms` is omitted (zero with no live lease, or a runtime that never
wrote the field), the goal line falls back to `now - startedAt` on Active and
`updatedAt - startedAt` when settled. That fallback is the 14-hour figure on older or
unaccumulated runs.

### Not this bug

- **Pause after current subtask** staying Active until the current subtask finishes is
  SKILL-168. Clocks may tick for that unconsumed request. This ticket does not stop them
  early.
- CLI `goal status` human text. The IDE surface is `work status` JSON.
- Standalone `feature-task-runtime` / `feature-verify` families that have no goal
  accumulator. They keep today's start timestamps until a later ticket gives them one.

## Intended Outcome

The IntelliJ popup's Goal and Subtask elapsed (and `ran`, once settled) measure time the
runtime was actually executing that goal or that current subtask. Pause, block, stop, and
unattended gaps are excluded. Resume continues from the frozen totals. Subtask elapsed is
never greater than goal elapsed.

## Acceptance Criteria

1. While a feature-goal snapshot carries `active_duration_ms`, the plugin's goal elapsed
   (and settled `ran`) equals that accumulated execution time plus the live tail only while
   `active_duration_as_of` is present. It does not equal `now - started_at` across a
   pause, block, or overnight gap.
2. Subtask elapsed for a feature-goal uses an execution accumulator for the **current**
   subtask, not `now - current_subtask.started_at`. The same pause, block, and unattended
   gaps excluded from the goal clock are excluded from the subtask clock.
3. After a consumed pause, a blocked stop, or an operator stop, neither clock advances
   with wall time. The 1s widget ticker does not grow them.
4. After resume of the same goal and the same current subtask, both clocks continue from
   the values frozen at stop, then add only post-resume execution. They do not add the
   downtime.
5. When the current subtask id changes, the subtask clock resets to execution of the new
   subtask and does not keep the previous subtask's total.
6. Whenever both clocks are present, subtask elapsed is less than or equal to goal
   elapsed. The SKILL-214 inversion (subtask 1h 37m, goal 1h 29m) cannot recur.
7. A paused, blocked, failed, or terminal snapshot that carries `active_duration_ms`
   reports that total for goal elapsed even if `started_at` is many hours earlier. It does
   not tick a tail from a stale `active_duration_as_of` after the lease is released.
8. `Pause after current subtask` (`pause_requested` on an otherwise Active snapshot)
   continues to tick until the pause is consumed. Immediate stop / consumed pause / blocked
   do not.
9. Plugin unit tests cover: subtask clock using the accumulator rather than child
   `started_at` across a pause gap; resume without adding downtime; subtask not exceeding
   goal; settled states frozen; `withElapsed` not advancing paused or blocked. Runtime
   tests cover: heartbeat folds into both totals; lease reacquire after downtime does not
   count the gap for either clock; switching current subtask id resets only the subtask
   total.

## Constraints

- Keep consuming `skill-bill work status --format json` only.
- Additive optional wire on `current_subtask` (or equivalent) is allowed. Do not bump
  `IDE_STATUS_CONTRACT_VERSION` unless the existing `0.1` const cannot describe the field.
  Older plugins ignore an unknown nested property only if the schema permits it; the
  schema's `additionalProperties: false` on `current_subtask` means the field must be
  declared there.
- Plugin remains an external CLI consumer. No SQLite, no runtime-kotlin imports.
- Reuse the existing lease-heartbeat accumulator (`GoalRunnerControlState.activeDurationMs`,
  `GOAL_ACTIVE_HEARTBEAT_GAP_LIMIT_MS`). Do not invent a second wall-clock estimator in
  the plugin.
- Preserve `clearControlState` retaining the goal accumulator. The subtask accumulator
  must survive pause clear the same way when the current subtask is unchanged.
- No comments added to changed files.

## Non-Goals

- Changing SKILL-168 pause-after-current-subtask vs immediate stop.
- Making standalone feature-task-runtime or feature-verify clocks use a lease accumulator.
- Removing the goal wall-clock fallback for snapshots that still omit `active_duration_ms`
  (older CLI). Those may keep today's fallback. This ticket is about snapshots that do
  carry the field, plus giving the subtask line the same kind of field.
- Relabelling elapsed vs ran.
- Speeding up status polls (SKILL-199).

## Diagnostic Evidence

- Screenshot 2026-08-28 12:43 local, SKILL-214, state active, Goal elapsed 1h 29m,
  Subtask elapsed 1h 37m, progress 1/1.
- `intellij-plugin/.../StatusUiMapper.kt` — `activeElapsed` vs `elapsed(subtaskStartedAt, now)`.
- `IdeStatusProjector.goalCurrentSubtask` — child WorkItem / workflow `startedAt` only.
- `GoalRunnerControlRepository.advancedBy` / `acquireExecutionLease` — goal accumulator
  already skips downtime on reacquire.
- `../../../orchestration/contracts/ide-status-schema.yaml` — `active_duration_ms` is goal-level;
  `current_subtask` has `id` and `started_at` only.

## Subtask Decomposition

One implementation unit. The plugin cannot tell the truth without a current-subtask
execution total on the wire, and shipping the field without a reader leaves the popup
wrong.

## Validation Strategy

- Runtime tests on control-state accumulation and IDE status projection of the subtask
  duration.
- Plugin tests listed in acceptance criterion 9.
- Contract / golden fixture coverage for the new optional `current_subtask` field.
- Manual: pause a one-subtask goal, wait, resume. Goal and subtask must continue from the
  pre-pause values, and subtask must not exceed goal.
