# SKILL-208 subtask 5 — Retire shape policing and align contracts

## Scope

Delete the machinery that existed to force phase prose into a typed API, now that
nothing on the feature-task phase path consults it, and align the contract set and
architecture documentation with the shipped boundary.

The deletion boundary is narrower than "everything named in this feature", and the
difference is load-bearing. `producerProjectionGateReason` is called by
`GoalPlanningSweep` and by `GoalPlanningPreparationCheckpoint` for `preplan` and
`plan`, and goal planning is out of scope for SKILL-208. The function, the
planning-projection schema, `FeatureTaskRuntimePlanningProjectionContract`, its
contract-version constant, its validator, and its typed error therefore survive
this feature. What is removed is the feature-task phase path's use of them and any
constant, loop id, cap, or edge that only that path referenced.

In scope:

- Delete `orchestration/contracts/feature-task-runtime-phase-output-schema.yaml`
  together with its Kotlin contract-version constant, schema path, validator,
  typed error, rejection-reason tests, fixture parity tests, and classpath `Copy`
  wiring.
- Delete the feature-task phase path's projection gate call sites and the
  regeneration loop ids, caps, and `RECORD_REJECTED` backward edges subtask 4 left
  behind, while keeping `producerProjectionGateReason`, the planning-projection
  schema and contract, and the goal-planning call sites intact and still covered
  by their own tests.
- Delete `FeatureTaskRuntimePhaseOutputStructuralRepair`,
  `FeatureTaskRuntimePhaseOutputDuplicateKeyMerge`,
  `FeatureTaskRuntimeSchemaFailureCorrections`,
  `FeatureTaskRuntimePhaseOutputEnvelopeWalker`, and the retry-skeleton and
  output-contract remnants in `FeatureTaskRuntimePhasePromptComposer` and
  `FeatureTaskRuntimePhasePromptDirectives`.
- Delete the repair-receipt entry rules and coverage check now that id membership
  decides coverage, keeping the receipt type only where a surviving durable record
  or the goal review state still reads it.
- Align `../../../runtime-kotlin/ARCHITECTURE.md`, the module catalog, and the runtime
  contract inventory: document the prose boundary, the runtime-owned sidecar and
  its two agent-authored entries, the derivation seam with its settlement and
  routing split and its indecisive path, the four design rules, and the
  hard-reset path for workflows created under the retired contracts.
- Record boundary history and decisions for the areas this change touches.

Out of scope: any behavior change to derivation, fact ownership, or the phase
envelope; goal-planning artifacts and their gate; telemetry schemas; pack routing.

## Acceptance Criteria

1. The retired schema, validator, repair passes, correction paths, envelope
   walker, receipt shape rules, and prompt remnants are deleted, with no dead
   constant, port, schema path, error type, or classpath `Copy` left behind on the
   feature-task phase path.
2. `producerProjectionGateReason`, the planning-projection schema, its contract
   type, its contract-version constant, its validator, and its typed error
   survive, and `GoalPlanningSweep` and `GoalPlanningPreparationCheckpoint` keep
   the behaviour and the test coverage they have today.
3. A full pipeline run reaches `commit_push` with no seam consulting a
   phase-output shape schema, and no phase prompt mentions `produced_outputs`,
   `contract_version`, or a required final JSON object.
4. Governance surfaces keep their schemas, contract versions, and parity tests:
   workflow state, decomposition manifest, platform-pack manifests, goal-planning
   preparation, handoff budgets, checkpoint policy, evidence-broker binding, and
   telemetry contracts.
5. Every surviving contract under `../../../orchestration/contracts` still has a Kotlin
   contract-version constant, a parity test, and a typed
   `Invalid<Contract>SchemaError`, with no orphaned reference to a retired one.
6. `../../../runtime-kotlin/ARCHITECTURE.md` describes the prose-centric phase boundary,
   the runtime-owned sidecar and the two governed artifacts that travel in it,
   the derivation seam with its settlement/routing split and indecisive path, the
   four design rules, and the hard-reset path for workflows created under the
   retired contracts.
7. Tests that only asserted retired shape behavior are deleted rather than
   weakened, including the transitional field-versus-prose resolution test subtask
   3 marked as such, and the surviving tests assert phase-boundary behavior: prose
   preserved, verdict routed, obligation closure enforced, indecision blocked,
   blank commit subject blocked.
8. Boundary history and decisions are recorded for the areas this change touches.

## Non-Goals

- Introducing a replacement shape contract for phase output, lenient or
  otherwise.
- Deleting `producerProjectionGateReason`, the planning-projection schema, or its
  contract type; goal planning still reads them.
- Changing behavior landed in subtasks 1 through 4.
- Touching goal-planning artifacts, telemetry schemas, or pack routing.
- Migrating in-flight workflows in place.

## Dependency Notes

- Depends on: subtask 4. Deleting the schemas and gates before the envelope flip
  would leave the phase path without a result contract of any kind.
- Unblocks: extending the envelope to the goal-planning sweep and the standalone
  verify and PR paths, at which point the surviving projection gate can be
  retired with them.

## Validation Strategy

- Run the surviving feature-task runtime and contract test sets, including every
  contract-version parity test.
- Grep the runtime for references to the retired contracts, constants, and schema
  paths to prove nothing dangles, and assert the goal-planning call sites still
  resolve.
- Run `./install.sh` if any governed skill content changes.

## Next Path

Adopt the same envelope for the goal-planning sweep and the standalone
`bill-feature-verify` and PR paths, then measure the derivation re-ask rate.
