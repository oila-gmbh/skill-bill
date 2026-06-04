# SKILL-66 - feature-goal telemetry

Created: 2026-06-03
Status: Draft
Issue key: SKILL-66
Parent: builds on SKILL-51/56/58/61/64 goal-runner work; brings `bill-feature-goal` to telemetry parity with `bill-feature-task` / `bill-feature-verify`

## Decomposition

This feature is decomposed because full telemetry parity spans four distinct
runtime contracts plus a closing integration gate, in strict dependency order:

1. the goal telemetry event contract — event names, strict payload schemas, and
   schema-coherence tests (contracts + mcp registry surface);
2. lifecycle-telemetry persistence for the new event family (ports +
   application records + sqlite);
3. runtime-owned emission from the goal runner at every lifecycle boundary
   (application);
4. the `goal_stats` MCP tool and `goal-stats` CLI aggregation surface (mcp +
   cli);
5. remote-stats integration, docs, and the closing validation gate
   (mcp + docs + gate).

Implement on one branch with a commit per subtask:

1. [Goal Telemetry Contract and Schema](./spec_subtask_1_goal-telemetry-contract-and-schema.md)
2. [Goal Telemetry Persistence](./spec_subtask_2_goal-telemetry-persistence.md)
3. [Goal Runner Runtime Emission](./spec_subtask_3_goal-runner-runtime-emission.md)
4. [Goal Stats MCP and CLI Surface](./spec_subtask_4_goal-stats-mcp-and-cli-surface.md)
5. [Remote Stats Integration and Validation Gate](./spec_subtask_5_remote-stats-integration-and-validation-gate.md)

## Sources

- Scoping exploration on 2026-06-03 confirmed the current state:
  - `bill-feature-task` and `bill-feature-verify` have full lifecycle telemetry:
    `feature_implement_started/finished`, `feature_verify_started/finished`,
    `*_stats` MCP tools, `implement-stats` / `verify-stats` CLI commands, and
    `telemetry_remote_stats` workflow mappings (`"implement"` ->
    `"bill-feature-task"`, `"verify"` -> `"bill-feature-verify"`).
  - `bill-feature-goal` (`GoalRunner` + `skill-bill goal`) has **no** telemetry
    family at all: no `goal_started` / `goal_finished` events, no stats tool,
    and no remote-stats mapping. Its existing goal-observability and
    goal-progress events (contract_version 0.1 schemas under
    `orchestration/contracts/`) are stored in workflow `artifacts_json` and are
    invisible to the stats/reporting layer.
- Telemetry architecture facts confirmed during scoping:
  - event payload contracts live in
    `orchestration/contracts/telemetry-event-schema.yaml` (schema version
    1.0.0), pinned by `TELEMETRY_EVENT_CONTRACT_VERSION` and guarded by
    `TelemetryEventSchemaContractVersionTest` and
    `TelemetryEventInputSchemaParityTest`;
  - MCP tool names, descriptions, and input schemas live in
    `runtime-mcp/.../McpToolRegistry.kt`; dispatch in `McpToolDispatcher.kt`
    validates payloads via `TelemetryEventSchemaValidator` before handlers run;
  - lifecycle telemetry flows through `LifecycleTelemetryService` ->
    `LifecycleTelemetryRepository` (port) -> `LifecycleTelemetryStore`
    (sqlite), with record mapping in `LifecycleTelemetryRecordMappers.kt`;
  - remote stats flow through `McpToolDispatcher.telemetryRemoteStats` and
    `RemoteStatsRequest`, with per-workflow name mapping.
- The SKILL-65 design principle, which this feature extends to telemetry: when
  the runtime owns the loop, observability should be runtime-owned ground
  truth, not agent self-report. `GoalRunner` owns the goal loop, so goal
  telemetry must be emitted by the runtime at the lifecycle boundaries it
  already controls — unlike implement/verify, where the orchestrating agent
  self-reports via MCP telemetry tools.
- Boundary contracts from `runtime-kotlin/ARCHITECTURE.md` and
  `RuntimeArchitectureTest`: application must not depend on Clikt/MCP/JDBC;
  schema validators are reached only through domain-owned ports; persistence
  types stay in their owning modules; `runtime-core` is the only composition
  root.

## Problem

`bill-feature-goal` is the only first-class workflow family with no telemetry.
Goal runs start, progress through subtasks, block, and finish without producing
a single stats-visible event. Three consequences follow:

1. **No outcome accounting.** There is no way to answer "how many goal runs
   completed vs blocked, and where do they block?" The implement and verify
   families answer the equivalent questions today via their stats tools.
