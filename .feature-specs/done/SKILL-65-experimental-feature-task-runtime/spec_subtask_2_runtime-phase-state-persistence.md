---
status: Complete
---

# SKILL-65 Subtask 2 - Runtime Phase State Persistence

Parent spec: [.feature-specs/SKILL-65-experimental-feature-task-runtime/spec.md](./spec.md)
Issue key: SKILL-65

## Scope

Register a new workflow family for the runtime-driven task and persist its
per-phase state, validated outputs, runtime-owned timestamps, and an append-only
per-phase attempt/event ledger. Reuse the existing `WorkflowFamily` /
`WorkflowStateRepository` / `DatabaseSessionFactory` machinery; add a SQLite
migration only if a new table or columns are required.

This subtask owns the persistence seam. It does not implement the phase loop
(Subtask 3) or any entrypoint surface (Subtask 4), and it leaves the existing
`IMPLEMENT` and `VERIFY` families untouched.

## Acceptance Criteria

1. A new `WorkflowFamily` case (e.g. `TASK_RUNTIME`, bound to
   `FeatureTaskRuntimePhaseWorkflowDefinition.definition`) and the matching
   `WorkflowFamilyKind` case are wired through `WorkflowService`'s family
   mapping and persistence methods (`save`, `get`, `list`, `latest`,
   `sessionSummary`).
2. `WorkflowStateRepository` gains family-specific methods (e.g.
   `saveFeatureTaskRuntimeWorkflow`, `get...`, `list...`, `latest...`) with a
   SQLite implementation; a `schema_migrations` entry is added if a new
   table/columns are needed. Existing families' persistence is unchanged.
3. Per-phase persistence captures: phase status, attempt count,
   `started_at`/`finished_at`/`duration`, the resolved agent id, and the
   validated output artifact for the phase.
4. An append-only phase attempt/event ledger is persisted (consistent with the
   SKILL-64 goal attempt-ledger intent), recording phase `start`, `resume`,
   `retry`, fix-loop iteration, `blocked`, and `complete` events with
   runtime-owned facts and timestamps.
5. Timestamps and durations are sourced from the runtime, never from
   agent-reported values.
6. Malformed persisted state continues to loud-fail with typed errors; no
   best-effort parsing or silent truncation is introduced.
7. Tests: `runtime-infra-sqlite` repository tests for the new family, a
   migration test if a migration is added, and an `ApplicationPersistencePort`
   test covering save/load/list/latest and ledger append/read for the new
   family.

## Non-Goals

- No phase-loop orchestration (Subtask 3).
- No CLI/MCP surface (Subtask 4).
- Do not alter `IMPLEMENT`/`VERIFY` family storage or `bill-feature-task`
  persistence.
- Do not expose persistence types outside their `model` packages or leak
  infrastructure types through application/port APIs.

## Dependency Notes

Depends on: Subtask 1 (the workflow definition and per-phase output schemas the
persisted artifacts conform to).

Provides the durable store the runner in Subtask 3 writes through.

## Validation Strategy

`runtime-infra-sqlite` repository + migration tests; application
persistence-port test for the new family and the attempt ledger;
`RuntimeArchitectureTest` continues to pass (validators/persistence stay in
their owning modules).

## Next Path

Run bill-feature-task on spec_subtask_3_runtime-phase-loop-runner.md.

## Spec Path

.feature-specs/SKILL-65-experimental-feature-task-runtime/spec_subtask_2_runtime-phase-state-persistence.md
