# SKILL-175 Subtask 4 - MCP tools, telemetry contracts, and proxy

Parent spec: [.feature-specs/SKILL-175-remove-prose-opencode-runtime-support/spec.md](spec.md)
Issue key: SKILL-175

## Scope

Remove the prose MCP tool family, goal-prose lifecycle tools, legacy
`feature_implement_*` aliases, and matching telemetry contract/event surfaces —
including remote proxy unions — so no agent or integration can open a prose
workflow through MCP.

### Detailed surfaces

**MCP server (`runtime-mcp/`):**

- Registry/dispatcher entries for:
  - `feature_task_prose_started` / `feature_task_prose_finished` /
    `feature_task_prose_stats`
  - `feature_task_prose_workflow_{open,update,get,list,latest,resume,continue}`
  - hidden aliases `feature_implement_*` (all ten) → prose handlers
  - `goal_prose_started` / `goal_prose_subtask_finished` / `goal_prose_finished`
- `McpLifecycleToolHandlers.kt`, `McpGoalToolHandlers.kt` prose stamping
  (`mode = "prose"`), `McpRuntime.kt` prose stats hooks
- `McpInputSchemas.kt` family id `feature-task-prose`
- Golden: `runtime-mcp/src/test/resources/golden/mcp-feature-task-prose-workflow.json`
- Tests: `McpStdioServerTest`, `McpRuntimeTest`,
  `McpWorkflowContinuationRuntimeTest`, telemetry parity/schema violation tests,
  `McpStdioArgumentShapeUnifiedContractTest`, goal telemetry emission parity

**Contracts:**

- `orchestration/contracts/telemetry-event-schema.yaml` — remove
  `feature_task_prose_*` / `goal_prose_*` tool and event shapes; bump
  `TELEMETRY_EVENT_CONTRACT_VERSION` + Kotlin parity constant/tests
- Playbooks under `orchestration/telemetry-contract/` and
  `orchestration/workflow-contract/` that document prose tools
- Cursor/MCP capability catalogs that list prose tools (including any generated
  or checked-in MCP tool lists in repo docs)

**Remote / proxy:**

- `docs/cloudflare-telemetry-proxy/README.md`, `worker.js`, `worker.test.mjs` —
  stop accepting or explicitly document drop of
  `skillbill_feature_task_prose_*` and legacy `skillbill_feature_implement_*`
  per privacy policy (choose one: reject unknown vs ignore; must not require
  prose handlers to remain in-process)

**Docs:**

- `docs/getting-started.md` MCP tool lists
- `docs/capabilities.md` prose workflow tool bullets
- `docs/review-telemetry.md` / `docs/telemetry-privacy.md` prose event fields
- Demo storyboard if it still demonstrates prose `workflow_update`

## Acceptance Criteria

1. MCP server no longer registers `feature_task_prose_*`, `feature_implement_*`
   aliases, or `goal_prose_*` tools.
2. Telemetry event schema and Kotlin contract version no longer define those
   tools/events; parity tests updated; schema bump loud-fails stale fixtures.
3. MCP goldens and stdio/argument-shape tests pass without prose workflow
   goldens.
4. Telemetry proxy/worker tests match the chosen drop/reject policy for retired
   event names; docs state the policy.
5. Getting-started / capabilities / telemetry docs no longer list prose MCP
   tools as product surface.
6. Targeted `runtime-mcp` tests + schema contract tests pass.

## Non-Goals

- Removing CLI `skill-bill workflow` (subtask 5) — may briefly overlap; finish
  MCP here even if CLI still references types until subtask 5/6.
- Deleting SQLite tables (subtask 6).

## Dependency Notes

- Depends on subtasks 2–3 (no skills instructing these tools).
- Informs subtask 5/6 (application services may still have prose methods until
  deleted).

## Validation Strategy

- MCP registry unit test: tool name set excludes prose/implement aliases /
  goal_prose.
- Schema contract version test green after bump.
- Proxy worker tests for retired events.
- Grep `orchestration/contracts` + `runtime-mcp` for `feature_task_prose` /
  `goal_prose` / `feature_implement_workflow`.

## Next Path

```bash
skill-bill goal SKILL-175
```

After this subtask: remove CLI prose workflow family (subtask 5).
