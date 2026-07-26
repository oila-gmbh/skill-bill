---
issue_key: SKILL-27
feature_name: mcp-stdio-cutover
feature_size: MEDIUM
status: Complete
created: 2026-04-25
rollout_needed: false
branch: feat/SKILL-27-cutover-preparation
source_context:
  - docs/migrations/SKILL-27-cutover-checklist.md
  - docs/migrations/SKILL-27-kotlin-runtime-port.md
  - runtime-kotlin/agent/history.md
---

# SKILL-27 - MCP Stdio Cutover

## Status

Complete.

## Goal

Package a Kotlin stdio MCP server and make `skill-bill-mcp` default to it,
while keeping the Python MCP server available as the explicit fallback and as a
temporary compatibility bridge for telemetry lifecycle tools that have not been
ported to Kotlin services yet.

## Acceptance Criteria

1. Add a Kotlin `runtime-mcp` application entrypoint that speaks line-delimited
   MCP JSON-RPC over stdio.
2. Preserve the current MCP tool inventory and route ported tools through
   Kotlin runtime services.
3. Route unported telemetry lifecycle tools through a narrow Python bridge so
   callers keep the same tool names and payloads during cutover.
4. Change `skill-bill-mcp` launcher selection so Kotlin is the default and
   `SKILL_BILL_MCP_RUNTIME=python` is the rollback path.
5. Update installer/start-script registrations to invoke the launcher-backed
   MCP entrypoint.
6. Add tests for launcher selection and Kotlin stdio protocol behavior.
7. Update docs, migration notes, and runtime history.
8. Run the runtime and repo validation gates.

## Non-Goals

- Deleting the Python MCP server.
- Porting every remaining telemetry lifecycle persistence path to Kotlin.
- Removing Python MCP fallback.
- Changing MCP tool names or external payload names.
