# SKILL-147 · Subtask 3 — Stop watching an idle goal

Parent spec: `.feature-specs/SKILL-147-goal-watch-follow-until-terminal/spec.md`

## Scope

Subtask 1 stops the watch loop when no runnable work remains
(`pending_count == 0`). That misses the case where work remains but nothing is
executing it: a usage-limit pause, a crashed runner, or a foreground goal the
user interrupted. In all three the counts stay unchanged forever and watch polls
an idle goal indefinitely.

Add an **idle** stop condition sourced from the runtime worker lease, which
already records liveness for exactly this purpose.

Files in scope:

- `runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/goalrunner/model/GoalRunnerModels.kt`
  — new projection field and extras entry.
- `runtime-kotlin/runtime-application/src/main/kotlin/skillbill/application/goalrunner/GoalRunnerStatusService.kt`
  — read lease liveness for the current subtask's workflow.
- `runtime-kotlin/runtime-cli/src/main/kotlin/skillbill/cli/goal/GoalCliCommands.kt`
  — surface the field and extend the stop condition.
- Tests in `GoalRunnerTest.kt` and `CliGoalRuntimeTest.kt`.

## Design

### Liveness source

`FeatureTaskRuntimeWorkerOwnership` carries `heartbeatAt` and `expiresAt`, and a
live worker refreshes the lease on a heartbeat interval. Read-only liveness for
the current subtask is therefore: an ownership row exists for that subtask's
`workflow_id` and its `expiresAt` is in the future as of the read.

This is a read. It must not reclaim, reconcile, fence, or write — `watch` stays
read-only, and crash reconciliation remains the startup path's job.

### Projection

Add `executionLiveness` to `GoalRunnerStatusProjection` with three values:

- `live` — an unexpired lease exists for the current subtask's workflow.
- `idle` — a runtime workflow exists for the current subtask but has no
  unexpired lease.
- `unknown` — liveness cannot be determined for this goal.

Surface it in the CLI status map as `execution_liveness` so both `goal status`
and `goal watch` render it. `goal status` gains one line; this is additive and
needs no contract-version bump.

### `unknown` is load-bearing

`unknown` must be returned, and must never stop the loop, whenever liveness is
not meaningfully readable:

- the current subtask's workflow is **not** `FeatureTaskWorkflowMode.RUNTIME`
  (a prose-mode goal has no runtime lease, and treating its absent lease as
  `idle` would stop watch immediately on a healthy run);
- no current subtask is selected, or the subtask has no `workflow_id` yet;
- the lease read throws.

Mirror the existing `resolveActiveAgent` guard in `GoalRunnerStatusService`,
which already skips non-runtime children rather than crashing the read.

### Debounce

A goal briefly has no live child lease between subtasks, while the parent closes
one workflow and opens the next. A single `idle` sample must not end the loop.

Stop only after `IDLE_STOP_CONSECUTIVE_REFRESHES = 3` consecutive `idle`
refreshes. Any `live` or `unknown` refresh resets the counter. With the default
interval this means a goal must look idle across three polls before watch gives
up, which comfortably exceeds a subtask handover while still bounding a paused
goal to a few intervals.

## Acceptance Criteria (this subtask)

1. `GoalRunnerStatusProjection` carries `executionLiveness` with values `live`,
   `idle`, and `unknown`, surfaced in the CLI status map as
   `execution_liveness` and rendered by `goal status`.
2. `GoalRunnerStatusService` resolves `live` when the current subtask's workflow
   has an ownership row whose `expiresAt` is in the future, and `idle` when a
   runtime workflow exists with no unexpired lease.
3. `unknown` is returned when the current subtask's workflow is not runtime
   mode, when no current subtask or `workflow_id` exists, and when the lease
   read throws.
4. The lease read performs no write: no reclaim, no reconcile, no fencing.
5. `goal watch` stops with `stop_reason: goal_idle` after
   `IDLE_STOP_CONSECUTIVE_REFRESHES` consecutive `idle` refreshes.
6. A `live` or `unknown` refresh resets the consecutive-idle counter to zero.
7. A prose-mode goal never stops the loop through the idle path.
8. The final refresh before an idle stop is printed, and the loop does not sleep
   after it, consistent with subtask 1.
9. A unit test covers each of `live`, `idle`, and the three `unknown` causes.
10. A CLI test asserts the debounce: two idle refreshes followed by a live one do
    not stop the loop; three consecutive idle refreshes do.
11. `skill-bill validate`, `(cd runtime-kotlin && ./gradlew check)`,
    `npx --yes agnix --strict .`, and `scripts/validate_agent_configs` pass.

## Non-Goals

- No crash reconciliation, lease reclaim, or any write from `watch`.
- No new contract file and no contract-version bump; `execution_liveness` is an
  additive projection field.
- No wall-clock timeout; the debounced idle stop replaces the need for one.
- No goal-level lease. Liveness is read from the current subtask's workflow only.
- No change to how the runtime acquires, heartbeats, or releases leases.

## Dependency Notes

Depends on subtask 1, which owns the loop, the `stop_reason` field, and the
print/sleep ordering this subtask extends. Independent of subtask 2.

This subtask relaxes the parent spec's original "Kotlin changes stay inside
`runtime-cli`" constraint, which scoped subtask 1 only. Domain and application
changes here are limited to the additive projection field and the lease read.

## Validation Strategy

```bash
(cd runtime-kotlin && ./gradlew :runtime-cli:test --tests '*CliGoalRuntimeTest*')
(cd runtime-kotlin && ./gradlew :runtime-application:test --tests '*GoalRunner*')
(cd runtime-kotlin && ./gradlew check)
skill-bill validate
npx --yes agnix --strict .
scripts/validate_agent_configs
```

Manual check: start a goal, interrupt the runner mid-subtask, and confirm a
running `goal watch` exits with `stop_reason: goal_idle` after three intervals
rather than polling forever.

## Next Path

```bash
skill-bill goal SKILL-147
```
