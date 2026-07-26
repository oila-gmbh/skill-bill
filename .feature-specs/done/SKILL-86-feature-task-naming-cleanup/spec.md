# SKILL-86 — Feature-task naming cleanup: retire deprecated families, align to the prose/runtime tree

Created: 2026-06-19
status: Complete
Issue key: SKILL-86

## Resolution (2026-06-19)

Implemented as a single atomic `single_spec` rename. Decisions taken during
implementation:

- **Hidden dispatcher aliases (AC2).** `feature_implement_*` is removed from the
  MCP registry / `tools/list` surface but RETAINED as hidden dispatcher aliases
  in `McpToolDispatcher` (via a shared `legacyToolAliases` map) so historical
  callers and in-flight workflow/telemetry calls still resolve. `tools/list` is
  clean; the dispatcher routes the legacy names to the prose handlers.
- **Contract version** bumped `1.0.0 -> 1.1.0` in lockstep
  (`telemetry-event-schema.yaml` + `TelemetryEventSchemaPaths.kt`).
- **Remote-stats legacy ids (AC7).** `bill-feature-task` and `implement` are
  accepted remote-stats `workflow` INPUTS that map to `feature-task-prose`;
  `remoteStatsWorkflows` (the POST-map allowlist) keeps only the mapped
  canonical values, so the legacy raw inputs are not added there (consistent
  with how `implement` is handled).
- **Strict-args parity.** `validateStrictArguments` resolves legacy aliases via
  the shared `canonicalToolName` so the alias surface keeps the strict
  unknown-argument gate.
- **Cloudflare telemetry proxy.** `docs/cloudflare-telemetry-proxy/worker.js`
  now read-unions legacy `skillbill_feature_implement_*` and new
  `skillbill_feature_task_prose_*` event names so historical and new rows both
  count. NOTE: a worker REDEPLOY is required for this to take effect in
  production.
- **Out of scope (pre-existing).** `remoteStatsWorkflowSchema` input enum still
  omits `goal` / `feature-task-runtime` / `bill-feature-goal` that the
  dispatcher accepts; pre-dates this change and is not test-enforced.

## Problem

The feature-task surface carries the scars of an unfinished rename. The intended
conceptual tree is:

```
bill_feature                         skill: bill-feature        (router)
├── feature_task                     skill: bill-feature-task   (router by mode:{prose|runtime})
│   ├── feature_task_prose           prose leaf
│   └── feature_task_runtime         runtime leaf
└── feature_goal                     skill: bill-feature-goal
```

`feature_task` is a **router node** (the `bill-feature-task` skill dispatches by
`mode`), not a record-owning leaf. Runs are recorded only at the two leaves. The
code does not match this model:

1. **The prose leaf still uses its pre-rename name.** Prose work persists under
   `WorkflowFamilyKind.IMPLEMENT` and is driven by the `feature_implement_*` MCP
   tool family — the legacy name for what is now `feature_task_prose`. The
   default mode (`bill-feature-task-prose`, plus `bill-feature-goal` and
   `bill-feature-task-subtask-runner`) calls `feature_implement_*` as its entire
   telemetry/workflow API.

2. **A duplicate runtime family exists, with inverted deprecation labels.** Two
   MCP tool families route to identical handlers and the same `TASK_RUNTIME`
   storage:
   - `feature_task_*` (e.g. `feature_task_started`) — currently treated as
     canonical
   - `feature_task_runtime_*` (e.g. `feature_task_runtime_started`) — currently
     described as the *"Deprecated alias for feature_task_*"*

   Per the tree the runtime leaf should be named `feature_task_runtime_*`, so the
   labels are backwards: `feature_task_runtime_*` is the real leaf and the bare
   `feature_task_*` family is the orphan to delete.

3. **The deprecated surface is dead weight in context.** The `feature_implement_*`
   (legacy) and `feature_task_runtime_*` (alias) families together are ~46% of
   the MCP `tools/list` payload an MCP client loads at startup.

Confirmed facts from scoping (2026-06-19):

- Both `IMPLEMENT` and `TASK_RUNTIME` already persist to ONE shared feature-task
  store, differentiated only by a mode column:
  `WorkflowService.kt` — `IMPLEMENT -> saveFeatureTaskWorkflow(record, FeatureTaskWorkflowMode.PROSE)`,
  `TASK_RUNTIME -> saveFeatureTaskWorkflow(record, FeatureTaskWorkflowMode.RUNTIME)`
  (`FeatureTaskWorkflowMode.PROSE("prose")` / `RUNTIME("runtime")` in
  `WorkflowStateRecord.kt`).
