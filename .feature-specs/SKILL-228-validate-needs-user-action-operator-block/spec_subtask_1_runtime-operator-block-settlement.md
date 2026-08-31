# SKILL-228 · Subtask 1 — Runtime operator-block settlement

## Scope

Make validate and build treat `failure_disposition: needs_user_action` as a
**terminal operator block**, not a repair-loop input.

Deliver:

- `terminalOutputAttempt` / `settleValidatedOutputPauseOrTerminal` path: when
  disposition is `NEEDS_USER_ACTION`, always `blockInPhase` — never
  `AttemptResult.retryableTerminal` for validate/build.
- Ensure phase settlement (blocked record + ledger) completes **before** the
  agent session can enter parent-PID hold; blocked output must not leave
  `workflow_status: running` with zero persisted evidence.
- Propagate blocked phase + `blockedReason` (from `summary` and
  `produced_outputs.blocking_reasons` when present) into goal continuation /
  subtask event / goal issue progress so CLI status is not `blocked: 0`.
- Preserve existing test
  `validation phase output block keeps repairing instead of stopping task` for
  validate `blocked` **without** explicit `failure_disposition` (defaults to
  `RETRYABLE`).

## Acceptance Criteria

1. Validate `blocked` + `needs_user_action` settles `BLOCKED` with
   `failure_disposition: NEEDS_USER_ACTION` and does not increment repair
   turns or relaunch validate.
2. Build `blocked` + `needs_user_action` follows the same terminal block path.
3. After settlement, `feature_task_phase_settlements` and/or phase records
   exist for the blocked validate/build attempt (not an empty evidence trail).
4. Goal/subtask durable state exposes a non-empty `blocked_reason` operator can
   read from `skill-bill goal status` without opening agent chat.
5. Existing validate repair-loop test (no explicit disposition) still passes.

## Non-Goals

- IdeStatus / IntelliJ UI (subtask 2).
- Classifying environmental failures from prose when disposition is omitted.
- Changing `FeatureTaskRuntimePhaseSafetyPolicy` default for validate without
  disposition.

## Dependency Notes

None. First runnable subtask.

## Validation Strategy

- Unit / runner tests in `FeatureTaskRuntimeRunnerTest` and settlement extras.
- Fixture asserting phase record status `blocked` and disposition
  `needs_user_action` after validate environmental block output.
- Regression: repair loop test unchanged for disposition-less validate block.
