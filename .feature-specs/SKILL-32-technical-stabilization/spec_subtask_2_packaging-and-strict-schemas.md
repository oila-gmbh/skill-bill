---
Status: Superseded by 2a / 2b / 2c
Issue: SKILL-32
Parent spec: [spec.md](spec.md)
---

# Subtask 2: Packaged Kotlin Runtime + Strict MCP Schemas

> Superseded by focused subtasks 2a, 2b, and 2c. The combined packaging and
> schema scope was decomposed so packaged runtime installer work, strict MCP
> schemas, and integration validation could be implemented and validated
> independently.

## Goal

Make the default installed runtime use packaged Kotlin executables (no Gradle
`run` invocation), and tighten MCP tool schemas so unknown fields are rejected
at the stdio boundary for every validating/persisting/telemetry tool. Together
these two phases unblock Phase 6 (Python retirement) by removing the last
runtime quality blockers.

This subtask delivers Phase 3 + Phase 4 of the parent spec.

## Scope

This subtask owns:

1. Phase 3 — produce JVM application distributions for `runtime-cli` and
   `runtime-mcp`; teach `./install.sh` to build/locate them; update the
   launcher so installed `skill-bill` and `skill-bill-mcp` resolve to packaged
   bin scripts; preserve `SKILL_BILL_RUNTIME=python` and
   `SKILL_BILL_MCP_RUNTIME=python` as rollback envs (still removed in
   Subtask 3); add installer tests for the four required paths.
2. Phase 4 — replace open-object schemas with strict input schemas for every
   listed priority tool; teach the MCP stdio adapter to reject unknown fields
   for strict tools at the boundary; expand `McpStdioServerTest` so it fails
   when a strict tool regresses to `additionalProperties: true`; add coverage
   tests asserting required args appear in `tools/list`, enums are published,
   unknown properties are rejected, and zero-arg tools use strict empty
   objects.

## Acceptance Criteria

### Phase 3 — Packaging

1. `runtime-cli` and `runtime-mcp` produce JVM application distributions via
   the existing `application` plugin (mainClass already `skillbill.cli.MainKt`
   and `skillbill.mcp.MainKt`).
2. `./install.sh` builds and locates those distributions; on a fresh install
   `skill-bill doctor --format json` and `skill-bill-mcp` resolve to the
   packaged bin scripts without invoking Gradle.
3. `SKILL_BILL_RUNTIME=python` and `SKILL_BILL_MCP_RUNTIME=python` continue to
   resolve to the Python fallback (final removal happens in Subtask 3).
4. Installer tests cover all four cases:
   - packaged CLI path resolution;
   - packaged MCP path resolution;
   - missing-distribution error message;
   - fallback environment variables.
5. Documentation in `install.sh` comments / launcher header names the packaged
   path and the development fallback. Public docs (getting-started) are NOT
   updated here — that lives in Subtask 4.

### Phase 4 — Strict MCP Schemas

6. Every priority tool has a strict input schema (no `additionalProperties:
   true`):
   - `quality_check_started`, `quality_check_finished`
   - `feature_verify_started`, `feature_verify_finished`
   - `pr_description_generated`
   - `import_review`
   - `triage_findings`
   - `resolve_learnings`
   - workflow `open`/`update`/`get`/`list`/`latest`/`resume`/`continue`
     (feature-implement and feature-verify variants)
   - `new_skill_scaffold`
7. The MCP stdio adapter rejects unknown fields at the boundary for strict
   tools.
8. `McpStdioServerTest` fails if a validating/persisting/telemetry tool falls
   back to `additionalProperties: true`.
9. Schema coverage tests verify:
   - every required handler argument is present in `tools/list`;
   - every accepted enum is published;
   - unknown properties are rejected for strict tools;
   - zero-argument tools declare empty strict objects, not open objects.
10. Existing MCP payload tests still pass.

## Non-Goals

- Removing Python CLI/MCP entry paths (Subtask 3).
- Golden fixtures, arch tests, or runtime surface tests (Subtask 3).
- Updating `docs/getting-started.md` or `docs/getting-started-for-teams.md`
  (Subtask 4).
- Native-image / single-file distributions (deferred per resolved decision Q4).

## Dependencies

- Subtask 1 (runtime green gate + ownership table) MUST land first. Do not
  layer packaging or schema work onto a red runtime gate.

## Validation Strategy

`bill-quality-check` plus the four-command validation gate. Plus a manual
fresh-install rehearsal: `./install.sh` from a clean clone followed by
`skill-bill doctor --format json` and `skill-bill-mcp` initialize+tools/list
without `SKILL_BILL_RUNTIME` set.

## Implementation Notes

- Phase 3 entry points:
  - `skill_bill/launcher.py` currently invokes
    `runtime-kotlin/gradlew -q :runtime-cli:run` — replace with packaged bin
    script resolution; keep Python fallback path behind
    `SKILL_BILL_RUNTIME=python`.
  - `install.sh` currently runs `python3 -m skill_bill install` plus venv
    setup; add `./gradlew :runtime-cli:installDist
    :runtime-mcp:installDist` (or equivalent) and locate the resulting
    `build/install/<name>/bin/<name>` scripts.
  - `runtime-cli` and `runtime-mcp` `build.gradle.kts` already apply the
    `application` plugin with mainClass — no plugin changes needed.
  - JDK becomes a documented prerequisite (per resolved decision Q2).
- Phase 4 targets:
  - `runtime-kotlin/runtime-mcp/src/main/kotlin/skillbill/mcp/McpToolRegistry.kt`
    — `openObjectSchema()` defaults to `additionalProperties=true`. Add a
    strict counterpart and switch each priority tool registration to it.
  - `McpStdioServer.kt` + `McpToolDispatcher.kt` + `McpToolArguments.kt` — add
    boundary rejection for unknown fields on strict tools.
  - `McpStdioServerTest.kt` — feature_implement_started/finished already
    assert strict-schema invariants; reuse that template for every priority
    tool.
  - Boundary history note: runtime-mcp-feature-implement-lifecycle-schemas
    (2026-04-30) is the canonical reusable strict-schema template for these
    conversions.
- Constraints: 2-space indent, no `Any`, no `Dispatchers.*`, no
  `kotlin.Result`, JUnit5+kotlin-test convention preserved (do NOT introduce
  Kotest mid-stream), no new Detekt suppressions, AGENTS.md mandates the
  four-command gate.

## Handoff Prompt

Run `bill-feature-implement` on
`.feature-specs/SKILL-32-technical-stabilization/spec_subtask_2_packaging-and-strict-schemas.md`.
