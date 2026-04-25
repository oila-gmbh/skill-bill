# SKILL-27 Kotlin Runtime Cutover Checklist

This checklist is the Phase 8 handoff document for switching Skill Bill from
Python-default to Kotlin-default in a later phase. It is not the cutover itself.

## Runtime Boundary

Current default runtime:

- `skill-bill` resolves to `skill_bill.cli:main` from `pyproject.toml`.
- `skill-bill-mcp` resolves to `skill_bill.mcp_server:main`.
- `scripts/mcp_server_start.sh` bootstraps the Python package and starts the
  Python MCP server.

Current Kotlin runtime:

- `runtime-kotlin` owns in-module CLI and MCP adapter behavior for the ported
  runtime surfaces.
- Kotlin owns durable workflow runtime behavior, review/learnings/stats/
  telemetry service behavior, governed loader/scaffold primitives, and install
  primitives.
- Python remains the production fallback and behavioral oracle until Phase 9
  intentionally flips the default entrypoints.

Reserved for Phase 9:

- changing `pyproject.toml` script entrypoints
- changing installer or MCP start scripts to prefer Kotlin
- release packaging for Kotlin executables
- deleting or weakening Python fallback behavior

## Cutover Gates

Before Kotlin becomes the default runtime, all of these must be true:

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

## Phase 9 Switch Plan

1. Add Kotlin executable packaging for the CLI and MCP server.
2. Add an opt-in launcher selection path that can choose `python` or `kotlin`
   without changing the command names.
3. Run the cutover gates in both runtime modes.
4. Flip the default runtime selection to Kotlin.
5. Keep Python fallback selectable and documented.
6. Run one normal feature workflow and one review/triage workflow through the
   Kotlin-default path.
7. Publish the validation report and rollback instructions with the change.

## Rollback Plan

If the Kotlin-default path fails after Phase 9:

1. Switch runtime selection back to Python.
2. Re-run `skill-bill doctor --format json` and the MCP `doctor` tool through
   the Python path.
3. Re-run the Python unit suite and agent-config validation.
4. Leave Kotlin artifacts in place for diagnosis; do not delete Python runtime
   code as part of rollback.
5. Record the failure and fix-forward criteria in
   `docs/migrations/SKILL-27-kotlin-runtime-port.md`.
