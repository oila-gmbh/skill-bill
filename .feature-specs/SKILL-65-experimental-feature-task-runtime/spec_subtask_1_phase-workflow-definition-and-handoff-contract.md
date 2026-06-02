---
status: Draft
---

# SKILL-65 Subtask 1 - Phase Workflow Definition + Handoff Contract

Parent spec: [.feature-specs/SKILL-65-experimental-feature-task-runtime/spec.md](./spec.md)
Issue key: SKILL-65

## Scope

Define the foundation for a fully runtime-driven task pipeline: a new
`WorkflowDefinition` describing the ordered phases and their declared upstream
dependencies, the three-layer inter-phase handoff contract, and the per-phase
output artifact schemas that raise the progression gate from presence to
validity.

This subtask is pure `runtime-domain` + contract + schema work. Schema
validators live in `runtime-infra-fs` and are reached through a domain-owned
port, mirroring `WorkflowSnapshotValidator` and `DecompositionManifestValidator`.
It introduces no orchestration, no agent launching, no CLI/MCP, and no changes
to `FeatureImplementWorkflowDefinition`.

## Acceptance Criteria

1. A new `FeatureTaskRuntimePhaseWorkflowDefinition` object lives in a new
   `skillbill.workflow.taskruntime` package in `runtime-domain` and produces a
   `WorkflowDefinition` with its own `skillName`/`workflowName`,
   `workflowIdPrefix`, `contractVersion`, ordered `stepIds`, `stepLabels`, and
   `requiredArtifactsByStep`. It is independent from
   `FeatureImplementWorkflowDefinition`, which is not touched.
2. The handoff contract is modeled as three explicit layers:
   - **run-invariants** — spec reference, acceptance criteria, and
     mandates/overrides — represented as always-present, non-optional inputs;
   - **declared upstream outputs** — keyed by producing phase, resolved as the
     **latest iteration** of each declared dependency to support fix loops;
   - **derived context** — optional per-phase context (e.g. the diff for
     `review`), declared statically in the definition.
3. Each phase declares its consumed upstream outputs and derived context
   statically in the definition. There is no representation that allows the
   executing agent to choose its own inputs at runtime.
4. Each phase has a declared output artifact schema. A JSON-Schema resource plus
   a domain-owned validator port (validator implemented in `runtime-infra-fs`)
   validates phase output; well-formed output passes, empty (`{}`) and malformed
   output fail. The schema resource is copied to the classpath via the same
   resource-copy pattern used by existing schema validators.
5. Run-invariants cannot be expressed as optional/discretionary in the model;
   the type system or contract makes their absence a loud failure.
6. A `FEATURE_TASK_RUNTIME_CONTRACT_VERSION` constant is defined alongside a
   `*SchemaPaths` entry, with a parity test that fails the build if the constant
   and the schema document diverge (mirror
   `DecompositionManifestSchemaContractVersionTest`).
7. Unit tests cover: per-phase dependency-set resolution over the DAG,
   latest-iteration selection across repeated fix-loop outputs, schema
   acceptance/rejection of well-formed/empty/malformed phase outputs, and
   run-invariant presence enforcement.

## Non-Goals

- No phase-loop runner, agent launching, or process work (Subtask 3).
- No persistence/workflow-family registration (Subtask 2).
- No CLI/MCP surface (Subtask 4).
- Do not modify `FeatureImplementWorkflowDefinition` or any `bill-feature-task`
  contract.
- Do not put validators or Jackson/`Files` references in `runtime-contracts` or
  `runtime-domain`; validators belong to `runtime-infra-fs` behind a port.

## Dependency Notes

Depends on: none.

This subtask establishes the phase DAG, the handoff layers, and the per-phase
output schemas consumed by every later subtask.

## Validation Strategy

Domain unit tests for the definition and handoff resolution; `runtime-infra-fs`
validator tests for per-phase output schemas; contract-version parity test.
`RuntimeArchitectureTest` must continue to pass (no new boundary violations).

## Next Path

Run bill-feature-task on spec_subtask_2_runtime-phase-state-persistence.md.

## Spec Path

.feature-specs/SKILL-65-experimental-feature-task-runtime/spec_subtask_1_phase-workflow-definition-and-handoff-contract.md
