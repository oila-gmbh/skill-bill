---
Status: Not Started
Issue: SKILL-32
Parent spec: [spec.md](spec.md)
---

# Subtask 1: Restore Kotlin Runtime Green Gate + Inventory Python Ownership

## Goal

Land a clean four-command validation gate from a fresh checkout, then publish an
explicit retirement scope (runtime ownership table) before any deletion or
repackaging work begins. This subtask delivers Phase 1 + Phase 2 of the parent
spec and becomes the foundation every later subtask depends on.

## Scope

This subtask owns:

1. Phase 1 — fixing the three current red checks in `runtime-application` so the
   Kotlin validation gate is green.
2. Phase 2 — extending `docs/migrations/SKILL-27-cutover-checklist.md` with a
   complete runtime ownership table classifying every CLI command, MCP tool,
   scaffold/authoring command, install primitive, validation script, and
   release/support script.

This subtask does NOT touch packaging, schemas, contract tests, Python
retirement, or docs beyond the cutover checklist.

## Acceptance Criteria

1. All four validation commands pass from a clean checkout:
   - `(cd runtime-kotlin && ./gradlew check)`
   - `.venv/bin/python3 -m unittest discover -s tests`
   - `npx --yes agnix --strict .`
   - `.venv/bin/python3 scripts/validate_agent_configs.py`
2. The current Detekt/Spotless failures are fixed without adding new
   suppressions:
   - `LifecycleTelemetryValidation.kt` `TooManyFunctions` (split into focused
     validator objects or section-owned files).
   - `TelemetryService.autoSync` `ReturnCount` (replace guard-return chain with
     a single readable predicate or branch).
   - `validateCompletedValidationResult` Spotless reformatting.
3. `docs/migrations/SKILL-27-cutover-checklist.md` includes an updated runtime
   ownership table that classifies every item in scope as one of:
   - Kotlin-owned;
   - Python-backed but acceptable as script/tooling;
   - Python-backed and blocking retirement;
   - deliberately retained compatibility fallback.
4. The checklist explicitly states no Python deletion starts until each blocking
   item has an owner and a test path; entries that need contract tests are
   flagged for Subtask 3.
5. If the Phase 1 fix produces reusable knowledge, it is recorded in
   `runtime-kotlin/agent/history.md`.

## Non-Goals

- Packaging Kotlin distributions (Subtask 2).
- Strict MCP schemas (Subtask 2).
- Golden fixtures or arch tests (Subtask 3).
- Removing any Python entry path (Subtask 3).
- Updating getting-started docs (Subtask 4).

## Dependencies

None — this is the foundation subtask. Run this first.

## Validation Strategy

`bill-quality-check` plus the four-command validation gate listed in the parent
spec. Phase 2 work is validated by inspection of the cutover checklist diff
against the live runtime surface.

## Implementation Notes

- Phase 1 fix targets:
  - `runtime-kotlin/runtime-application/src/main/kotlin/skillbill/application/LifecycleTelemetryValidation.kt`
    — split helpers into focused validator objects rather than one giant class.
  - `runtime-kotlin/runtime-application/src/main/kotlin/skillbill/application/TelemetryService.kt`
    at `autoSync` — collapse early-return chain into a single decision branch.
  - `validateCompletedValidationResult` — apply Spotless `ktfmt` formatting.
- Phase 2 target: `docs/migrations/SKILL-27-cutover-checklist.md`. Existing
  structure has Cutover Gates with 8 CLI + 6 MCP parity payloads at lines
  ~45-61 and a Rollback Plan section. Append the ownership table near the
  Cutover Gates so Subtask 3 can cross-reference it.
- Inventory inputs: walk `runtime-kotlin/runtime-cli` mainClass surface,
  `runtime-kotlin/runtime-mcp` `McpToolRegistry` registrations, the
  `skill_bill/launcher.py` Python fallback paths, `scripts/*.py` (especially
  `validate_agent_configs.py`, `skill_repo_contracts.py`,
  `validate_release_ref.py`, `migrate_to_content_md.py`), and
  `install.sh`/`pyproject.toml` entry points.
- Constraints from AGENTS.md / personal rules: 2-space indent, no
  `kotlin.Result`, no `Any`, no `Dispatchers.*`, no new suppressions, no
  `relaxed=true` mocks, JUnit5+kotlin-test stays the test stack here.

## Handoff Prompt

Run `bill-feature-implement` on
`.feature-specs/SKILL-32-technical-stabilization/spec_subtask_1_runtime-green-and-ownership.md`.
