# SKILL-65.1 · Subtask 6 — Lifecycle Telemetry and Stats

Parent: [SKILL-65.1 full parity](./spec.md)
Issue key: SKILL-65.1
Status: Draft

## Scope

Bring the runtime to telemetry/stats parity with `bill-feature-task`
(`feature_implement_started`/`feature_implement_finished` + `*_stats` MCP tool +
CLI stats + remote-stats mapping). The runtime today has rich **runtime-owned**
per-phase records and an append-only ledger but feeds nothing to the
stats/reporting layer. This subtask adds a `feature-task-runtime` lifecycle
telemetry family, mirroring the SKILL-66 (`feature-goal-telemetry`) pattern, so
runs are visible to stats/reporting **without** discarding the runtime-owned
observability that is the SKILL-65 thesis (the two coexist; per-phase records
remain the ground-truth source).

## Acceptance Criteria

1. A `feature-task-runtime` lifecycle telemetry event family
   (started/finished, with the resolved size, phase outcomes, completion status)
   is defined in `orchestration/contracts/telemetry-event-schema.yaml` with
   strict payload schemas and the existing contract-version guards
   (`TelemetryEventSchemaContractVersionTest`,
   `TelemetryEventInputSchemaParityTest`) kept green.
2. The events are registered in `McpToolRegistry` and validated in dispatch via
   `TelemetryEventSchemaValidator`, following the existing telemetry tool pattern.
3. Lifecycle telemetry persists through `LifecycleTelemetryService` ->
   `LifecycleTelemetryRepository` -> `LifecycleTelemetryStore` with record
   mapping, exactly like the implement/verify/goal families.
4. The runtime emits started on run open and finished on terminal outcome
   (completed / blocked / decomposed-at-planning / error), sourced from the
   runtime's own per-phase records — emission is runtime-owned, not agent
   self-report.
5. A `*_stats` MCP tool and a `feature-task-runtime-stats` CLI command aggregate
   the family, and a remote-stats workflow-name mapping is added
   (`McpToolDispatcher.telemetryRemoteStats` / `RemoteStatsRequest`).
6. The runtime-owned per-phase records and ledger are unchanged and remain the
   authoritative observability source; telemetry is additive.

## Non-Goals

- No removal or weakening of the per-phase records/ledger (the SKILL-65
  observability model stays primary).
- No change to the implement/verify/goal telemetry families beyond adding the new
  family alongside them.

## Dependency Notes

- Depends on: subtask 3 (terminal phases) + subtask 4 (resolved size) + subtask 5
  (decompose terminal outcome) so the finished event can report the complete
  lifecycle and every terminal status.
- Reference implementation: SKILL-66 subtasks 1-5 (contract → persistence →
  emission → stats tool/CLI → remote-stats + gate).

## Validation Strategy

- Schema-coherence + contract-version parity tests for the new event family.
- Persistence + emission tests (started on open, finished per terminal status).
- Stats aggregation tests (MCP tool + CLI) and a remote-stats mapping test.
- `(cd runtime-kotlin && ./gradlew check)` and `skill-bill validate` pass.

## Next path

Proceed to [subtask 7 — Goal-Runner Cooperation and Continuation Entry](./spec_subtask_7_goal-runner-cooperation-and-continuation.md),
or run `skill-bill goal SKILL-65.1`.
