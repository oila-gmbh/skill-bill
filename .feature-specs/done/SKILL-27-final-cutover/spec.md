---
issue_key: SKILL-27
feature_name: final-cutover
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

# SKILL-27 - Final Cutover

## Status

Complete.

## Goal

Make the user-facing `skill-bill` CLI entrypoint Kotlin-default while keeping
Python available as the explicit fallback. Keep `skill-bill-mcp` on the Python
stdio server until a Kotlin MCP server executable is packaged.

## Acceptance Criteria

1. Add this stage spec with accepted scope, non-goals, and status
   `In Progress`.
2. Add a launcher entrypoint for `skill-bill` that defaults to the Kotlin CLI
   and supports `SKILL_BILL_RUNTIME=python` fallback.
3. Add a Kotlin CLI main/application packaging path that can execute normal
   commands and stdin-backed dry-run scaffold payloads.
4. Route `pyproject.toml` script entrypoints through the launcher.
5. Keep `skill-bill-mcp` Python-backed by default and make the Kotlin MCP
   selection fail loudly with a clear "not packaged yet" message.
6. Update docs, migration notes, and runtime history to describe the CLI
   cutover, Python rollback path, and remaining MCP cutover gap.
7. Add tests covering launcher selection, fallback behavior, and MCP loud-fail
   behavior.
8. Run the runtime and repo validation gates.

## Non-Goals

- Packaging a Kotlin stdio MCP server.
- Deleting Python runtime code.
- Removing Python CLI fallback.
- Changing CLI command names or MCP tool names.
- Changing installer bootstrapping behavior beyond the script entrypoint target.
