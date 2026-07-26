---
Status: Complete
Issue: SKILL-32
Parent spec: [spec.md](spec.md)
Parent subtask: [spec_subtask_3_contract-tests-and-python-retirement.md](spec_subtask_3_contract-tests-and-python-retirement.md)
---

# Subtask 3c: Python Runtime Fallback Retirement + Docs + Release Notes

## Goal

After 3a's contract regression net is locked and 3b has removed the Kotlin
CLI's last consumers of the Python bridge, deliberately retire the Python
runtime fallback. This means deleting `SKILL_BILL_RUNTIME=python` and
`SKILL_BILL_MCP_RUNTIME=python` resolution, deleting `skill_bill/cli.py`,
`skill_bill/mcp_server.py`, and `scripts/mcp_server_start.sh`, updating
`LauncherRuntime.supportedOperations` (and its surface contract test lock)
to drop python-fallback, fixing migration / installer / getting-started docs
to stop presenting Python as a normal runtime fallback (line-targeted strip
only — structural rewrite is Subtask 4), and publishing release notes whose
rollback guidance is "install previous release", not "select Python runtime".

After this subtask, normal CLI and MCP use has exactly one source of truth:
packaged Kotlin. Python remains only in explicitly retained tooling per the
Subtask 1 ownership table.

## Scope

In scope:

1. Delete `SKILL_BILL_RUNTIME` and `SKILL_BILL_MCP_RUNTIME` env-var
   resolution from `skill_bill/launcher.py`. Setting either no longer
   selects a Python entry path.
2. Delete `skill_bill/cli.py`, `skill_bill/mcp_server.py`, and
   `scripts/mcp_server_start.sh`.
3. Adjust `pyproject.toml` console-scripts ONLY if the entry points need
   adjustment after the deletions. Per the parent spec note, the entry
   points likely front Kotlin via `launcher.main()` / `mcp_main()` and
   stay; verify and document the decision inline.
4. Update `LauncherRuntime.supportedOperations` to drop the
   `python-fallback` and `mcp-python-fallback` entries. Update the
   `RuntimeSurfaceContractTest` lock that 3a left with a `// TODO(3c)`
   marker so the test asserts the post-retirement value.
5. Migration and installer doc edits — line-targeted strip only:
   - `docs/migrations/SKILL-27-cutover-checklist.md` Rollback Plan section:
     replace any "select Python runtime" guidance with "install previous
     release".
   - `docs/migrations/SKILL-27-kotlin-runtime-port.md`: remove statements
     that present Python as a normal runtime fallback.
   - `docs/getting-started.md`: remove Python-fallback references on the
     lines they appear; do NOT restructure (that is Subtask 4).
6. Release notes in `RELEASING.md` (or the project's release-notes home if
   different — confirm by inspection): publish the SKILL-32 entry with
   "install previous release" rollback guidance and a short list of the
   removed env vars and files.
7. History entries: append a retirement summary to `agent/history.md` and
   `runtime-kotlin/agent/history.md` per AGENTS.md history-hygiene rules
   (one short, reusable, high-signal entry each).
8. Final four-command gate plus a fresh-install rehearsal with NO
   `SKILL_BILL_RUNTIME` env var set anywhere — the launcher must succeed
   with packaged Kotlin only.

Out of scope:

- Adding new contract tests, fixtures, or arch bans (3a / 3b).
- Adding new ported commands or removing CLI bridge helpers (3b).
- Restructuring `docs/getting-started.md` or
  `docs/getting-started-for-teams.md` (Subtask 4).
- Adding the external-author dry run (Subtask 4).
- Touching `scripts/validate_agent_configs.py`,
  `scripts/skill_repo_contracts.py`, `scripts/validate_release_ref.py`,
  `scripts/migrate_to_content_md.py`, `install.sh`, or `uninstall.sh` —
  these are explicitly retained per the Subtask 1 ownership table.

## Acceptance Criteria

1. `skill_bill/launcher.py` no longer reads `SKILL_BILL_RUNTIME` or
   `SKILL_BILL_MCP_RUNTIME`; setting either has no effect on the launched
   runtime. A Python-side test asserts this.
2. `skill_bill/cli.py`, `skill_bill/mcp_server.py`, and
   `scripts/mcp_server_start.sh` no longer exist in the repo. Repo grep
   for these paths returns zero hits outside historical docs.
3. `pyproject.toml` console-scripts entry points are inspected; adjusted if
   the deletions broke them, otherwise left intact. The decision is
   recorded in the commit message.
4. `LauncherRuntime.supportedOperations` no longer contains
   `python-fallback` or `mcp-python-fallback`; the
   `RuntimeSurfaceContractTest` lock asserts the new value and the
   `// TODO(3c)` marker 3a left is gone.
5. `docs/migrations/SKILL-27-cutover-checklist.md` Rollback Plan section
   says "install previous release" rather than "select Python runtime".
