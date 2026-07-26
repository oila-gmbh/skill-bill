---
Status: Complete
Issue: SKILL-32
Parent spec: [spec.md](spec.md)
Parent subtask: [spec_subtask_2_packaging-and-strict-schemas.md](spec_subtask_2_packaging-and-strict-schemas.md)
---

# Subtask 2c: Packaging and Schema Integration Validation

## Goal

Run the full repository validation gate after the packaged-runtime and
strict-schema subtasks land together, fixing only integration fallout.

## Scope

This subtask owns the final integration pass for SKILL-32 Subtask 2:

- Run the full validation gate across Python tests, Kotlin runtime checks,
  agent config validation, and strict agent lint.
- Confirm packaged runtime resolution and strict MCP schemas coexist without
  breaking existing MCP payload behavior.
- Fix only integration fallout caused by the combination of Subtasks 2a and
  2b.

## Acceptance Criteria

1. The packaged CLI and MCP runtime path still resolves without Gradle after
   strict-schema changes.
2. Superseded: Python rollback env vars are not part of this validation pass
   because the runtime focus is Kotlin-only and Python is slated for deletion.
3. Strict MCP schema tests still reject unknown fields and prevent open-schema
   regressions.
4. Existing MCP payload tests pass.
5. The full four-command validation gate passes.

## Non-Goals

- New packaging features beyond Subtask 2a.
- New strict-schema behavior beyond Subtask 2b.
- Removing Python CLI/MCP entry paths.
- Public documentation updates.
- Golden fixtures, architecture tests, or runtime surface tests.

## Dependencies

Subtasks 2a and 2b must both land first.

## Validation Strategy

Run the full gate:

```bash
.venv/bin/python3 -m unittest discover -s tests
(cd runtime-kotlin && ./gradlew check)
npx --yes agnix --strict .
.venv/bin/python3 scripts/validate_agent_configs.py
```

Also rehearse the manual fresh-install path from the parent subtask when
practical: `./install.sh`, `skill-bill doctor --format json`, and
`skill-bill-mcp` initialize plus `tools/list` with no runtime rollback env vars
set.

## Handoff Prompt

Run `bill-feature-implement` on
`.feature-specs/SKILL-32-technical-stabilization/spec_subtask_2c_packaging-schema-integration-validation.md`.
