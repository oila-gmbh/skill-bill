# SKILL-93 — Update check on bill-feature entry

## Summary

When the user invokes `bill-feature`, the skill calls a new `mcp__skill-bill__update_check`
MCP tool. The tool hits the GitHub Releases API (reusing the existing `UpdateCheckService`)
and returns the version status. If a newer stable release is available the skill
soft-blocks: it shows the version gap and asks the user whether to update or continue.
If the user chooses to update, execution stops and the install command is shown.
If the user chooses to continue, the normal `bill-feature` flow resumes unmodified.

## Problem

Users running an outdated `skill-bill` runtime discover the gap only when something breaks
or when they happen to run `/bill-update-check` manually. There is no automatic nudge at the
natural entry point where it matters most.

## Intended Outcome

Invoking `bill-feature` (or any skill that routes through it) performs a silent update check
at the very start. If the runtime is up to date, execution proceeds with zero overhead. If a
newer release exists, the user sees a clear prompt and can update in place or continue with
their current version.

## Acceptance Criteria

1. A new `update_check` MCP tool is registered in `McpToolRegistry.toolNames`,
   `McpToolDispatcher.nativeHandlers`, and exposed via `McpRuntime.updateCheck()`.
2. The tool is backed by `UpdateCheckService.check(includePrereleases = false)` and returns
   a map with at least: `status` (string enum wire name), `installed_version` (nullable
   string), `latest_version` (nullable string), `recommended_install_command` (nullable
   string).
3. `UpdateCheckService` is added to `McpRuntimeServices` so it is available via the DI graph
   without changes to `RuntimeComponent`.
4. An `updateCheckEvent` branch is added to `orchestration/contracts/telemetry-event-schema.yaml`
   with an open-object shape (`additionalProperties: true`, no extra properties) matching
   the `doctor`/stats event pattern. The `oneOf` list and `$defs` are both updated. The
   parity test passes without changes.
5. `skills/bill-feature/content.md` calls `mcp__skill-bill__update_check` as the **first**
   action before any intake or spec-prep work.
6. When the tool returns `status: "update_available"`:
   - The skill surfaces the version gap (installed vs latest).
   - It asks the user: update now or continue?
   - If update: the skill stops and shows the `recommended_install_command`.
   - If continue: the normal `bill-feature` flow proceeds from intake unchanged.
7. When the tool returns any status other than `update_available` (up to date, ahead of
   release, or unknown) the skill proceeds to intake silently with no user prompt.
8. All existing `McpRuntimeTest`, `TelemetryEventInputSchemaParityTest`,
   `TelemetryEventSchemaValidatesAllEventsTest`, `TelemetryEventSchemaContractVersionTest`,
   and `McpStdioArgumentShapeUnifiedContractTest` pass without modification to the tests
   themselves.

## Constraints

- `UpdateCheckService` already carries the GitHub API call logic and `TransportContext` wiring.
  Do not duplicate that logic; wire through DI.
- The new event branch in the telemetry schema must follow the open-object shape (same as
  `doctorEvent`, `featureTaskProseStatsEvent`, etc.) because `update_check` takes no
  structured arguments. The `TelemetryEventInputSchemaParityTest` enforces this automatically.
- The soft-block must be in the skill content (`bill-feature/content.md`), not in the runtime.
  The MCP tool is a pure query; decision logic stays in the skill.
- Do not add `update_check` to `bill-feature-task`, `bill-feature-spec`, or any other skill
  entry point — only `bill-feature`.

## Non-Goals

- No `--include-prereleases` flag on the MCP tool (stable releases only via the tool).
- No automatic update execution from the skill — the skill shows the command, not runs it.
- No update check on other skill entry points (`/bill-code-review`, `/bill-feature-task`,
  etc.).
- No changes to the existing `bill-update-check` slash command or its CLI subprocess path.
- No caching / TTL to avoid repeated checks within a session.

## Affected Areas

- `runtime-kotlin/runtime-mcp` — `McpComponent`, `McpRuntime`, `McpToolRegistry`,
  `McpToolDispatcher`
- `orchestration/contracts/telemetry-event-schema.yaml`
- `skills/bill-feature/content.md`

## Open Questions

None — design confirmed in conversation (SKILL-93 thread).
