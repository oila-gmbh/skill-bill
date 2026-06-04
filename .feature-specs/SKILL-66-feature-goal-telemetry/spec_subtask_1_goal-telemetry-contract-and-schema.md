---
status: Pending
---

# SKILL-66 Subtask 1 - Goal Telemetry Contract and Schema

Parent spec: [.feature-specs/SKILL-66-feature-goal-telemetry/spec.md](./spec.md)
Issue key: SKILL-66

## Scope

Define the goal telemetry event contract: `goal_started`,
`goal_subtask_finished`, and `goal_finished` payload shapes as strict branches
in `orchestration/contracts/telemetry-event-schema.yaml`, plus the `goal_stats`
read-tool contract (name, description, input schema) in the MCP registry
surface. Extend the existing parity/coherence test discipline to the new
family.

This subtask owns the contract only. It does not persist events (Subtask 2),
emit them (Subtask 3), or implement the stats tool handler (Subtask 4). The
three emission events are runtime-internal payload contracts — they are NOT
registered as MCP tools; only `goal_stats` is.

## Acceptance Criteria

1. `telemetry-event-schema.yaml` gains strict event branches
   (`additionalProperties: false`) for `goal_started`,
   `goal_subtask_finished`, and `goal_finished`:
   - `goal_started`: issue_key, feature_name, workflow_id, subtask_total,
     resumed (boolean), started_at;
   - `goal_subtask_finished`: issue_key, workflow_id, subtask_id,
     subtask_name, status (`complete`/`blocked`/`skipped`), started_at,
     finished_at, duration_ms, attempt facts, blocked_reason (string or null);
   - `goal_finished`: issue_key, workflow_id, status (`completed`/`blocked`),
     started_at, finished_at, duration_ms, subtask counts by terminal status.
   Field shapes follow the conventions of the existing implement/verify
   branches (timestamp formats, nullability, integer bounds).
2. `goal_stats` is added to `McpToolRegistry.toolNames`, `descriptions`, and
   `inputSchemas`, with an input schema consistent with
   `feature_implement_stats` / `feature_verify_stats` (optional date-range /
   grouping arguments per the existing pattern).
3. The schema's `x-coherence-checks` section is updated per its documented
   discipline (toolnames membership, inputschemas parity, discriminator
   strategy) to cover the new family, including an explicit note that the
   three emission events are runtime-internal and intentionally absent from
   `toolNames`.
4. `TelemetryEventSchemaContractVersionTest`,
   `TelemetryEventInputSchemaParityTest`, and any toolname/coherence parity
   tests pass with the new entries; new parity assertions are added where the
   existing pattern adds per-family ones (cf.
   `TelemetryTaskRuntimeStepIdEnumParityTest` precedent).
5. The schema version handling follows the existing convention: bump only if
   the schema's own rules require it for additive branches; the pinned
   `TELEMETRY_EVENT_CONTRACT_VERSION` and schema stay in lockstep either way.
6. No dispatcher handler, persistence, or emission code is included; the
   `goal_stats` handler wiring is deferred to Subtask 4 in whatever stub form
   the registry/dispatcher tests require to stay green.

## Non-Goals

- No persistence (Subtask 2), no emission (Subtask 3), no stats aggregation or
  CLI (Subtask 4), no remote-stats mapping (Subtask 5).
- No MCP emission tools for `goal_started`/`goal_subtask_finished`/
  `goal_finished` — runtime-owned emission is a parent-spec invariant.
- No changes to existing event branches or the goal-observability/progress
  schemas (contract_version 0.1).

## Dependency Notes

Depends on: nothing — first subtask.

Provides the payload contracts Subtask 2 persists, Subtask 3 emits against,
and Subtask 4 exposes via `goal_stats`.

## Validation Strategy

Schema parity and contract-version tests in `runtime-mcp`; coherence-check
review against the schema's `x-coherence-checks` documentation;
`skill-bill validate` for repo-level contract checks.

## Next Path

Run bill-feature-task on spec_subtask_2_goal-telemetry-persistence.md.

## Spec Path

.feature-specs/SKILL-66-feature-goal-telemetry/spec_subtask_1_goal-telemetry-contract-and-schema.md
