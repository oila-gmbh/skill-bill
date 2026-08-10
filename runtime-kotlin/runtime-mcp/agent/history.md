## [2026-08-09] SKILL-175 remove prose MCP tools and telemetry (subtask 4)
Areas: runtime-kotlin/runtime-mcp, orchestration/{contracts,telemetry-contract,workflow-contract}, docs, docs/cloudflare-telemetry-proxy
- Deleted the prose MCP family end-to-end: `feature_task_prose_*`, hidden `feature_implement_*` aliases, and `goal_prose_*` from registry, dispatcher, lifecycle/goal handlers, input schemas, goldens, and stdio/parity tests — agents can no longer open a prose workflow through MCP.
- Telemetry event schema bumped to `1.8.0` with matching `TELEMETRY_EVENT_CONTRACT_VERSION`; prose/implement/goal_prose tool and event shapes removed so stale fixtures loud-fail on parity. reusable PATTERN: retire MCP tools and schema events in one contract bump.
- Cloudflare proxy keeps ingest pass-through for retired event names (old clients must not get batch failures) but drops them from `/stats` aggregation; docs and worker tests pin that policy. reusable for future event retirements that must not break installed emitters.
- Getting-started / capabilities / telemetry docs and playbooks no longer advertise prose MCP tools as product surface; historical prose rows remain queryable as legacy only.
- Known limitation: CLI `skill-bill workflow` prose family and SQLite prose tables remain until SKILL-175 subtasks 5–6.
Feature flag: N/A
Acceptance criteria: 6/6 implemented

## [2026-06-23] SKILL-93 update-check-on-bill-feature
Areas: runtime-kotlin/runtime-mcp, orchestration/contracts, skills/bill-feature
- New `update_check` MCP tool: registered in `McpToolRegistry.toolNames`, `McpToolDispatcher.nativeHandlers`, and backed by `McpRuntime.updateCheck()`
- `UpdateCheckService` added as last param of `McpRuntimeServices`; kotlin-inject resolves it automatically — no `RuntimeComponent` changes needed (reusable pattern for adding services to the DI graph)
- `updateCheck()` is a pure-query tool (no `withAutoSync`); returns 5-key map: `status`, `installed_version`, `latest_version`, `recommended_install_command`, `reason`
- `updateCheckEvent` open-object `$defs` block added to telemetry schema after `doctorEvent`; `oneOf` ref added at matching position (reusable pattern for future open-object events)
- Zero-input tools have no `inputSchemas` entry and fall through to `openObjectSchema()` in `McpToolRegistry` (established pattern)
Feature flag: N/A
Acceptance criteria: 12/12 implemented