2. **Invisible to remote stats.** `telemetry_remote_stats` knows `implement`
   and `verify` but not `goal`, so goal work is absent from any aggregated
   remote view even though the local runtime has perfect knowledge of it.
3. **Ground truth is discarded.** Ironically, the goal runtime has *better*
   facts than implement/verify — it owns the loop, the clock, and per-subtask
   outcomes — but records them only as run-scoped observability artifacts that
   the stats layer cannot see.

SKILL-66 closes the gap: runtime-emitted lifecycle telemetry for goal runs,
persisted through the existing lifecycle-telemetry seam, aggregated by a
`goal_stats` tool and `goal-stats` CLI command, and mapped into
`telemetry_remote_stats`.

## Goals

1. Record `goal_started`, `goal_subtask_finished`, and `goal_finished`
   telemetry events for every `skill-bill goal` run, emitted by the runtime
   (GoalRunner) with runtime-owned timestamps, durations, and counts — never
   agent self-report.
2. Define strict payload contracts for the new event family in
   `orchestration/contracts/telemetry-event-schema.yaml`, with the same
   coherence and parity guarantees as existing events.
3. Persist the events through the existing `LifecycleTelemetryService` /
   `LifecycleTelemetryRepository` / `LifecycleTelemetryStore` seam, adding a
   migration where needed.
4. Provide a `goal_stats` MCP tool and a `goal-stats` CLI command with
   aggregates equivalent to `implement-stats` / `verify-stats`: run counts,
   completion/blocked rates, durations, and per-subtask outcome breakdowns.
5. Integrate the family into `telemetry_remote_stats` (`"goal"` ->
   `"bill-feature-goal"`) and `telemetry_proxy_capabilities` where applicable.
6. Keep `bill-feature-goal` runtime behavior otherwise unchanged: same loop,
   same gates, same observability/progress artifacts, same CLI output.

## Non-Goals

- Do not change or replace the existing goal-observability and goal-progress
  event schemas (contract_version 0.1) or their artifact storage; lifecycle
  telemetry complements them, it does not migrate them.
- Do not modify implement/verify telemetry events, stats, or storage.
- Do not expose MCP emission tools (`goal_started` etc.) for agents to call;
  emission is runtime-owned by design. Only the read-side (`goal_stats`) is an
  MCP tool.
- Do not build dashboards, visualizations, or alerting.
- Do not backfill telemetry for historical goal runs.
- Do not change the remote telemetry proxy server itself; only the local
  mapping/capability surface.
- Do not add telemetry to the experimental `feature-task-runtime` family
  (SKILL-65 owns its own observability story and promote/kill decision).

## Target User Experience

A maintainer runs a goal as today; telemetry is recorded invisibly:

```bash
skill-bill goal SKILL-XX
```

Afterwards, local aggregates are available:

```bash
skill-bill goal-stats --format json
```

```json
{
  "runs": 14,
  "completed": 11,
  "blocked": 3,
  "completion_rate": 0.79,
  "avg_duration_ms": 5460000,
  "subtask_outcomes": {"complete": 52, "blocked": 3, "skipped": 1},
  "top_blocked_subtasks": [
    {"issue_key": "SKILL-61", "subtask_id": 3, "blocked_reason": "..."}
  ]
}
```

The same aggregates are reachable as the `goal_stats` MCP tool, and remote
aggregation accepts the new family:

```
telemetry_remote_stats workflow="goal" since="30d"
```

A telemetry persistence failure during a goal run fails loudly with a typed
error; it is never swallowed.

## Acceptance Criteria

1. Every `skill-bill goal` run records exactly one `goal_started` event at loop
   start and exactly one `goal_finished` event at terminal outcome (completed
   or blocked), with runtime-owned `started_at`/`finished_at`/`duration_ms`,
   issue key, subtask totals, and terminal status.
2. Every subtask that reaches a terminal outcome within a run records one
   `goal_subtask_finished` event carrying subtask id, name, terminal status
   (`complete`/`blocked`/`skipped`), runtime-owned timing, attempt/resume
   facts, and `blocked_reason` when blocked.
3. All three event payloads are defined as strict branches
   (`additionalProperties: false`) in
   `orchestration/contracts/telemetry-event-schema.yaml`; schema parity and
   coherence tests (`TelemetryEventSchemaContractVersionTest`,
   `TelemetryEventInputSchemaParityTest`, and the x-coherence-checks
   discipline) cover the new family.
