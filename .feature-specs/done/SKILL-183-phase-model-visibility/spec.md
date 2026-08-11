# SKILL-183 — Surface the model the current runtime phase actually launched with

## Context

Machine-global `execution_matrix` in `~/.config/skill-bill/config.json` routes each
feature-task runtime phase to a model and optional effort. `FeatureTaskRuntimeModelResolver`
resolves a `PhaseModelDirective` per phase, `FeatureTaskRuntimeRunLoop` merges it for Cursor,
and the agent-run command builders render it as `--model` / `--effort`. After that the value is
gone: it is a launch argument, never durable state.

The consequence is that an operator cannot answer "what is the review phase actually running
on right now?" from anything but the config file plus a mental replay of the tier rules. The
config is a projection of intent; it can also be edited mid-goal. Two model-routing
mistakes are invisible today: a phase silently resolving to no directive at all (inherits the
parent model), and a phase resolving to a model the operator did not intend after a config or
tier change.

The IntelliJ plugin already surfaces live runtime state — repository, issue key, current step,
progress, subtask, active duration, planning, pause — through a status-bar widget backed by
`skill-bill` CLI JSON validated against `orchestration/contracts/ide-status-schema.yaml`. The
current phase's model belongs in exactly that surface, read from durable runtime state so it
reports what ran rather than what config would produce now.

## Intended Outcome

An operator watching a feature-task or goal run from the IntelliJ status widget can open the
status popup and see one `Model:` line naming the model (and effort, when set) that the
currently executing phase actually launched with, sourced from durable workflow state.

## Acceptance Criteria

1. A feature-task runtime phase that launches with a resolved model directive writes the model
   and, when present, the effort it actually launched with into durable per-phase workflow state.
2. A phase that launches with no model directive records no model, and existing workflow rows
   written before this change keep loading, resuming, and projecting status without error.
3. The IDE status payload carries the current phase's recorded model as an optional field, stays
   schema-valid against `orchestration/contracts/ide-status-schema.yaml`, and golden fixtures
   cover both the present and absent cases.
4. A plugin build compiled before this change still maps a payload containing the new field
   normally instead of reporting an incompatible contract.
5. `IdeStatusJsonMapper` parses the field and degrades it to null on a missing, mistyped, or
   blank value without losing the surrounding status outcome.
6. The status details popup renders exactly one additional `Model:` line when the current phase
   has a recorded model, and omits the line entirely when it does not.

## Constraints

- The durable per-phase authority is `FeatureTaskRuntimePhaseRecord` in
  `runtime-kotlin/runtime-domain/.../taskruntime/model/FeatureTaskRuntimePersistenceModels.kt`,
  persisted as a JSON artifact map under `FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY` by
  `FeatureTaskRuntimePhaseRecorder`. New fields are additive and optional on that record; absent
  keys must decode to null rather than failing the read.
- The IDE status contract change is additive-optional. Do not bump
  `IDE_STATUS_CONTRACT_VERSION` in a way that makes an existing plugin build report
  `Incompatible`: the plugin compares `contract_version` for strict equality.
- The schema sets `additionalProperties: false`, so the new key must be declared in
  `orchestration/contracts/ide-status-schema.yaml` for the producer's own validation to pass.
- Model routing itself is unchanged. This feature records and displays what was used; it does
  not alter resolution, precedence, tier defaults, or CLI rendering.
- The working tree carries an unrelated in-progress change to
  `runtime-domain/.../config/model/ExecutionMatrixModels.kt` and its test (per-phase agent
  override keys). Work over it; do not revert, stash, or extend it.
- Test bar is delete-by-default. Add tests only where they pin a nameable regression.

## Non-Goals

- No full phase-to-model table in the popup, and no projection for phases that have not run.
- No status-bar widget text or tooltip change.
- No `execution_matrix` schema work; per-phase agent override keys landed separately.
- No new telemetry event for the launched model.
- No display of the resolved agent alongside the model.

## Subtasks

1. Record the launched model on the durable phase record and emit it on the IDE status contract.
2. Surface the current phase's recorded model in the IntelliJ plugin status popup.

## Validation Strategy

- Runtime: round-trip a phase record with and without a model through
  `toArtifactMap()` and the from-wire decode; assert a pre-change artifact map (no model keys)
  decodes to null fields. Assert the emitted status wire map validates against the schema in
  both shapes via the existing `IdeStatusSchemaValidator` path.
- Contract: extend the golden fixtures with a model-present case and keep an absent case.
- Plugin: assert `IdeStatusJsonMapper` degrades a mistyped/blank model to null while the
  surrounding outcome still maps, and assert the popup line appears exactly once when present
  and not at all when absent.
- Run `(cd runtime-kotlin && ./gradlew check)` and the `intellij-plugin` test task.

## Next Path

Subtask 1 lands the durable record and the contract field; subtask 2 consumes it in the plugin.
