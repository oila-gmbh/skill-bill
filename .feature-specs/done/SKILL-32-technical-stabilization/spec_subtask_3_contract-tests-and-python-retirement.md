---
Status: Not Started
Issue: SKILL-32
Parent spec: [spec.md](spec.md)
---

# Subtask 3: Kotlin Contract Tests + Python Runtime Retirement

## Goal

Land first-class Kotlin contract guarantees (golden CLI fixtures, MCP contract
fixtures, arch tests, runtime surface tests) FIRST, then deliberately retire
the Python runtime fallback (env vars, CLI/MCP entry paths, validators) so
that normal CLI and MCP use has exactly one source of truth: packaged Kotlin.

This subtask delivers Phase 5 + Phase 6 of the parent spec, in that order.
Phase 5 must precede Phase 6 within this subtask per the spec's recommended
execution order.

## Scope

This subtask owns:

1. Phase 5 — golden JSON fixtures for the eight listed CLI outputs and the six
   MCP contract surfaces; arch tests preventing CLI/MCP adapters from
   reintroducing direct DB/FS/HTTP/Python-bridge dependencies; runtime surface
   tests asserting owner package, supported operations, contract version, and
   status.
2. Phase 6 — remove `SKILL_BILL_RUNTIME=python`, `SKILL_BILL_MCP_RUNTIME=python`,
   and the Python CLI/MCP runtime entry paths; port-or-retire Python repo
   validators per the Subtask 1 ownership table; update `pyproject.toml`,
   `skill_bill/launcher.py`, installer docs, migration docs, and
   getting-started docs (only the Python-mention removals; the structural
   getting-started rewrite happens in Subtask 4); publish release notes with
   "install previous release" rollback guidance.

## Acceptance Criteria

### Phase 5 — Contract Tests (must land before Phase 6 work in this subtask)

1. Golden JSON fixtures exist and are asserted for all eight CLI outputs:
   - `version --format json`
   - `doctor --format json`
   - `import-review --format json`
   - `triage --format json`
   - `learnings resolve --format json`
   - `workflow show --format json`
   - `verify-workflow show --format json`
   - `new-skill --dry-run --format json`
2. MCP contract fixtures exist for: `doctor`, `import_review`, `triage_findings`,
   `resolve_learnings`, workflow continuation tools, `new_skill_scaffold`.
3. Arch tests in `RuntimeArchitectureTest.kt` (or sibling) prevent CLI and MCP
   adapters from depending on direct DB, filesystem, HTTP, or Python-bridge
   APIs.
4. Runtime surface tests assert each active surface declares: owner package,
   supported operations, contract version, status. (Surfaces include
   `LauncherRuntime`, `InstallRuntime`, `ScaffoldRuntime`,
   `FeatureImplementWorkflowRuntime`, `FeatureVerifyWorkflowRuntime`.)
5. Kotlin tests catch contract drift without running the Python oracle for
   normal-use paths.

### Phase 6 — Python Retirement

6. `SKILL_BILL_RUNTIME=python` and `SKILL_BILL_MCP_RUNTIME=python` are removed
   from launcher resolution; setting them no longer routes to Python.
7. Python CLI/MCP runtime entry paths are removed from `skill_bill/launcher.py`
   and `pyproject.toml` console-scripts.
8. Each Python validator flagged "Python-backed and blocking retirement" in
   the Subtask 1 ownership table is either ported to Kotlin (with a test path
   from Phase 5) or explicitly retired.
9. Migration docs and installer docs no longer present Python as a normal
   runtime fallback.
10. Release notes are published with rollback guidance: rollback is "install
    previous release", not "select Python runtime".
11. Python remains only in explicitly retained tooling per the ownership
    table; it is no longer in active runtime ownership.

## Non-Goals

- Restructuring `docs/getting-started.md` /
  `docs/getting-started-for-teams.md` for adoption clarity (Subtask 4 — this
  subtask only removes Python references where they are factually wrong).
- The external-author dry run / governance-vs-packs section (Subtask 4).
- Any new schema work (Subtask 2).
- Any new packaging work (Subtask 2).

## Dependencies

- Subtask 1 (ownership table identifies which Python items must be ported
  vs. retired vs. retained).
- Subtask 2 (packaged Kotlin must be the default and strict schemas must be
  in place before Python retirement is safe).

## Validation Strategy

`bill-quality-check` plus the four-command validation gate. Phase 5 must be
fully green before any Phase 6 deletion lands. After Phase 6, repeat the
fresh-install rehearsal from Subtask 2 with no `SKILL_BILL_RUNTIME` env var
set anywhere — the launcher must succeed with packaged Kotlin only.

## Implementation Notes

- Phase 5 file pointers:
  - Existing golden fixture homes:
    `runtime-kotlin/runtime-cli/src/test/resources/golden/cli-import-review.json`
    and
    `runtime-kotlin/runtime-mcp/src/test/resources/golden/mcp-resolve-learnings.json`
    are the canonical patterns to extend.
  - Arch test home:
    `runtime-kotlin/runtime-core/src/test/kotlin/skillbill/architecture/RuntimeArchitectureTest.kt`.
  - `RuntimeSurfaceContract` is already used by `LauncherRuntime`,
    `InstallRuntime`, `ScaffoldRuntime`, `FeatureImplementWorkflowRuntime`,
    `FeatureVerifyWorkflowRuntime`. Add per-surface assertion tests.
- Phase 6 file pointers:
  - `SKILL_BILL_RUNTIME` and `SKILL_BILL_MCP_RUNTIME` consumers in
    `skill_bill/launcher.py`.
  - `pyproject.toml` console-scripts entry.
  - Python validators in scope per ownership table:
    `scripts/validate_agent_configs.py`, `scripts/skill_repo_contracts.py`,
    `scripts/validate_release_ref.py`, `scripts/migrate_to_content_md.py`.
  - `docs/migrations/SKILL-27-cutover-checklist.md` Rollback Plan section
    needs the "install previous release" guidance.
- Constraints: 2-space indent, no `Any`, no `Dispatchers.*`, no
  `kotlin.Result`, no `relaxed=true` mocks, AGENTS.md mandates the
  four-command gate.

## Handoff Prompt

Run `bill-feature-implement` on
`.feature-specs/SKILL-32-technical-stabilization/spec_subtask_3_contract-tests-and-python-retirement.md`.
