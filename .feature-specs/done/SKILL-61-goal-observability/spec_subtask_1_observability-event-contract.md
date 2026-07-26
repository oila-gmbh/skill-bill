---
status: Complete
---

# SKILL-61 Subtask 1 - Observability Event Contract

Parent spec: [.feature-specs/SKILL-61-goal-observability/spec.md](./spec.md)
Issue key: SKILL-61

## Scope

Define the runtime-owned observability event contract for decomposed goal runs.
Events must be cheap to emit, stable enough for CLI rendering, and durable
enough to survive interruption/resume without becoming a second workflow engine.

Decide whether the contract is represented as an existing workflow artifact, a
new sidecar artifact, or a new runtime contract schema. If a new schema is
required, apply the repo contract recipe from `AGENTS.md`.

## Acceptance Criteria

1. A concrete observability event shape is defined with required and optional
   fields.
2. The event shape covers at least issue key, subtask id, workflow phase,
   worker role, liveness class, changed-file summary, diff stat, activity
   summary, timestamp, and sequence number.
3. The design identifies the authoritative storage location for latest-event
   and run-history data.
4. Event storage has clear retention behavior so long runs do not grow
   unboundedly.
5. Event parsing/rendering fails loudly on malformed durable records if a new
   runtime schema is introduced.
6. Default rendering can omit optional heavy fields without losing the ability
   to diagnose liveness.

## Non-Goals

- Do not implement CLI watch behavior in this subtask.
- Do not add provider-specific session-file scraping as a required contract.
- Do not design a general telemetry warehouse.

## Dependency Notes

Depends on: none
This subtask establishes the contract consumed by later runtime and CLI work.

## Validation Strategy

Add unit/contract tests for event validation, sequence ordering, optional-field
handling, and malformed durable data.

## Next Path

Run bill-feature-task on spec_subtask_2_runtime-supervisor-worker-boundary.md.

## Spec Path

.feature-specs/SKILL-61-goal-observability/spec_subtask_1_observability-event-contract.md
