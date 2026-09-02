# SKILL-230 · Subtask 2 — Concurrent planning status wire

## Scope

Report every subtask the current planning wave covers, so a 5-wide planning
wave is legible instead of appearing as one planning subtask.

Subtask 1 makes planning concurrent while `GoalPlanningStatusSnapshot` still
carries a single `currentPlanningSubtaskId`. Status then under-reports live work
and an operator cannot tell a healthy wave from a wedged one.

Deliver:

- `GoalPlanningStatusSnapshot` carries the ordered set of subtask ids the
  current planning wave covers, alongside the existing counts and state.
- `currentPlanningSubtaskId` keeps a single value equal to the lowest id in that
  set, and falls back to today's first-missing derivation when no wave is
  active. This is what keeps `IdeStatusJsonMapper` in the IntelliJ plugin and
  the VS Code extension working with no edit.
- Derive the reported set from the bounded missing-plan set in manifest order,
  capped at the fan-out cap, which is exactly the set the sweep dispatches. This
  needs no new durable state and no new hot-path query. Sourcing it from durable
  planning attempt records is acceptable only if it adds no query to the status
  read path and never reports a subtask whose plan is already checkpointed.
- `orchestration/contracts/ide-status-schema.yaml`: the `planning` object sets
  `additionalProperties: false`, so add the new optional array property there
  with a description stating its derivation. Keep `contract_version` at `"0.1"`
  unless the parity test requires otherwise; adding an optional property leaves
  existing payloads valid.
- The `IdeStatusModels` planning model and `toStatusWireMap()` emit the property
  in snake_case and omit it when empty.
- `GoalCliStatusFormatting` exposes it in JSON output and names the concurrent
  count on the human line.
- `WorkflowGoalObservabilityMcpMapping` carries it on the MCP goal status
  surface.
- `GoalPlanningStatusReasonCoherence` continues to hold: the reason text and the
  snapshot must not disagree about where planning resumes.

## Acceptance Criteria

1. The IDE status schema accepts a `planning` object carrying the new array and
   rejects a non-array value, an empty array, duplicate ids, and a set larger
   than the fan-out cap.
2. `IdeStatusSnapshot.toStatusWireMap()` emits the property with snake_case keys
   matching the schema exactly, and omits the key entirely when the set is
   empty.
3. `current_planning_subtask_id` still carries exactly one value, equal to the
   lowest id in the reported set when a wave is active, so the IntelliJ plugin
   and the VS Code extension render correctly with no source change in either.
4. A goal mid-planning with 5 concurrent plans reports all 5 subtask ids through
   `skill-bill goal status --format json` and `skill-bill work status`, and the
   human-readable line states how many subtasks are being planned.
5. A goal whose planning state is `prepared`, and a goal whose planning has not
   started, omit the property.
6. No subtask whose plan is already checkpointed appears in the reported set.
7. Planning status reason text and the snapshot stay coherent about the resume
   position, and `GoalPlanningStatusReasonCoherenceTest` passes.
8. Contract-version parity tests for the IDE status schema pass.

## Non-Goals

- IntelliJ plugin and VS Code extension changes. Backward compatibility is
  asserted by acceptance criterion 3 rather than by editing those consumers.
- A durable planning-liveness table, per-lane heartbeat, or process-level
  liveness probe for individual plan sessions.
- Per-plan timings, progress percentages, or token accounting.
- Changing fan-out behavior or the concurrency cap, which is subtask 1.
- Reworking the goal status projection for non-planning phases.

## Dependency Notes

Depends on subtask 1. The reported set is meaningless until planning actually
dispatches waves, and the cap that bounds the array comes from subtask 1's
burst schedule.

## Validation Strategy

- Schema validator tests cover the accept case and each named reject case.
- IDE status model and projector tests cover emission, omission, and the
  lowest-id derivation of `current_planning_subtask_id`.
- `IdeStatusGoldenFixturesTest` gains a goal fixture mid-wave with several
  concurrent planning subtasks; `CliWorkStatusTest` covers CLI rendering.
- Named bugs these tests are there to catch: a wave of 5 that still reports one
  planning subtask; a status read that names a subtask whose plan already
  landed; an emitted key that breaks the plugin's single-id mapper; a prepared
  goal that still advertises in-flight planning.
- Targeted Gradle module tests for `runtime-application`, `runtime-infra-fs`,
  `runtime-cli`, and `runtime-mcp`, plus `skill-bill validate`. Build and test
  execution belongs to the validate phase.

## Next Path

Run `skill-bill goal SKILL-230`.
