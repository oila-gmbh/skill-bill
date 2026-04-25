# SKILL-27 Kotlin Runtime Cutover Checklist

This checklist records the Phase 9 CLI cutover and the Phase 10 MCP stdio
cutover.

## Runtime Boundary

Current default runtime:

- `skill-bill` resolves to `skill_bill.launcher:main` from `pyproject.toml`.
  The launcher defaults to the Kotlin CLI.
- `SKILL_BILL_RUNTIME=python skill-bill ...` is the explicit Python CLI
  fallback.
- `skill-bill-mcp` resolves to `skill_bill.launcher:mcp_main`. The launcher
  defaults to the Kotlin stdio MCP server.
- `SKILL_BILL_MCP_RUNTIME=python skill-bill-mcp` is the explicit Python MCP
  fallback.
- `scripts/mcp_server_start.sh` bootstraps the Python package and starts the
  launcher-backed MCP server.

Current Kotlin runtime:

- `runtime-kotlin` owns in-module CLI and MCP adapter behavior for the ported
  runtime surfaces.
- Kotlin owns durable workflow runtime behavior, review/learnings/stats/
  telemetry service behavior, governed loader/scaffold primitives, and install
  primitives.
- Python remains the MCP fallback, the CLI rollback path, and the temporary
  compatibility bridge for unported MCP telemetry lifecycle tools.

Still reserved after this stage:

- deleting or weakening Python fallback behavior
- porting the remaining MCP telemetry lifecycle leaf persistence paths from
  the Python bridge into Kotlin services

## Cutover Gates

Before Python runtime retirement, all of these must be true:

1. `cd runtime-kotlin && ./gradlew check` passes from a clean checkout.
2. `.venv/bin/python3 -m unittest discover -s tests` passes from the same
   checkout.
3. `npx --yes agnix --strict .` passes.
4. `.venv/bin/python3 scripts/validate_agent_configs.py` passes.
5. Representative Python and Kotlin CLI payloads match for:
   - `version --format json`
   - `doctor --format json`
   - `import-review --format json`
   - `triage --format json`
   - `learnings resolve --format json`
   - `workflow show --format json`
   - `verify-workflow show --format json`
   - `new-skill --dry-run --format json`
6. Representative Python and Kotlin MCP payloads match for:
   - `doctor`
   - `import_review`
   - `triage_findings`
   - `resolve_learnings`
   - `feature_implement_workflow_continue`
   - `feature_verify_workflow_continue`
   - `new_skill_scaffold`
7. The installer has a tested Kotlin runtime path or an explicit documented
   reason for keeping the Python installer as the fallback after cutover.
8. The release notes name the fallback command/path and the rollback command.

## Phase 9 CLI Switch Result

1. `skill-bill` now routes through `skill_bill.launcher:main`.
2. The launcher defaults to Kotlin for CLI commands.
3. `SKILL_BILL_RUNTIME=python` routes the same command name to the Python CLI.
4. `skill-bill-mcp` defaults to the Kotlin stdio MCP server.
5. `SKILL_BILL_MCP_RUNTIME=python skill-bill-mcp` routes to the Python MCP
   server.

## Phase 10 MCP Switch Result

1. `runtime-mcp` now has a Gradle application entrypoint.
2. The Kotlin MCP server exposes the Python-compatible tool inventory over
   line-delimited stdio JSON-RPC.
3. Ported tools route through Kotlin runtime services.
4. Telemetry lifecycle tools that remain Python-owned route through
   `skill_bill.mcp_tool_bridge`.
5. Installer MCP registrations and `scripts/mcp_server_start.sh` invoke the
   launcher-backed MCP entrypoint.

## Rollback Plan

If the Kotlin-default CLI or MCP path fails:

1. Run CLI commands with `SKILL_BILL_RUNTIME=python`.
2. Run MCP with `SKILL_BILL_MCP_RUNTIME=python`.
3. Re-run `skill-bill doctor --format json` and the MCP `doctor` tool through
   the Python path.
4. Re-run the Python unit suite and agent-config validation.
5. Leave Kotlin artifacts in place for diagnosis; do not delete Python runtime
   code as part of rollback.
6. Record the failure and fix-forward criteria in
   `docs/migrations/SKILL-27-kotlin-runtime-port.md`.
