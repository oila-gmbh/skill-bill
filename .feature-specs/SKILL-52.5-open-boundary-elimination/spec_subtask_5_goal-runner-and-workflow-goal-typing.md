# SKILL-52.5 · Subtask 5 — Goal runner and workflow goal typing

Parent spec: [.feature-specs/SKILL-52.5-open-boundary-elimination/spec.md](./spec.md)
Issue key: SKILL-52.5
Subtask order: 5 of 7
Depends on: subtask 4
Branch model: same-branch, commit per subtask

## Purpose

Type goal-runner and goal-workflow allow-list clusters:
`skillbill.engine.goalrunner.*`, `skillbill.goalrunner.*` (domain/ports),
`skillbill.workflow.goal.*`, and `skillbill.engine.goalplanning.*` (~108 FQNs).

## Scope

In scope:

- Goal continuation artifact codecs, child repair wedge state, planning hydration
  artifacts, observability parsing models, subtask review state/findings, and
  goalrunner port persistence helpers still exposing public raw maps.
- Replace `toArtifactMap` / `fromArtifactMap` / `artifacts` patch maps with typed
  models; reuse taskruntime typed records where the wire shape matches.
- Goal planning envelope and projection writers typed at port boundaries.
- Infra-sqlite goalrunner codecs updated to private mappers consuming typed models.
- Remove retired FQNs from allow-list; ratchet decreases accordingly.

Out of scope:

- Application workflow wire projections (subtask 6).
- Goal routing or decomposition orchestration behavior changes.

## Acceptance Criteria

1. Zero allow-list entries remain for the goalrunner / workflow.goal / engine.goalrunner
   prefixes in scanned public boundaries.
2. Goal child resume, repair wedge, and subtask review persisted state remains
   readable; loud-fail on corrupt records preserved.
3. Allow-list ratchet count drops by at least the FQNs retired in this subtask (~108).
4. Goal runner and workflow goal focused tests pass.
5. Architecture tests pass.

## Non-Goals

- Lifecycle telemetry typing (subtask 6).
- IDE status or desktop gateway work.

## Dependency Notes

- Subtask 4 typed feature-task engine outputs feed goal-runner handoff paths.

## Validation Strategy

```bash
(cd runtime-kotlin && ./gradlew :runtime-core:test --tests 'skillbill.architecture.*')
(cd runtime-kotlin && ./gradlew :runtime-engine:test --tests '*GoalRunner*' --tests '*GoalPlanning*')
(cd runtime-kotlin && ./gradlew :runtime-domain:test --tests '*Goal*')
(cd runtime-kotlin && ./gradlew :runtime-infra-sqlite:test --tests '*GoalRunner*')
```

## Next Path

`.feature-specs/SKILL-52.5-open-boundary-elimination/spec_subtask_6_application-workflow-telemetry-and-remainder.md`
