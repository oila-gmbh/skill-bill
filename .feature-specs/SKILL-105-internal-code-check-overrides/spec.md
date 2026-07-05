# SKILL-105 - Internal code-check platform overrides

Created: 2026-07-05
Status: Complete
- Agent: codex
Issue key: SKILL-105
Parent: SKILL-104 follow-up

## Problem

SKILL-104 made platform-pack code-review skills internal sidecars of
`bill-code-review`, but deliberately left platform quality-check overrides
listed. That leaves stack-specific commands such as `bill-kotlin-code-check`
visible even though the intended user-facing entry point is `bill-code-check`.

The platform-pack internal-skill mechanism already supports selection-aware
sidecars. SKILL-105 applies that existing mechanism to quality-check overrides
and updates the quality-check routing contract so users only see
`bill-code-check`.

## Goals

- `bill-code-check` remains the only user-facing quality-check entry point.
- Platform-pack quality-check overrides install as selected-pack sidecars of
  `bill-code-check`.
- Routed skill identity strings such as `bill-kotlin-code-check` remain stable
  for manifests, routing output, and telemetry.
- New platform-pack scaffolds generate quality-check overrides with the correct
  internal classification.
- Contract validation fails loudly when a declared quality-check file is not
  internal to `bill-code-check`.

## Non-Goals

- Changing skill names, manifest paths, telemetry field names, or routing
  identity values.
- Changing code-review internals from SKILL-104.
- Adding nested internal skills or manifest-level internality flags.
- Changing native-agent behavior.

## Acceptance Criteria

1. Existing built-in platform quality-check `content.md` files declare
   `internal-for: bill-code-check`.
2. `bill-code-check` routing prose tells agents to read the routed
   `<skill-name>.md` sibling sidecar and not use the Skill tool for
   stack-specific quality-check overrides.
3. `ShellContentLoader.loadQualityCheckContent` rejects declared
   quality-check content that does not declare `internal-for: bill-code-check`
   with a typed, actionable error.
4. New platform-pack scaffolds write `internal-for: bill-code-check` into the
   generated quality-check `content.md`.
5. Install/apply tests verify selected quality-check overrides are not linked
   as standalone agent skills and are present as sidecars inside
   `bill-code-check`.
6. Documentation no longer describes pack quality-check skills as listed
   commands and names SKILL-105 as the follow-up to SKILL-104.
7. Validation passes for the changed scope: targeted Gradle tests and
   `skill-bill validate` at minimum; broader checks are run if the targeted
   surface indicates risk.
