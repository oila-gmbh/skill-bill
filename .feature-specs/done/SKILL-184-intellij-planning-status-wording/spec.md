# SKILL-184 — Show current phase execution progress in IntelliJ

## Context

The `skill-bill work status --format json` contract already emits an optional
`planning` object for feature goals. It includes the planning state and the
number of saved plans:

```json
"planning": {
  "state": "partially_planned",
  "planned_subtask_count": 10,
  "total_subtask_count": 15
}
```

The IntelliJ plugin already parses this object into `GoalPlanningInfo`, but its
compact progress pair is still derived from execution progress and therefore
shows the current-subtask position while planning. The same progress position
is the right place to show the current phase's execution information once
implementation begins. Audit and review are concrete examples: their loop or
pass number must be visible instead of a misleading feature-subtask fraction.

The runtime already has authoritative phase-specific counters in its durable
status projections, including audit-gap iteration, review pass, validation-gate
run, phase attempt, and backward-edge iteration data. The IDE status seam does
not yet project those counters as one bounded current-phase execution value.

## Execution Note

The first goal run reached subtask 2 validation and blocked on
`RejectedOutputDiagnosticError.Conflict` during validation-gate repair. This is
the same producer-evidence and diagnostic persistence collision addressed by
SKILL-185, not an IntelliJ-specific failure. Resume this goal after the
SKILL-185 runtime fix; SKILL-185 is a remediation prerequisite for execution,
not a change to this feature's scope.

## Intended Outcome

The IntelliJ status surfaces expose one bounded current-phase execution slot.
While planning is relevant, it shows the same planning truth as the CLI status
request:

`Planning: partially planned, 10/15 plans saved`

Once execution begins, the slot shows phase-specific execution information
such as `Audit loop 2` or `Review pass 3`. The full wording is available in the
tooltip, accessibility description, and clicked details popup; the compact bar
may use a shortened form to stay within its existing length budget.

## Acceptance Criteria

1. A relevant planning snapshot with state `partially_planned`, planned count
   `10`, and total count `15` renders `Planning: partially planned, 10/15 plans
   saved` in the tooltip, accessibility description, and details popup.
2. While planning is relevant, the compact progress pair uses
   `planned_subtask_count/total_subtask_count` without the execution
   `completed + 1` offset; one saved plan out of fifteen renders `1/15`, not a
   claim that execution subtask 1 is running.
3. The IDE status contract carries one optional typed current-phase execution
   value for non-planning phases. It identifies the current phase, execution
   kind, and current count, with an optional total only when a meaningful
   bounded total exists. It is additive, schema-validated, and omitted when no
   reliable value exists.
4. Every looping phase uses its authoritative durable counter in that value:
   audit exposes audit-gap loop iteration, review exposes review pass/loop
   number, validation exposes gate run number when applicable, and other
   backward-edge or regeneration phases expose their durable edge iteration or
   attempt without relabeling an attempt as a semantic loop.
5. The IntelliJ compact slot and full detail surfaces render the current-phase
   execution value, for example `Audit loop 2` or `Review pass 3`, instead of
   the feature-subtask fraction while that phase is active.
6. Planning text and planning-based progress are omitted after execution starts;
   once a non-planning phase is active, its current-phase execution value
   replaces the planning display. Existing execution information is retained
   when no reliable loop value exists.
7. Missing, malformed, stale, or older-runtime optional execution data degrades
   to the existing status presentation without failing the complete status
   mapping.
8. Runtime and plugin tests cover planning counts, audit/review loop counters,
   phase transitions, absent optional data, stale data, malformed data, and
   non-goal workflows.
9. The runtime and IntelliJ plugin check suites pass.

## Constraints

- Reuse the existing optional `planning` wire object and `GoalPlanningInfo`;
  add only the smallest additive current-phase execution projection needed by
  the IDE and do not duplicate loop computation in the plugin.
- Derive values from durable runtime records and status projections, not from
  elapsed time, display strings, agent output, or UI-local counters.
- Keep the plugin read-only for status polling and preserve its existing
  bounded-text and accessibility behavior.
- Keep the full planning or current-phase execution line in the tooltip and
  popup even when the compact status-bar text must shorten it to fit its
  length budget.
- Do not expose raw JSON, process output, database paths, prompts, or private
  planning artifacts.

## Non-Goals

- No changes to goal-planning, loop, retry, or persistence computation; this
  feature only projects existing authoritative counters through the IDE status
  seam.
- No contract-version bump; the current-phase execution value is additive and
  optional.
- No new IntelliJ tool window, action, setting, notification, or CLI command.
- No change to the wire meaning of existing feature-level `progress.completed`
  and `progress.total` fields.
- No change to pause, stop, stale, blocked, failed, idle, or incompatible
  lifecycle behavior beyond carrying the planning line on surfaces that already
  show relevant planning.

## Subtasks

1. Project the authoritative current-phase execution counters through the
   additive IDE status contract.
2. Update the IntelliJ presentation and details surfaces to render planning
   progress or current-phase execution information, with focused regression
   coverage and documentation.

## Validation Strategy

Run the runtime contract/projector tests and the plugin's focused mapper,
presentation, and platform fixture tests, then run:

```bash
cd /home/sermilion/StudioProjects/skill-bill/runtime-kotlin
./gradlew check

cd /home/sermilion/StudioProjects/skill-bill/intellij-plugin
./gradlew check
```

## Next Path

After both subtasks are complete, install or launch the rebuilt plugin and
observe a live goal while it is partially planned, in an audit loop, and in a
review pass to confirm the CLI and IntelliJ surfaces use the same authoritative
phase information.
