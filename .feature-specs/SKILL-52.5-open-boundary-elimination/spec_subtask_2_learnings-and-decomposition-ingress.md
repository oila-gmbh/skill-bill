# SKILL-52.5 · Subtask 2 — Learnings and decomposition ingress

Parent spec: [.feature-specs/SKILL-52.5-open-boundary-elimination/spec.md](./spec.md)
Issue key: SKILL-52.5
Subtask order: 2 of 7
Depends on: subtask 1
Branch model: same-branch, commit per subtask

## Purpose

Retire the smallest, previously gated allow-list cluster: learnings payload
helpers, decomposition manifest codec entrypoints, `WorkflowEngine.continueDecision`,
and decomposition planning parse helpers that still accept raw planning maps at
the application boundary.

## Scope

In scope:

- Type the six `skillbill.learnings.*` surfaces previously classified
  `must_type_now`.
- Type the three `postponed_with_reason` surfaces:
  - `skillbill.workflow.decomposition.DecompositionManifestCodec.decodeMap`
  - `skillbill.workflow.decomposition.toWireMap`
  - `skillbill.workflow.engine.WorkflowEngine.continueDecision`
- Replace raw-map parameters on decomposition ingress helpers under
  `skillbill.application.decomposition.*` (`parseSubtasks`, `baseBranch`,
  `executionModel`, `specSource`, `parseStackBranches`, `parentSpecPath`, and
  siblings on the allow-list) with a typed `DecompositionPlanningResult` (or
  equivalent contract-backed DTO) built at the CLI/MCP adapter before the
  application layer.
- Remove the corresponding FQNs from `RAW_MAP_OPEN_BOUNDARY_ALLOWLIST`; allow-list
  ratchet count must decrease.
- Add or extend contract schemas under `orchestration/contracts/` when new typed
  fields need wire keys; bump contract versions per repo policy.

Out of scope:

- Workflow taskruntime artifact models (subtask 3).
- Feature task engine run loop (subtask 4).
- Persisted decomposition manifest on-disk shape changes without a contract-version
  bump and migration note.

## Acceptance Criteria

1. No public raw-map declaration remains for the learnings, decomposition ingress,
   or postponed codec/continueDecision FQNs targeted in Scope.
2. Decomposition planning ingress uses a typed DTO from adapter through
   application; adapters own any remaining wire-map parsing.
3. `WorkflowEngine.continueDecision` accepts/returns typed models; CLI/MCP wire
   compatibility preserved at adapters.
4. Allow-list ratchet count drops by at least the number of retired FQNs in this
   subtask (~33).
5. Focused tests for learnings, decomposition, and workflow continue paths pass.

## Non-Goals

- Typing durable feature-task artifacts.
- Removing `@OpenBoundaryMap` globally (subtask 7).

## Dependency Notes

- Requires subtask 1 canonical allow-list source and ratchet.

## Validation Strategy

```bash
(cd runtime-kotlin && ./gradlew :runtime-core:test --tests 'skillbill.architecture.*')
(cd runtime-kotlin && ./gradlew :runtime-domain:test --tests '*Decomposition*' --tests '*Learnings*')
(cd runtime-kotlin && ./gradlew :runtime-application:test --tests '*Decomposition*')
(cd runtime-kotlin && ./gradlew :runtime-cli:test --tests '*Decomposition*' --tests '*Learnings*')
(cd runtime-kotlin && ./gradlew :runtime-mcp:test --tests '*Decomposition*')
```

## Next Path

`.feature-specs/SKILL-52.5-open-boundary-elimination/spec_subtask_3_workflow-taskruntime-artifact-typing.md`
