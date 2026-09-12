# SKILL-52.5 · Subtask 3 — Workflow taskruntime artifact typing

Parent spec: [.feature-specs/SKILL-52.5-open-boundary-elimination/spec.md](./spec.md)
Issue key: SKILL-52.5
Subtask order: 3 of 7
Depends on: subtask 2
Branch model: same-branch, commit per subtask

## Purpose

Replace the largest allow-list cluster — `skillbill.workflow.taskruntime.*`
public `toArtifactMap` / `fromArtifactMap` and related helpers (~98 FQNs) —
with typed durable-record models and contract-backed keys, keeping persistence
byte-compatible or quarantining per schema-bump policy.

## Scope

In scope:

- Every allow-list FQN under `skillbill.workflow.taskruntime` in
  `runtime-domain` and related port/engine call sites that still expose public
  raw maps at the boundary scan roots.
- Introduce typed record models (data classes / sealed types) for phase handoffs,
  repair receipts, quarantine entries, checkpoint identities, validation gate
  progress, implementation attempts, audit-gap persistence, goal-continuation
  artifacts, and sibling artifact families currently encoded as `linkedMapOf`.
- Move wire serialization to private mappers in infra or adapter modules where
  the public API previously returned maps; ports expose typed models only.
- Contract schemas for new artifact record shapes in `orchestration/contracts/`
  when keys are not already owned; wire keys via existing `*Keys` objects.
- Golden or round-trip tests for representative artifact families to catch
  persistence regressions.
- Remove retired FQNs from the allow-list; ratchet count decreases by ~98.

Out of scope:

- `skillbill.engine.featuretask.*` (subtask 4).
- Changing feature-task phase semantics or validation rules.

## Acceptance Criteria

1. Zero allow-list entries remain under `skillbill.workflow.taskruntime` for
   public raw-map shapes in application/domain/ports scan roots.
2. Durable artifact read/write paths remain loud-fail on malformed records with
   existing typed errors or successor equivalents.
3. Allow-list ratchet count drops by at least the retired taskruntime FQNs (~98).
4. Representative round-trip tests cover at least one model per major artifact
   family touched (handoff, repair receipt, quarantine, checkpoint, validation gate).
5. Architecture and focused domain tests pass.

## Non-Goals

- Rewriting validators' business rules beyond typing their inputs/outputs.
- Desktop-specific UI models.

## Dependency Notes

- Subtask 2 must land first so decomposition ingress is typed and does not
  reintroduce raw maps consumed by taskruntime writers.

## Validation Strategy

```bash
(cd runtime-kotlin && ./gradlew :runtime-core:test --tests 'skillbill.architecture.*')
(cd runtime-kotlin && ./gradlew :runtime-domain:test --tests '*FeatureTaskRuntime*' --tests '*TaskRuntime*')
(cd runtime-kotlin && ./gradlew :runtime-infra-sqlite:test --tests '*FeatureTask*')
(cd runtime-kotlin && ./gradlew :runtime-engine:test --tests '*FeatureTask*')
```

## Next Path

`.feature-specs/SKILL-52.5-open-boundary-elimination/spec_subtask_4_feature-task-engine-typing.md`