4. Events persist through `LifecycleTelemetryRepository` /
   `LifecycleTelemetryStore` with a `schema_migrations` entry if new
   tables/columns are required; malformed rows loud-fail with typed errors.
5. Emission lives in the goal-runner application layer behind the existing
   service seam; timestamps and durations are sourced from the runtime clock,
   never from agent-reported values; a telemetry write failure fails the run
   loudly rather than being swallowed.
6. A `goal_stats` MCP tool and a `goal-stats` CLI command report: total runs,
   completed/blocked counts and rates, duration aggregates, per-subtask
   outcome breakdown, and most-recent-run summary; both honor the established
   `--format` conventions and empty-store behavior of
   `implement-stats`/`verify-stats`.
7. `telemetry_remote_stats` accepts `"goal"` and `"bill-feature-goal"` and maps
   them to the goal family; `telemetry_proxy_capabilities` reflects the family
   where the existing pattern requires it.
8. `bill-feature-goal` behavior is otherwise unchanged: goal CLI output,
   observability/progress artifacts, manifest updates, and blocking semantics
   are byte-equivalent for runs that do not hit telemetry failures.
9. Architecture boundaries hold: `RuntimeArchitectureTest` passes; no
   application dependency on MCP/Clikt/JDBC is introduced.
10. Maintainer validation passes:
    - `skill-bill validate`
    - `(cd runtime-kotlin && ./gradlew check)`
    - `npx --yes agnix --strict .`
    - `scripts/validate_agent_configs`

## Design Notes

- **Runtime-owned emission, not MCP self-report.** Implement/verify telemetry
  is agent self-reported because the agent owns those loops. The goal runtime
  owns its loop, so the honest design is direct emission from `GoalRunner`'s
  lifecycle boundaries (`Started`, `SubtaskProgressed` terminal transitions,
  `Finished`/`Blocked`). This is strictly stronger ground truth and removes an
  entire class of "agent forgot to call the tool" gaps.
- **One contract, two consumers.** Payload shapes live in
  `telemetry-event-schema.yaml` so the local store and the remote-stats path
  validate against a single source of truth, exactly like existing events.
- **Reuse the lifecycle seam.** New repository methods and records follow the
  `LifecycleTelemetryRecordMappers` pattern; no parallel telemetry pipeline.
- **Loud-fail on telemetry write failure.** Consistent with the repo-wide
  loud-fail principle and with how lifecycle telemetry behaves today; a goal
  run must not silently produce no accounting. If this proves too strict in
  practice it is a one-line policy change at the emission seam, recorded in
  `agent/decisions.md`.
- **Stats parity, not stats novelty.** `goal-stats` mirrors the shape and
  conventions of `implement-stats`/`verify-stats`; the only goal-specific
  addition is the per-subtask outcome breakdown, which is the family's reason
  to exist.
- **Strictly additive.** No existing event branch, table, tool, or CLI command
  changes shape.

## Validation Strategy

- Contract tests: schema parity, contract-version pin, registry/inputSchema
  coherence for the new family (subtask 1).
- `runtime-infra-sqlite` repository + migration tests and an application
  persistence-port test for the new records (subtask 2).
- Application tests with a fake launcher and fake clock proving exact event
  counts and payloads per run outcome (completed, blocked mid-run, resumed
  run, skipped subtask), and loud-fail on telemetry write failure (subtask 3).
- CLI tests and MCP golden tests for `goal-stats`/`goal_stats`, including the
  empty-store case (subtask 4).
- Dispatcher tests for the remote-stats mapping plus the full maintainer
  command set as the closing gate (subtask 5).

## Open Questions

- Should `goal_subtask_finished` also be emitted for subtasks completed in a
  *previous* run segment when a blocked goal is resumed, or only for subtasks
  reaching terminal state within the current process? (Leaning: only within
  the current process — resume must not double-count; dedupe by
  `(issue_key, subtask_id, workflow_id)`.)
- Should `goal_finished` be emitted on a blocked outcome with
  `status: "blocked"`, or should blocked runs emit only `goal_started` +
  per-subtask events until a later segment finishes? (Leaning: emit
  `goal_finished` per run segment with terminal status, and let stats group by
  issue key; this keeps every segment accounted for.)
- Does `telemetry_proxy_capabilities` enumerate workflow families explicitly
  (requiring a change) or advertise capabilities generically (no change)?
  Subtask 5 resolves this from code, not assumption.
- Should `goal-stats` live as a top-level CLI command (matching
  `implement-stats`/`verify-stats`) or as `skill-bill goal stats` under the
  goal group? (Leaning: top-level `goal-stats` for parity; the goal group
  stays runtime-only.)