6. `docs/migrations/SKILL-27-kotlin-runtime-port.md` and
   `docs/getting-started.md` no longer present Python as a normal runtime
   fallback (line-targeted strip; full restructure remains for Subtask 4).
7. Release notes are published in `RELEASING.md` (or the confirmed release-
   notes home) with rollback guidance "install previous release" and a
   short list of the removed env vars and files.
8. `agent/history.md` and `runtime-kotlin/agent/history.md` each have a
   short retirement entry per AGENTS.md history-hygiene rules.
9. The four-command validation gate is green from a clean checkout:
   `(cd runtime-kotlin && ./gradlew check)`,
   `.venv/bin/python3 -m unittest discover -s tests`,
   `npx --yes agnix --strict .`,
   `.venv/bin/python3 scripts/validate_agent_configs.py`.
10. A fresh-install rehearsal — `./install.sh` from a clean tree with NO
    `SKILL_BILL_RUNTIME` env var set — succeeds and `skill-bill doctor
    --format json` plus `skill-bill-mcp` initialize / `tools/list` /
    `tools/call` for `doctor` all work.
11. Python remains only in the tooling explicitly retained by the Subtask 1
    ownership table; it is no longer in active runtime ownership.

## Non-Goals

- Restructuring getting-started docs (Subtask 4).
- External-author dry run (Subtask 4).
- New contract tests beyond the `LauncherRuntime` lock update (3a / 3b own
  the contract net).
- Removing or modifying `scripts/validate_agent_configs.py`,
  `scripts/skill_repo_contracts.py`, `scripts/validate_release_ref.py`,
  `scripts/migrate_to_content_md.py`, `install.sh`, or `uninstall.sh`.

## Dependencies

- 3a (the contract regression net — without it, deleting
  `LauncherRuntime` python-fallback entries cannot be safely locked).
- 3b (must have removed the CLI's last Kotlin-side consumers of
  `skill_bill/cli.py` and `skill_bill/scaffold` before those files can be
  deleted).
- Subtask 1 (ownership table identifies which Python tooling stays).
- Subtask 2a/2b/2c (packaged Kotlin must already be the default).

## Validation Strategy

`bill-quality-check` plus the four-command validation gate. After deletion,
repo grep for `SKILL_BILL_RUNTIME`, `SKILL_BILL_MCP_RUNTIME`,
`skill_bill/cli.py`, `skill_bill/mcp_server.py`, and
`scripts/mcp_server_start.sh` must return zero hits outside historical
docs. The fresh-install rehearsal must complete with NO `SKILL_BILL_RUNTIME`
env var set anywhere — the four sanity checks (`doctor --format json`,
MCP `initialize`, MCP `tools/list`, MCP `tools/call doctor`) must all pass.

## Implementation Notes

- File pointers:
  - Env-var resolution to delete: `skill_bill/launcher.py` (search for
    `SKILL_BILL_RUNTIME` and `SKILL_BILL_MCP_RUNTIME`).
  - Files to delete: `skill_bill/cli.py`, `skill_bill/mcp_server.py`,
    `scripts/mcp_server_start.sh`.
  - `pyproject.toml` console-scripts: confirm `skill-bill` and
    `skill-bill-mcp` still front `launcher.main()` / `mcp_main()` and stay.
  - `LauncherRuntime.supportedOperations`: locate via grep for
    `python-fallback` under `runtime-kotlin/`. Update the lock in the
    `RuntimeSurfaceContractTest` 3a created.
  - Migration docs: `docs/migrations/SKILL-27-cutover-checklist.md`
    (Rollback Plan section), `docs/migrations/SKILL-27-kotlin-runtime-port.md`.
  - Getting-started: `docs/getting-started.md` — line-targeted strip only.
  - Release notes home: `RELEASING.md` (confirm by inspection; if the repo
    has a separate `CHANGELOG.md` or `docs/releases/` home, use that
    instead and record the choice in the commit message).
  - History: `agent/history.md`, `runtime-kotlin/agent/history.md`.
- Rehearsal pattern: clean checkout, `./install.sh`, run `skill-bill
  doctor --format json` plus the MCP four-step sanity (`initialize`,
  `tools/list`, `tools/call` for `doctor`, one lifecycle telemetry tool
  with required arguments) per parent spec's validation gate. Confirm NO
  `SKILL_BILL_RUNTIME` env var is set during rehearsal.
- Constraints: 2-space indent, no `Any`, no `Dispatchers.*`, no
  `kotlin.Result`, no `relaxed=true` mocks, no new suppressions,
  `.orEmpty()` instead of `?: ""`. AGENTS.md mandates the four-command
  gate. JUnit5 + kotlin-test, NOT kotest, inside `runtime-kotlin`.
- History-hygiene: the `agent/history.md` entries should follow
  `bill-boundary-history` conventions — short, reusable, high-signal,
  no stale step-by-step narrative.

## Handoff Prompt

Run `bill-feature-implement` on
`.feature-specs/SKILL-32-technical-stabilization/spec_subtask_3c_python-runtime-retirement.md`.
