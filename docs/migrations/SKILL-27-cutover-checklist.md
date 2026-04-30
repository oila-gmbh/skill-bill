# SKILL-27 Kotlin Runtime Cutover Checklist

This checklist records the Phase 9 CLI cutover, Phase 10 MCP stdio cutover,
and Phase 11 native MCP lifecycle telemetry cutover.

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
- Python remains the MCP fallback and the CLI rollback path.
- MCP telemetry lifecycle tools are Kotlin-native.

Still reserved after this stage:

- deleting or weakening Python fallback behavior
- retiring Python runtime code before normal-use confidence exists

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

## Runtime Ownership Inventory

Python deletion must not start until every `Python-backed and blocking
retirement` item has a named owner and a test path. Items flagged for Subtask 3
need Kotlin-first contract tests before the Python fallback can be removed.

| Surface | Items | Classification | Owner before deletion | Test path / Subtask 3 flag |
|---|---|---|---|---|
| Executable entrypoints | `skill-bill`, `skill-bill-mcp` | Deliberately retained compatibility fallback | Runtime launcher owner | Add packaged-runtime smoke coverage before deleting launcher fallback; Subtask 2/3 |
| CLI system commands | `version`, `doctor` | Kotlin-owned | Runtime CLI owner | `runtime-kotlin/runtime-cli` tests |
| CLI review commands | `import-review`, `record-feedback`, `triage`, `stats`, `implement-stats`, `verify-stats` | Kotlin-owned | Runtime CLI owner | `runtime-kotlin/runtime-cli` and review persistence tests |
| CLI learnings commands | `learnings list`, `learnings show`, `learnings resolve`, `learnings add`, `learnings edit`, `learnings disable`, `learnings enable`, `learnings delete` | Kotlin-owned | Runtime CLI owner | `runtime-kotlin/runtime-cli` and learnings service tests |
| CLI telemetry commands | `telemetry status`, `telemetry sync`, `telemetry capabilities`, `telemetry stats`, `telemetry enable`, `telemetry disable`, `telemetry set-level` | Kotlin-owned | Runtime CLI owner | `runtime-kotlin/runtime-cli` and telemetry service tests |
| CLI workflow commands | `workflow open`, `workflow update`, `workflow show`, `workflow get`, `workflow list`, `workflow latest`, `workflow resume`, `workflow continue`, `verify-workflow open`, `verify-workflow update`, `verify-workflow show`, `verify-workflow get`, `verify-workflow list`, `verify-workflow latest`, `verify-workflow resume`, `verify-workflow continue` | Kotlin-owned | Runtime workflow owner | `runtime-kotlin/runtime-cli` workflow tests |
| CLI scaffold / authoring commands | `list`, `show`, `explain`, `validate`, `upgrade`, `render`, `edit`, `fill`, interactive `new-skill`, interactive `new`, `create-and-fill`, `new-addon` | Python-backed and blocking retirement | Scaffold runtime owner | Add Kotlin-first scaffold/authoring contract tests; Subtask 3 |
| CLI payload scaffold commands | non-interactive `new-skill`, non-interactive `new` | Python-backed and blocking retirement | Scaffold runtime owner | Existing scaffold parity tests cover the Kotlin adapter; add Kotlin-owned scaffold execution before Python retirement in Subtask 3 |
| CLI install primitives | `install agent-path`, `install detect-agents`, `install link-skill` | Python-backed and blocking retirement | Installer runtime owner | Add Kotlin-first install primitive tests; Subtask 3 |
| MCP system / review / learnings tools | `doctor`, `import_review`, `triage_findings`, `resolve_learnings`, `review_stats` | Kotlin-owned | Runtime MCP owner | `runtime-kotlin/runtime-mcp` tests |
| MCP feature implement lifecycle tools | `feature_implement_started`, `feature_implement_finished`, `feature_implement_stats` | Kotlin-owned | Runtime MCP owner | Lifecycle schema and service tests |
| MCP feature implement workflow tools | `feature_implement_workflow_open`, `feature_implement_workflow_update`, `feature_implement_workflow_get`, `feature_implement_workflow_list`, `feature_implement_workflow_latest`, `feature_implement_workflow_resume`, `feature_implement_workflow_continue` | Kotlin-owned | Runtime workflow owner | `runtime-kotlin/runtime-mcp` workflow tests |
| MCP feature verify lifecycle tools | `feature_verify_started`, `feature_verify_finished`, `feature_verify_stats` | Kotlin-owned | Runtime MCP owner | Lifecycle service tests |
| MCP feature verify workflow tools | `feature_verify_workflow_open`, `feature_verify_workflow_update`, `feature_verify_workflow_get`, `feature_verify_workflow_list`, `feature_verify_workflow_latest`, `feature_verify_workflow_resume`, `feature_verify_workflow_continue` | Kotlin-owned | Runtime workflow owner | `runtime-kotlin/runtime-mcp` workflow tests |
| MCP quality / PR telemetry tools | `quality_check_started`, `quality_check_finished`, `pr_description_generated` | Kotlin-owned | Runtime MCP owner | Lifecycle service tests |
| MCP telemetry proxy tools | `telemetry_proxy_capabilities`, `telemetry_remote_stats` | Kotlin-owned | Runtime MCP owner | Telemetry MCP tests |
| MCP scaffold tool | `new_skill_scaffold` | Kotlin-owned | Scaffold runtime owner | Existing MCP scaffold tests plus Subtask 3 golden coverage |
| Python launcher fallback | `skill_bill/launcher.py`, `SKILL_BILL_RUNTIME=python`, `SKILL_BILL_MCP_RUNTIME=python` | Deliberately retained compatibility fallback | Runtime launcher owner | Keep until packaged Kotlin runtime and contract tests are green; Subtask 2/3 |
| Python CLI / MCP implementation | `skill_bill/cli.py`, `skill_bill/mcp_server.py` and Python runtime helpers used only by fallback paths | Python-backed and blocking retirement | Runtime CLI/MCP owners | Delete only after each fallback-owned surface above has owner and test path; Subtask 3 |
| Validation scripts | `scripts/validate_agent_configs.py`, `scripts/skill_repo_contracts.py`, `scripts/validate_release_ref.py` | Python-backed but acceptable as script/tooling | Repo governance owner | Covered by repo validation commands; not a runtime retirement blocker |
| Migration script | `scripts/migrate_to_content_md.py` | Python-backed but acceptable as script/tooling | Scaffold governance owner | Maintainer-only migration path; not a runtime retirement blocker |
| MCP bootstrap script | `scripts/mcp_server_start.sh` | Python-backed and blocking retirement | Installer/runtime owner | Replace with packaged Kotlin MCP launch path or document retained fallback; Subtask 2/3 |
| Installer scripts | `install.sh`, `uninstall.sh` | Python-backed but acceptable as script/tooling | Installer owner | Keep shell installer; Subtask 2 adds packaged Kotlin distro path |
| Release docs/support | `RELEASING.md`, release validation references | Python-backed but acceptable as script/tooling | Release owner | Update release notes with fallback and rollback commands before retirement |

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
4. Telemetry lifecycle tools route through Kotlin-native services.
5. Installer MCP registrations and `scripts/mcp_server_start.sh` invoke the
   launcher-backed MCP entrypoint.

## Phase 11 MCP Lifecycle Result

1. MCP lifecycle telemetry tools persist through Kotlin services and SQLite
   repository ports.
2. Standalone lifecycle tools emit the same outbox event names and payload
   shapes as the Python implementation.
3. Orchestrated lifecycle tools return child `telemetry_payload` values
   without local outbox emission.
4. `LegacyPythonMcpBridge` and `skill_bill.mcp_tool_bridge` have been removed.

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
