# SKILL-52.5 · Subtask 4 — Feature task engine typing

Parent spec: [.feature-specs/SKILL-52.5-open-boundary-elimination/spec.md](./spec.md)
Issue key: SKILL-52.5
Subtask order: 4 of 7
Depends on: subtask 3
Branch model: same-branch, commit per subtask

## Purpose

Type the `skillbill.engine.featuretask.*` allow-list cluster (~88 FQNs): run loop
settlement, output verification, review envelope handling, phase settlement
service methods, and artifact patch helpers that still pass raw maps between
engine steps.

## Scope

In scope:

- All allow-list FQNs under `skillbill.engine.featuretask` in
  `runtime-engine` and engine-facing port/application call sites scanned as
  public boundaries.
- Replace `outputMap`, `envelopeMap`, `parsedOutputsByPayload`, settlement
  service raw-map returns, verification gate contexts, and sibling shapes with
  typed engine models reusing taskruntime typed records from subtask 3 where
  possible (compose, do not duplicate map keys).
- `FeatureTaskPhaseSettlementService` public methods (`auditSettle`, `block`,
  `complete`, `findEnvelope`) return typed results, not maps.
- Engine tests covering verification, settlement, and run-loop checkpoint paths
  updated to typed fixtures.
- Remove retired FQNs from allow-list; ratchet decreases by ~88.

Out of scope:

- Goal runner engine surfaces (subtask 5).
- Changing run-loop control flow or retry policy.

## Acceptance Criteria

1. Zero allow-list entries remain under `skillbill.engine.featuretask` for public
   raw-map shapes in scanned modules.
2. Run loop, verification, and settlement behavior unchanged at observable
   boundaries (same loud-fail errors, same persisted artifacts modulo typing).
3. Allow-list ratchet count drops by at least the retired featuretask FQNs (~88).
4. Engine tests for settlement, verification, and checkpoint remediation pass.
5. Architecture tests pass.

## Non-Goals

- Splitting god-object engine files (SKILL-52.4 scope unless required for typing).
- MCP/CLI payload redesign beyond adapter mappers.

## Dependency Notes

- Subtask 3 typed taskruntime artifacts are inputs to many engine helpers; land
  subtask 3 first.

## Validation Strategy

```bash
(cd runtime-kotlin && ./gradlew :runtime-core:test --tests 'skillbill.architecture.*')
(cd runtime-kotlin && ./gradlew :runtime-engine:test --tests '*FeatureTask*')
(cd runtime-kotlin && ./gradlew :runtime-application:test --tests '*FeatureTask*')
```

## Next Path

`.feature-specs/SKILL-52.5-open-boundary-elimination/spec_subtask_5_goal-runner-and-workflow-goal-typing.md`