- The bare `feature_task_*` and `feature_task_runtime_*` MCP families have **no
  in-tree callers** — no skill, agent, or CLI invokes them. Runtime mode is
  driven by the `skill-bill feature-task` CLI, which emits telemetry in-process,
  not via MCP. Only the prose family (`feature_implement_*`) has live skill
  callers.
- Public telemetry ids today: prose normalizes to `bill-feature-task`
  (`TelemetrySupport.kt`: `"implement", "bill-feature-task" -> "bill-feature-task"`),
  runtime is `feature-task-runtime` (`WorkflowService.kt` `WorkflowFamily.TASK_RUNTIME(..., "feature-task-runtime")`).

## Solution

Finish the rename and delete the deprecated duplicates so the implementation
matches the tree. Two record-owning leaves remain, named symmetrically; the bare
duplicate and the legacy prose names go away.

| Concern | Before | After |
|---|---|---|
| Prose enum kind | `WorkflowFamilyKind.IMPLEMENT` | `WorkflowFamilyKind.TASK_PROSE` |
| Prose MCP family | `feature_implement_*` | `feature_task_prose_*` |
| Prose public telemetry id | `bill-feature-task` | `feature-task-prose` (legacy ids aliased at read) |
| Runtime enum kind | `TASK_RUNTIME` | `TASK_RUNTIME` (unchanged) |
| Runtime MCP family | `feature_task_runtime_*` ("deprecated alias") | `feature_task_runtime_*` (canonical) |
| Duplicate runtime family | `feature_task_*` (bare) | **deleted** |

Storage modes (`prose`/`runtime`), the shared workflow store, and the runtime
leaf's behavior are unchanged — this is a naming and surface-area cleanup, not a
behavioral change to either leaf.

### Telemetry identity (resolved)

The prose leaf's public telemetry id is renamed `bill-feature-task` →
`feature-task-prose` for symmetry with `feature-task-runtime`. To avoid splitting
the historical telemetry that powers stats, **read paths alias the legacy ids**:
`implement` and `bill-feature-task` continue to resolve to the prose lane at read
time (`TelemetrySupport.kt` normalization and `mapRemoteStatsWorkflow` in
`McpToolDispatcher.kt`). New writes use `feature-task-prose`. The
`skillbill_feature_implement_*` event names emitted by the prose lane become
`skillbill_feature_task_prose_*`; bump `TELEMETRY_EVENT_CONTRACT_VERSION`
accordingly.

## Acceptance Criteria

1. **Prose enum renamed.** `WorkflowFamilyKind.IMPLEMENT` is renamed to
   `TASK_PROSE` across the runtime; the prose lane still maps to
   `saveFeatureTaskWorkflow(..., FeatureTaskWorkflowMode.PROSE)` and reads/lists
   the `prose` store unchanged. `WorkflowFamily` (in `WorkflowService.kt`) emits
   the public id `feature-task-prose` for the prose lane.

2. **Prose MCP family renamed.** The `feature_implement_*` tool family is renamed
   to `feature_task_prose_*` in `McpToolRegistry.kt` (`toolNames`, `descriptions`,
   `inputSchemas`) and `McpToolDispatcher.kt` (handler map), preserving each
   tool's schema and behavior. No `feature_implement_*` tool name remains in the
   registry, dispatcher, or `tools/list` output.

3. **Bare `feature_task_*` family deleted.** The duplicate `feature_task_started`,
   `feature_task_finished`, `feature_task_stats`, and `feature_task_workflow_*`
   tools are removed from the registry and dispatcher. `tools/list` no longer
   contains any bare `feature_task_*` lifecycle/workflow tool.

4. **Runtime family promoted to canonical.** `feature_task_runtime_*` retains its
   tools and `TASK_RUNTIME` mapping, and its registry descriptions no longer say
   "Deprecated alias" / "Use feature_task_*" — they describe the runtime leaf
   directly.

5. **Prose-side skills migrated.** `bill-feature-task-prose`,
   `bill-feature-goal`, and `bill-feature-task-subtask-runner` content, plus
   `bill-feature-task-prose/native-agents/agents.yaml`, call `feature_task_prose_*`
   tool names instead of `feature_implement_*`. No installed skill references a
   `feature_implement_*` tool name.

