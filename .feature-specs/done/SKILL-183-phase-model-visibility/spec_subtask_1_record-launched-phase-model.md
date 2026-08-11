# SKILL-183 · Subtask 1 — Record the launched model on the durable phase record and emit it on the IDE status contract

## Scope

Today the resolved model directive is a launch argument and nothing more.
`FeatureTaskRuntimeModelResolver.resolve` returns a `PhaseModelDirective`,
`FeatureTaskRuntimeRunLoop` pre-merges model and effort for Cursor before handing them to the
launcher as `modelOverride` / `effortOverride`, and `AgentRunCommandBuilders` renders them onto
the child CLI. No durable state records what the phase actually launched with.

Give the launched directive a durable home and put it on the IDE status wire.

**Durable record.** `FeatureTaskRuntimePhaseRecord`
(`runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/workflow/taskruntime/model/FeatureTaskRuntimePersistenceModels.kt`)
is the per-phase authority: it already carries `resolvedAgentId`, `status`, `attemptCount`,
`startedAt`, `loopId`, `edgeIteration`, and `outputArtifact`, and is persisted as a JSON artifact
map under `FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY` by `FeatureTaskRuntimePhaseRecorder`.
Add two optional fields — the launched model and the launched effort — defaulting to null, with
the same non-blank-when-present validation style the record's `init` block already uses for its
other optional strings. Extend `toArtifactMap()` and the matching from-wire decode so both keys
round-trip, and so an artifact map written before this change (neither key present) decodes to
null fields instead of failing.

Record the value the child was actually launched with, after Cursor's model/effort merge, not the
pre-merge directive. For Cursor the merged string is what identifies the model; for the other
model-directive-capable agents model and effort stay separate. A phase that launches with no
directive records neither field.

Thread the value from the launch site to the write: `FeatureTaskRuntimePhaseStateRequest` carries
the launched model and effort, and `recordPhaseState` / `recordCompletedPhase` persist them onto
the record through the existing `phaseRecordFor` path. Do not add a second write; the value must
land in the same transaction that advances the phase, so a crash cannot leave a phase whose
recorded model disagrees with the attempt that ran.

**Status wire.** `IdeStatusSnapshot.toStatusWireMap()`
(`runtime-application/src/main/kotlin/skillbill/application/model/IdeStatusModels.kt`) gains an
optional `current_model` object mirroring the config's own vocabulary:

```json
"current_model": { "model": "gpt-5.6-luna-xhigh", "effort": "high" }
```

`effort` is optional inside it; the whole object is omitted when the current phase has no
recorded model. A nested object matches the existing `current_step` / `progress` /
`current_subtask` shape and leaves room for effort without a second top-level key. Declare it in
`orchestration/contracts/ide-status-schema.yaml` — the schema is `additionalProperties: false`,
so an undeclared key fails the producer's own validation.

`IdeStatusProjector` populates it for the feature-task-runtime and goal families by reading the
phase record for the step it already selected as `current_step`. When the projector cannot
resolve a phase record for that step, omit the field rather than guessing; this is optional
context and must never cost a status reading.

**Contract version.** Do not bump `IDE_STATUS_CONTRACT_VERSION`. The plugin compares
`contract_version` for strict equality and reports `Incompatible` on any mismatch, so a bump
would break every plugin build in the field to add an optional field they can ignore. Confirm
`IdeStatusSchemaContractVersionTest` agrees that an additive-optional field needs no bump; if it
asserts otherwise, surface that as a blocking finding rather than bumping the version.

Do not change model resolution, precedence, tier defaults, or CLI rendering. Do not add a
telemetry event.

## Acceptance Criteria

1. `FeatureTaskRuntimePhaseRecord` carries an optional launched model and an optional launched
   effort, both defaulting to null and both rejected when present-but-blank.
2. A phase record with a model and effort round-trips through `toArtifactMap()` and the from-wire
   decode with both values intact.
3. An artifact map containing neither key — the shape every pre-change workflow row holds —
   decodes to a record with null model and null effort, and the surrounding workflow row loads,
   resumes, and projects status without error.
4. A phase launched with a resolved directive persists the model the child was actually launched
   with, including Cursor's merged `model[effort=…]` form, in the same transaction that advances
   the phase.
5. A phase launched with no directive persists neither field.
6. `IdeStatusSnapshot.toStatusWireMap()` emits `current_model` with a required `model` and an
   optional `effort` when the current phase has a recorded model, and omits the key entirely
   otherwise.
7. Both emitted shapes validate against `orchestration/contracts/ide-status-schema.yaml`, and the
   golden fixtures cover a model-present case and a model-absent case.
8. `IDE_STATUS_CONTRACT_VERSION` is unchanged.
9. `(cd runtime-kotlin && ./gradlew check)` passes.

## Non-Goals

- Any plugin-side parsing or rendering; that is subtask 2.
- A per-phase model table or a projection for phases that have not run.
- Changing how models are resolved, merged, or rendered onto child CLIs.
- Recording the resolved agent id alongside the model; the record already carries it.
- A telemetry event for the launched model.

## Dependencies

None. This subtask lands first because the contract field it adds is what subtask 2 consumes.

## Validation Strategy

- Unit-test the phase-record round trip for three shapes: model + effort, model only, and an
  artifact map with neither key present (the pre-change shape). The third is the regression that
  matters — it is what proves existing rows still load.
- Assert the wire map through the existing `IdeStatusSchemaValidator` path for both the
  model-present and model-absent snapshots, so an undeclared or mistyped key fails the producer's
  own `additionalProperties: false` gate rather than reaching a client.
- Extend `IdeStatusGoldenFixturesTest` with the model-present fixture, keeping an absent case.
- Check `IdeStatusSchemaContractVersionTest` before writing the schema change: it decides whether
  an additive-optional field is allowed at the current version.
- For the Cursor path, assert the persisted model equals the merged string the builder renders,
  not the pre-merge directive, so the recorded value matches the `--model` argument the child
  actually received.
- Run the affected module tests, then `(cd runtime-kotlin && ./gradlew check)`.

## Next Path

Subtask 2 parses `current_model` in the plugin and renders the current phase's `Model:` line.
