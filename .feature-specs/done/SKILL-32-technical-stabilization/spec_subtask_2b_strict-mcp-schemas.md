---
Status: Complete
Issue: SKILL-32
Parent spec: [spec.md](spec.md)
Parent subtask: [spec_subtask_2_packaging-and-strict-schemas.md](spec_subtask_2_packaging-and-strict-schemas.md)
---

# Subtask 2b: Strict MCP Schemas

## Goal

Publish strict MCP input schemas for every priority validating, persisting, and
telemetry tool, and reject unknown fields at the stdio boundary for strict
tools.

## Scope

This subtask owns the strict-schema half of SKILL-32 Subtask 2:

- Replace open-object schemas with strict input schemas for the priority MCP
  tools.
- Teach the Kotlin MCP stdio adapter to reject unknown properties before
  dispatching strict tool calls.
- Expand MCP schema tests so priority tools cannot regress to
  `additionalProperties: true`.
- Add coverage for required arguments, enum publication, unknown-property
  rejection, and zero-argument strict empty objects.

## Acceptance Criteria

1. Every priority tool has a strict input schema with no open
   `additionalProperties: true`:
   - `quality_check_started`, `quality_check_finished`
   - `feature_verify_started`, `feature_verify_finished`
   - `pr_description_generated`
   - `import_review`
   - `triage_findings`
   - `resolve_learnings`
   - feature-implement workflow `open`, `update`, `get`, `list`, `latest`,
     `resume`, and `continue`
   - feature-verify workflow `open`, `update`, `get`, `list`, `latest`,
     `resume`, and `continue`
   - `new_skill_scaffold`
2. The MCP stdio adapter rejects unknown fields at the boundary for strict
   tools.
3. `McpStdioServerTest` fails if a validating, persisting, or telemetry tool
   falls back to `additionalProperties: true`.
4. Schema coverage tests verify every required handler argument appears in
   `tools/list`.
5. Schema coverage tests verify every accepted enum is published.
6. Schema coverage tests verify unknown properties are rejected for strict
   tools.
7. Zero-argument tools declare empty strict objects rather than open objects.
8. Existing MCP payload tests still pass.

## Non-Goals

- Packaged runtime installer work.
- Removing Python CLI/MCP entry paths.
- Golden fixtures, architecture tests, or runtime surface tests.
- Public documentation updates.

## Dependencies

Subtask 2a should land first so packaging behavior is verified independently
before the MCP boundary changes.

## Implementation Notes

- `runtime-kotlin/runtime-mcp/src/main/kotlin/skillbill/mcp/McpToolRegistry.kt`
  currently defaults unspecified tools to open object schemas. Add or reuse a
  strict schema helper and switch the priority tools to explicit strict
  schemas.
- Use the runtime history entry
  `runtime-mcp-feature-implement-lifecycle-schemas` from 2026-04-30 as the
  strict-schema template.
- `McpStdioServer.kt`, `McpToolDispatcher.kt`, and `McpToolArguments.kt` are
  the likely boundary for rejecting unknown fields before handler dispatch.
- Handler argument names and enum domains are spread across lifecycle,
  workflow, review, quality-check, PR-description, learning, and scaffold
  handlers.
- Preserve the existing JUnit5 plus `kotlin-test` style.

## Validation Strategy

Run:

```bash
(cd runtime-kotlin && ./gradlew check)
```

## Handoff Prompt

Run `bill-feature-implement` on
`.feature-specs/SKILL-32-technical-stabilization/spec_subtask_2b_strict-mcp-schemas.md`.