6. **Telemetry contract updated.** `orchestration/contracts/telemetry-event-schema.yaml`
   replaces the `feature_implement_*` event consts with `feature_task_prose_*`,
   removes the bare `feature_task_*` consts, and keeps `feature_task_runtime_*`.
   Emitted prose event names become `skillbill_feature_task_prose_*` and
   `TELEMETRY_EVENT_CONTRACT_VERSION` is bumped.

7. **Legacy telemetry ids aliased at read.** Stats/read paths
   (`TelemetrySupport.kt` normalization, `mapRemoteStatsWorkflow`,
   `ReviewStats`) still resolve the legacy `implement` / `bill-feature-task`
   identifiers to the prose lane, so historical rows continue to count.
   Persisted workflow-state rows written under the old kind/mode keys remain
   readable (DB column migrations / `StaleSessionReconciler` /
   `WorkflowStateStore` updated as needed so existing DBs self-heal without data
   loss).

8. **Build green, contracts/goldens updated.** The full Kotlin build and test
   suite pass. Golden fixtures are regenerated to the new names (including
   `mcp-feature-task-runtime-workflow.json` and any `tools/list` snapshot), and
   tests across `runtime-mcp`, `runtime-application`, `runtime-domain`, and
   `runtime-infra-sqlite` are updated to the renamed identifiers.

9. **Docs updated.** User-facing docs that name `feature_implement_*` or the bare
   `feature_task_*` tools are updated to the new names.

## Non-goals

- No behavioral change to either the prose or runtime phase loops.
- No change to the `bill-feature-task` router's `mode:{prose|runtime}` interface
  or the `feature_goal` / `bill-feature-goal` surface (beyond the
  `feature_implement_*` → `feature_task_prose_*` call-site migration in the goal
  skills).
- No removal or rename of the runtime leaf's CLI (`skill-bill feature-task`).
- No destructive migration of historical telemetry rows — old ids are aliased at
  read time, not rewritten.

## Sources

- `runtime-kotlin/runtime-mcp/src/main/kotlin/skillbill/mcp/core/McpToolRegistry.kt`
  — `toolNames`, `descriptions`, `inputSchemas` for all three families.
- `runtime-kotlin/runtime-mcp/src/main/kotlin/skillbill/mcp/core/McpToolDispatcher.kt`
  — `nativeHandlers` mapping `feature_implement_*` (IMPLEMENT), `feature_task_*`
  and `feature_task_runtime_*` (both TASK_RUNTIME); `mapRemoteStatsWorkflow`.
- `runtime-kotlin/runtime-application/src/main/kotlin/skillbill/application/model/WorkflowUpdateRequest.kt`
  — `WorkflowFamilyKind` enum.
- `runtime-kotlin/runtime-application/src/main/kotlin/skillbill/application/workflow/WorkflowService.kt`
  — kind → `saveFeatureTaskWorkflow(mode)` routing and `WorkflowFamily` public ids.
- `runtime-kotlin/runtime-application/src/main/kotlin/skillbill/application/telemetry/TelemetrySupport.kt`
  — public-id normalization (`implement`/`bill-feature-task`/`feature-task-runtime`).
- `runtime-kotlin/runtime-ports/src/main/kotlin/skillbill/ports/persistence/model/WorkflowStateRecord.kt`
  — `FeatureTaskWorkflowMode`.
- sqlite: `DatabaseSchema.kt`, `DatabaseColumnMigrations.kt`,
  `StaleSessionReconciler.kt`, `WorkflowStateStore.kt`, lifecycle telemetry
  support files under `runtime-infra-sqlite`.
- `orchestration/contracts/telemetry-event-schema.yaml` — event consts.
- skills: `skills/bill-feature-task-prose/content.md`,
  `skills/bill-feature-task-prose/native-agents/agents.yaml`,
  `skills/bill-feature-goal/content.md`,
  `skills/bill-feature-task-subtask-runner/content.md`.
- golden: `runtime-kotlin/runtime-mcp/src/test/resources/golden/mcp-feature-task-runtime-workflow.json`.

## Next path

```bash
Run bill-feature-task on .feature-specs/SKILL-86-feature-task-naming-cleanup/spec.md
```
