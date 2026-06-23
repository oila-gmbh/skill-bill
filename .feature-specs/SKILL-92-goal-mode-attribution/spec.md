# SKILL-92 Goal Telemetry Mode Attribution and Prose-Goal Emission

## Status

- Status: Draft
- Issue: `SKILL-92`
- Mode: single_spec
- Purpose: make prose-mode and runtime-mode goal runs distinguishable and both recorded in
  goal telemetry, by (a) adding a `mode` discriminator to the goal telemetry record/contract
  and (b) emitting goal-level lifecycle telemetry for `bill-feature-goal mode:prose` runs,
  which today write nothing at the goal level. Together these let the telemetry loop compare
  prose vs runtime at the goal level — the only feature-task family that runs autonomously
  end-to-end, so the comparison is free of the human-pacing confound that distorts
  single-spec feature-task wall-clock.
- Scope: a `mode` field on the goal lifecycle requests/records, the `goal_run_sessions`
  table (additive-optional column via the self-healing path), and the goal lifecycle event
  contract; the runtime goal emitter stamping `mode=runtime`; a goal-lifecycle emission
  surface the in-session prose goal orchestrator can call (mirroring the existing
  `feature_task_prose_*` lifecycle tools) stamping `mode=prose`; the prose goal skill wiring
  to emit started / per-subtask / finished; and a per-mode breakdown surfaced by `goal_stats`.
- Non-goal: token estimation (owned by SKILL-91); per-subtask agent attribution (owned by
  SKILL-89); changing the goal phase loop, decomposition, subtask scheduling, or runtime
  supervision behavior; any new goal-execution capability — this is purely telemetry
  attribution and coverage.

## Problem Statement

Goal-level telemetry exists only for runtime goal runs, and carries no mode tag, so the
prose-vs-runtime goal comparison is structurally impossible today:

1. **`goal_run_sessions` is runtime-only.** It is written exclusively by
   `GoalRunnerTelemetryEmitter` (in `skillbill.application.goalrunner`), which belongs to the
   foreground `skill-bill goal` runtime. `bill-feature-goal mode:prose` runs an in-session
   subtask loop that never touches that runner. Each prose subtask emits its own
   `feature_task_prose` row, but the goal that spawned them is never recorded. Prose goals
   are invisible at the goal level.
2. **There is no agent-callable goal lifecycle surface.** `goal_started`,
   `goal_subtask_finished`, and `goal_finished` are runtime-internal telemetry events, not
   MCP tools. The only goal MCP tool is the read-only `goal_stats`. So even though the prose
   feature-task path has lifecycle MCP tools (`feature_task_prose_started` /
   `_finished`), the prose **goal** orchestrator has no equivalent way to emit.
3. **No mode discriminator.** `goal_run_sessions` has no `mode` column, and neither do the
   `GoalStartedRequest` / `GoalFinishedRequest` models or the goal lifecycle event contract.
   Even if prose goals emitted, nothing would separate them from runtime goals.

The consequence, observed directly in the data: 79 goal rows, all runtime, ~4% goal-level
completion (61 blocked / 3 completed / 15 in-flight) — with zero prose-goal rows to compare
against. The autonomous head-to-head the data is meant to support cannot be run.

## Desired End State

- The goal lifecycle requests/records, the `goal_run_sessions` row, and the goal lifecycle
  event contract all carry a `mode` value of `prose` or `runtime`.
- The runtime goal emitter stamps `mode=runtime` on every goal it already records — no
  behavior change beyond the new field.
- An agent-callable goal-lifecycle emission surface exists (mirroring the
  `feature_task_prose_*` lifecycle tools), so the in-session prose goal orchestrator can emit
  a goal-started, a per-subtask-finished, and a goal-finished record, each stamped
  `mode=prose`. Prose goals become first-class goal-telemetry rows.
- `bill-feature-goal mode:prose` (`skills/bill-feature-goal/content.md`) is wired to call
  that surface at goal start, after each subtask, and at goal completion/termination, with
  the same crash-safe, idempotent emission discipline the runtime path uses (a resumed prose
  goal does not double-count, and a re-emit of an already-recorded boundary is a no-op).
- `goal_stats` reports its existing aggregates broken down by `mode`, so prose and runtime
  goal completion rates, blocked rates, and durations can be read side by side.
- Existing pre-feature goal rows — all of which are runtime by construction, since prose goal
  emission did not exist before this feature — are safely attributable to `runtime`, and the
  aggregator never miscounts a null-mode legacy row as prose.

## Mode Back-Fill Safety (resolved)

Back-filling existing `goal_run_sessions` rows to `mode=runtime` is factually correct here,
not a guess: prior to this feature there was **no** mechanism for a prose goal to write a
goal row, so every pre-existing row originated from the runtime emitter. The spec may either
default the new column to `runtime` or treat null as runtime in the aggregator; whichever is
chosen must be stated, and the choice must guarantee no pre-feature row is ever counted as
prose.

## Implementation Map (reference, non-binding)

- Goal table + migration: `GoalTelemetryMigration.kt` (`CREATE TABLE goal_run_sessions`);
  self-healing column add in `DatabaseColumnMigrations.kt`.
