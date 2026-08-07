# SKILL-165 Subtask 1 — Emit goal planning status on the IDE status wire

## Scope

Runtime side only (`runtime-kotlin` + `orchestration/contracts`):

- `orchestration/contracts/ide-status-schema.yaml`: add the optional `planning`
  object per the parent spec's wire shape. `contract_version` const stays `"0.1"`.
- `runtime-application/src/main/kotlin/skillbill/application/model/IdeStatusModels.kt`:
  add a typed planning model (e.g. `IdeStatusPlanning` mirroring
  `GoalPlanningStatusSnapshot`: state wire enum, sharedPreplanPrepared,
  plannedSubtaskCount, totalSubtaskCount, optional currentPlanningSubtaskId,
  optional reason) as an optional field on `IdeStatusSnapshot`, emitted by
  `toStatusWireMap()` under `planning` and omitted when null.
- `runtime-application/src/main/kotlin/skillbill/application/work/IdeStatusProjector.kt`:
  in `projectGoal`, map `GoalRunnerStatusProjection.planning` into the snapshot.
  While planning state is not `prepared` and the goal is not settled
  (blocked/failed/terminal), prefer a planning-aware current step (id `planning`,
  label `Planning`) and a summary that mentions planning progress
  (e.g. "Goal SKILL-165 is planning subtasks (1/4 planned)."). Keep the existing
  goal execution `progress` field semantics unchanged (subtask completion counts).
- Non-goal projections (`projectRuntime`, `projectWorkflowFamily`, problem
  snapshots) must never populate `planning`.
- `IdeStatusProjector.projectGoal` lifecycle precedence: the pause-controls
  override (`projection.paused` / `projection.pauseRequested` → `PAUSED`)
  currently wins over every candidate lifecycle. Restrict it so it only replaces
  an `ACTIVE` candidate lifecycle; a candidate that is `BLOCKED` (or `FAILED` /
  `TERMINAL`) keeps its durable lifecycle. Observed live: SKILL-164's
  `needs_human` foreign-spec block would read `paused` whenever a pause request
  coexisted, hiding the one state the widget exists to prompt about.

## Acceptance Criteria (this subtask)

1. The schema accepts a goal snapshot with a valid `planning` object and rejects a
   `planning` object with an unknown property, a negative count, or an invalid
   `state` value (`IdeStatusSchemaValidatorTest` covers accept and reject cases).
2. `IdeStatusSnapshot.toStatusWireMap()` emits `planning` with snake_case keys
   exactly matching the schema, and omits the key entirely when the planning value
   is null (unit-tested).
3. `IdeStatusProjector.projectGoal` populates planning from the goal status
   projection; a goal whose planning state is not `prepared` gets step id
   `planning`, label `Planning`, and a planning-progress summary; a goal with
   `prepared` planning (or a null planning projection) keeps today's step/summary
   behavior (projector tests cover both).
4. `skill-bill work status` output for a goal mid-planning validates against the
   updated schema end-to-end (`CliWorkStatusTest` or golden fixtures updated —
   `IdeStatusGoldenFixturesTest` includes at least one goal-with-planning fixture).
5. The runtime/schema contract-version parity test
   (`IdeStatusSchemaContractVersionTest`) still passes with version `"0.1"`.
6. A goal candidate with durable state `blocked` projects
   `lifecycle_state: "blocked"` even when the goal-runner control state carries
   `paused = true` or `pause_requested = true`; an `active` candidate with those
   controls still projects `paused` (projector tests cover both orderings).

## Non-Goals

- No plugin changes (subtask 2).
- No changes to `GoalRunnerStatusService`, `planningStatus` computation, or
  planning checkpoint behavior.
- No contract-version bump.

## Dependency Notes

None. This subtask defines the wire shape subtask 2 consumes.

## Validation Strategy

Quality gate runs the runtime test suites touching this seam:
`runtime-application` (IdeStatus projector/model tests), `runtime-infra-fs`
(schema validator, golden fixtures, contract-version parity), and
`runtime-cli` (`CliWorkStatusTest`). Build/test execution belongs to the
validate phase, not implement/review phases.

## Next Path

Subtask 2 renders the new `planning` wire object in the IntelliJ plugin.
