# SKILL-27 Kotlin Runtime Cutover Checklist

This checklist records the Phase 9 CLI cutover and the remaining MCP cutover
gap.

## Runtime Boundary

Current default runtime:

- `skill-bill` resolves to `skill_bill.launcher:main` from `pyproject.toml`.
  The launcher defaults to the Kotlin CLI.
- `SKILL_BILL_RUNTIME=python skill-bill ...` is the explicit Python CLI
  fallback.
- `skill-bill-mcp` resolves to `skill_bill.launcher:mcp_main` and remains
  Python-backed by default.
- `scripts/mcp_server_start.sh` bootstraps the Python package and starts the
  Python MCP server.

Current Kotlin runtime:

- `runtime-kotlin` owns in-module CLI and MCP adapter behavior for the ported
  runtime surfaces.
- Kotlin owns durable workflow runtime behavior, review/learnings/stats/
  telemetry service behavior, governed loader/scaffold primitives, and install
  primitives.
- Python remains the production MCP fallback and CLI rollback path.

Still reserved after this stage:

- packaging a Kotlin stdio MCP server
- changing MCP start scripts to prefer Kotlin
- deleting or weakening Python fallback behavior

## Cutover Gates

Before Kotlin becomes the default MCP runtime, all of these must be true:

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
4. `skill-bill-mcp` remains Python-backed and fails loudly if
   `SKILL_BILL_MCP_RUNTIME=kotlin` is requested before a Kotlin stdio server is
   packaged.

## Rollback Plan

If the Kotlin-default CLI path fails:

1. Run the command with `SKILL_BILL_RUNTIME=python`.
2. Re-run `skill-bill doctor --format json` and the MCP `doctor` tool through
   the Python path.
3. Re-run the Python unit suite and agent-config validation.
4. Leave Kotlin artifacts in place for diagnosis; do not delete Python runtime
   code as part of rollback.
5. Record the failure and fix-forward criteria in
   `docs/migrations/SKILL-27-kotlin-runtime-port.md`.