- Goal INSERT/UPDATE: `GoalTelemetrySaveSupport.kt`.
- Request/record models: `GoalStartedRequest` / `GoalFinishedRequest` in
  `LifecycleTelemetryRequests.kt`; the matching `GoalStartedRecord` / `GoalFinishedRecord`
  telemetry models.
- Runtime emitter (stamps `runtime`): `GoalRunnerTelemetryEmitter.kt`.
- Event contract: `orchestration/contracts/telemetry-event-schema.yaml` `goalStartedEvent`
  (~line 1247), `goalSubtaskFinishedEvent`, `goalFinishedEvent` (~line 1354); bump
  `contract_version` and keep the Kotlin `TELEMETRY_EVENT_CONTRACT_VERSION` constant in
  lockstep.
- Prose emission surface: new goal-lifecycle MCP tools (or an equivalent agent-callable CLI
  emit command) registered alongside the existing lifecycle handlers in
  `McpToolRegistry.kt` / `McpLifecycleToolHandlers.kt`, routed through
  `LifecycleTelemetryService.kt` to the same goal save support — mirroring how
  `feature_task_prose_started` / `_finished` are wired.
- Prose goal wiring: `skills/bill-feature-goal/content.md` (mode:prose subtask loop) calls
  the surface at goal start, per subtask, and at completion.
- Stats: `GoalWorkflowStats` / `GoalRunSummary` in `ReviewStatsModels.kt`;
  `GoalWorkflowStatsSupport.kt` builder; `ReviewStatsRuntime.kt`; the `goal_stats` MCP
  payload (`toMcpMap`).

## Acceptance Criteria

1. The goal lifecycle requests/records and the `goal_run_sessions` row carry a `mode` field
   whose value is exactly `prose` or `runtime`.
2. A new `mode` column is added to `goal_run_sessions` through the self-healing column-ensure
   path, so an existing database upgrades in place without a destructive migration.
3. The runtime goal emitter (`GoalRunnerTelemetryEmitter`) stamps `mode=runtime` on every
   goal it records, with no other behavioral change.
4. An agent-callable goal-lifecycle emission surface exists (mirroring the
   `feature_task_prose_*` lifecycle tools) that records a goal-started, per-subtask-finished,
   and goal-finished entry, each stamped `mode=prose`.
5. `bill-feature-goal mode:prose` is wired to emit via that surface at goal start, after each
   subtask, and at goal completion/termination, producing a `goal_run_sessions` row and the
   corresponding `goal_subtask_events` rows for a prose goal run.
6. Prose goal emission is crash-safe and idempotent: a resumed prose goal does not
   double-count subtasks or duplicate the started/finished boundary, and re-emitting an
   already-recorded boundary is a no-op — matching the runtime path's discipline.
7. The goal lifecycle event contract carries `mode` on the started/finished events, the
   `contract_version` is bumped, and the Kotlin contract-version constant is updated to match
   so schema-parity validation passes.
8. `goal_stats` reports its existing aggregates (completion/blocked counts, durations,
   subtask outcomes) broken down by `mode`, so prose and runtime goals are directly
   comparable.
9. No pre-feature goal row is ever counted as `prose`: legacy null-mode rows are treated as
   `runtime` (the chosen mechanism — column default or aggregator coalesce — is stated), and
   a test asserts this.
10. Tests cover: the runtime emitter stamping `runtime`; the prose emission surface stamping
    `prose` and persisting a full started→subtask→finished sequence; idempotent re-emit;
    self-healing column add against a pre-feature database; and the `goal_stats` per-mode
    breakdown including the legacy-row attribution rule.

## Non-Goals

- Token estimation or cost capture (SKILL-91); per-subtask agent attribution (SKILL-89).
- Any change to goal decomposition, subtask scheduling, the phase loop, or runtime
  supervision; this spec adds only telemetry attribution and prose-goal coverage.
- Retroactive reconstruction of prose goals that ran before this feature — they were never
  recorded and cannot be recovered; only runtime legacy rows exist to attribute.

## Validation Strategy

- Unit-test the mode field on requests/records and the runtime emitter stamping `runtime`.
- Integration-test the prose emission surface end-to-end: emit started → two subtask-finished
  → finished, assert a `mode=prose` `goal_run_sessions` row plus matching
  `goal_subtask_events`, then re-emit the finished boundary and assert no duplicate.
- Run the column-ensure path against a database seeded on the pre-feature goal schema; assert
  the `mode` column appears and existing rows attribute to `runtime`.
- Assert `goal_stats` returns a per-mode breakdown and that a legacy null-mode row lands in
  the runtime bucket, never prose.
- Run `./gradlew check` and confirm contract-version lockstep / schema-parity validation
  passes.

## Relationship to SKILL-91

Independent of SKILL-91 (token estimation) and shippable on its own. The two are
complementary: SKILL-91 gives the **cost** axis for feature-task runs; SKILL-92 gives the
**mode-attributed goal** axis. Only with both can the telemetry loop answer "prose vs runtime,
autonomous, head to head" on both timing/recovery and cost.

## Next

```bash
Run bill-feature-task on .feature-specs/SKILL-92-goal-mode-attribution/spec.md
```
