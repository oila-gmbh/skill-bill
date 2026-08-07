# SKILL-165 — Goal planning progress in the IDE status contract and IntelliJ plugin

## Intended Outcome

When a goal is in its planning phase, the IntelliJ status bar widget shows planning
progress instead of leaving the operator guessing: a headline segment like
`Planning: 1/4` and pre-planning status (`Pre-planning: In progress` /
`Pre-planning: Done`) in the widget tooltip/detail. The runtime already computes
this state in `GoalPlanningStatusSnapshot`
(`runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/goalrunner/model/GoalRunnerModels.kt`),
populated via `GoalRunnerStatusService` → `manifestStore.planningStatus(...)`; it is
currently dropped at the IDE wire seam. This feature threads it through the
versioned `ide-status` contract and renders it in the plugin.

The widget must also report goal lifecycle truthfully. Two fidelity gaps observed
live on SKILL-164 (2026-08-07):

- `IdeStatusProjector.projectGoal` lets the goal-runner pause controls override the
  candidate lifecycle unconditionally, so a durably **blocked** goal
  (`goal_issue_progress.status = 'blocked'`, e.g. a `needs_human` foreign-spec
  block) reads `paused` whenever a pause was requested. Blocked is the one state
  the widget exists to prompt about and must outrank the pause override.
- The plugin maps a fresh `lifecycle_state: "paused"` to the same `Active`
  rendering as a running goal (`IdeStatusJsonMapper`: `"active", "paused" ->
  Active`), so an operator cannot see from the widget that a goal is sitting
  paused and waiting on them.

## Wire Contract Decision

The `planning` object is an **additive, optional** property on the IDE status
snapshot, emitted only for `workflow_family: feature-goal` when the goal status
projection carries a planning snapshot. `contract_version` stays `"0.1"`:

- Consumers already tolerate absent optional fields, and the plugin's JSON mapper
  ignores unknown keys, so both directions degrade gracefully without a bump.
- The plugin enforces strict version equality; bumping would falsely brand every
  already-installed plugin incompatible for a purely additive change.

Wire shape (snake_case, mirrors `GoalPlanningStatusSnapshot`):

```yaml
planning:
  type: object
  additionalProperties: false
  required: [state, shared_preplan_prepared, planned_subtask_count, total_subtask_count]
  properties:
    state:
      enum: [not_started, preplanned, partially_planned, blocked, prepared]
    shared_preplan_prepared: { type: boolean }
    planned_subtask_count: { type: integer, minimum: 0 }
    total_subtask_count: { type: integer, minimum: 0 }
    current_planning_subtask_id: { type: string, minLength: 1 }   # optional
    reason: { type: string, minLength: 1 }                        # optional
```

## Acceptance Criteria

1. `orchestration/contracts/ide-status-schema.yaml` accepts an optional `planning`
   object with the shape above; `contract_version` remains `"0.1"` and the
   runtime/schema parity test still passes.
2. `IdeStatusSnapshot` carries an optional typed planning value and
   `toStatusWireMap()` emits it under `planning` (omitted when null), passing the
   existing schema validation seam (`IdeStatusSchemaValidator`).
3. `IdeStatusProjector.projectGoal` populates the planning value from
   `GoalRunnerStatusProjection.planning`; non-goal families never emit `planning`.
4. While a goal's planning is not yet `prepared`, the goal snapshot's step label and
   summary reflect planning (e.g. step label `Planning`, summary mentioning
   planning progress) instead of a bare `Goal` placeholder.
5. The IntelliJ plugin parses `planning` into its domain outcome, and the status bar
   widget shows `Planning: <planned>/<total>` in the headline while planning is
   relevant (state not `prepared`), with `Pre-planning: In progress|Done` surfaced
   in the tooltip/detail.
6. Absent `planning` on the wire renders exactly today's behavior in the plugin
   (no regression for non-goal families, older CLIs, and prepared/executing goals).
7. A goal whose durable state is `blocked` projects `lifecycle_state: "blocked"`
   even while the goal-runner controls carry `paused`/`pause_requested`; the pause
   override applies only to a goal whose candidate lifecycle is active.
8. The plugin renders a fresh `lifecycle_state: "paused"` goal as a distinct paused
   presentation (headline names the paused state; tooltip keeps step, progress, and
   elapsed anchors) instead of the `Active` rendering. A stale paused snapshot
   keeps today's stale treatment; blocked/failed rendering is unchanged.

## Constraints

- Additive contract change only; no `contract_version` bump; schema-validated emit
  must stay green (`IdeStatusGoldenFixturesTest`, `IdeStatusSchemaValidatorTest`,
  `IdeStatusSchemaContractVersionTest`).
- Plugin remains read-only over the `skill-bill work status` contract; no new CLI
  flags or commands.
- Plugin keeps depending only on `com.intellij.modules.platform`.

## Non-Goals

- No changes to goal planning behavior, checkpointing, or `planningStatus`
  computation itself.
- No new CLI commands or MCP tools.
- No changes to status projection for `feature-task-prose`, `feature-task-runtime`,
  or `feature-verify` families beyond leaving them untouched.
- No plugin settings UI for toggling the new segments.

## Subtasks

1. Runtime wire planning: schema + `IdeStatusModels` + `IdeStatusProjector` +
   runtime tests/fixtures. Includes the goal lifecycle precedence fix
   (blocked outranks the pause override, AC 7).
2. Plugin planning rendering: JSON mapper + domain outcome + presentation +
   plugin tests. Includes the distinct paused presentation (AC 8). Depends on
   subtask 1's wire shape.
