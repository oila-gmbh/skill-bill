## [2026-06-23] SKILL-93 update-check-on-bill-feature
Areas: runtime-kotlin/runtime-mcp, orchestration/contracts, skills/bill-feature
- New `update_check` MCP tool: registered in `McpToolRegistry.toolNames`, `McpToolDispatcher.nativeHandlers`, and backed by `McpRuntime.updateCheck()`
- `UpdateCheckService` added as last param of `McpRuntimeServices`; kotlin-inject resolves it automatically — no `RuntimeComponent` changes needed (reusable pattern for adding services to the DI graph)
- `updateCheck()` is a pure-query tool (no `withAutoSync`); returns 5-key map: `status`, `installed_version`, `latest_version`, `recommended_install_command`, `reason`
- `updateCheckEvent` open-object `$defs` block added to telemetry schema after `doctorEvent`; `oneOf` ref added at matching position (reusable pattern for future open-object events)
- Zero-input tools have no `inputSchemas` entry and fall through to `openObjectSchema()` in `McpToolRegistry` (established pattern)
Feature flag: N/A
Acceptance criteria: 12/12 implemented
