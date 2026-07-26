---
Status: Complete
Issue: SKILL-32
Parent spec: [spec.md](spec.md)
Parent subtask: [spec_subtask_3b_port-or-retire-python-backed-cli.md](spec_subtask_3b_port-or-retire-python-backed-cli.md)
---

# Subtask 3b_2: Scaffold-Shaped Payload-Mode Ports + Golden Fixture

## Goal

Port the four payload-mode scaffold-shaped CLI commands —
`NewSkillCommand`, `NewCommand`, `NewAddonCommand`, `CreateAndFillCommand` —
from the `runPythonScaffoldCli` / `runPythonCli` bridge to the native
`skillbill.scaffold.ScaffoldRuntime` (entry point
`skillbill.scaffold.scaffold(payload, dryRun)` in
`runtime-kotlin/runtime-core/src/main/kotlin/skillbill/scaffold/ScaffoldService.kt:119`).
Land the `NewSkillCommand --dry-run --format json` golden fixture under
`runtime-kotlin/runtime-cli/src/test/resources/golden/`, asserted by an
integration test. This closes parent AC 1's eighth CLI output and parent
3b AC 2.

Interactive-prompt modes for these commands are NOT in scope here — they are
either ported in 3b_3 (where the inspection commands and authoring loops
also live) or explicitly retired with a clear error message there. This
subtask focuses on the payload-mode happy path because that path is what the
MCP `new_skill_scaffold` tool already exercises natively, so the porting
risk is low.

## Scope

In scope:

1. Port `NewSkillCommand` payload mode (currently
   `runtime-kotlin/runtime-cli/src/main/kotlin/skillbill/cli/ScaffoldCliCommands.kt`
   around line 326) to call `skillbill.scaffold.scaffold(payload, dryRun)`
   directly. Keep the `--dry-run` and `--format json` flags wired the same
   way the MCP `new_skill_scaffold` tool wires them.
2. Land a golden fixture for `NewSkillCommand --dry-run --format json` under
   `runtime-kotlin/runtime-cli/src/test/resources/golden/` (follow the
   pattern from 3a's `cli-import-review.json`). Assert via an integration
   test that uses the existing `goldenJson(name, vararg replacements)`
   helper.
3. Port `NewCommand` payload mode (line ≈ 357) to the native scaffold
   runtime.
4. Port `NewAddonCommand` payload mode (locate via grep — likely a sibling
   of `NewSkillCommand` and `NewCommand`).
5. Port `CreateAndFillCommand` payload mode (locate via grep; likely calls
   `runPythonCli` rather than `runPythonScaffoldCli`).
6. Kotlin tests covering each ported payload-mode command's happy path
   under `runtime-kotlin/runtime-cli/src/test/kotlin/skillbill/cli/`.

Out of scope:

- Interactive-prompt modes for `NewSkillCommand` / `NewCommand` /
  `NewAddonCommand` / `CreateAndFillCommand` — owned by 3b_3.
- The remaining inspection / authoring commands (`ListSkills`, `ShowSkill`,
  `Explain`, `Validate`, `Upgrade`, `Render`, `Edit`, `Fill`) — owned by
  3b_3.
- Bridge helper deletion + arch ban promotion (3b_4).
- Any change under `skill_bill/` (3c).

## Acceptance Criteria

1. `NewSkillCommand` payload mode calls `skillbill.scaffold.scaffold(...)`
   directly. The `--dry-run --format json` path emits a JSON shape that
   matches the contract used by the MCP `new_skill_scaffold` tool.
2. A golden fixture for `NewSkillCommand --dry-run --format json` exists
   under `runtime-kotlin/runtime-cli/src/test/resources/golden/` and is
   asserted by an integration test that fails on contract drift. This
   closes parent 3b AC 2.
3. `NewCommand`, `NewAddonCommand`, and `CreateAndFillCommand` payload modes
   each call native Kotlin runtime code instead of the Python bridge, and
   each is covered by a Kotlin test exercising the new code path.
4. The bridge helpers (`runPythonCli`, `runPythonScaffoldCli`,
   `pythonProcess`) are still present, but the four payload-mode commands
   listed above no longer call them. Repo grep confirms.
5. The four-command validation gate is green:
   `(cd runtime-kotlin && ./gradlew check)`,
   `.venv/bin/python3 -m unittest discover -s tests`,
   `npx --yes agnix --strict .`,
   `.venv/bin/python3 scripts/validate_agent_configs.py`.
6. No new Detekt suppressions, no `kotlin.Result`, no `Any`, no
   `Dispatchers.*`, no `relaxed=true` mocks, 2-space indent, `.orEmpty()`
   instead of `?: ""`. JUnit5 + kotlin-test stays the runtime-kotlin test
   stack.

## Non-Goals

- Interactive-prompt modes (3b_3).
- Inspection / authoring command ports (3b_3).
- Bridge helper deletion (3b_4).
- Arch ban promotion (3b_4).

## Dependencies

- 3a complete (golden fixture pattern + arch test scaffolding in place).
- 3b_1 complete (the install + doctor ports prove the test idiom; their
  visibility-widening work may also surface patterns reused here).

## Validation Strategy

`bill-quality-check` plus the four-command validation gate. The new golden
fixture for `NewSkillCommand --dry-run --format json` must be asserted by
a test that fails on contract drift (sanity-check by hand once: edit the
fixture and confirm the test fails). For each ported payload command, a
Kotlin test must exercise the new code path.

## Implementation Notes

- File pointers:
  - Bridge call sites for the four payload-mode commands:
    `runtime-kotlin/runtime-cli/src/main/kotlin/skillbill/cli/ScaffoldCliCommands.kt`
    — `NewSkillCommand` ≈ line 326 (`runPythonScaffoldCli("new-skill", ...)`),
    `NewCommand` ≈ line 357 (`runPythonScaffoldCli("new", ...)`),
    `NewAddonCommand` and `CreateAndFillCommand` via grep (they currently
    use `runPythonCli`).
  - Native scaffold entry point:
    `runtime-kotlin/runtime-core/src/main/kotlin/skillbill/scaffold/ScaffoldService.kt:119`
    (`skillbill.scaffold.scaffold(payload, dryRun)`).
  - MCP equivalent for contract reference: the
    `mcp__skill-bill__new_skill_scaffold` MCP tool registration; mirror its
    payload shape.
  - Existing golden fixture pattern: 3a's
    `runtime-kotlin/runtime-cli/src/test/resources/golden/cli-import-review.json`
    plus the `goldenJson(name, vararg replacements)` helper.
- Port pattern: parse the CLI flags into the same scaffold payload type the
  MCP handler uses, call `skillbill.scaffold.scaffold(...)`, render the
  result via the existing JSON format. No shelling out.
- Constraints: 2-space indent, no `Any`, no `Dispatchers.*`, no
  `kotlin.Result`, no `relaxed=true` mocks, no new suppressions,
  `.orEmpty()` instead of `?: ""`. JUnit5 + kotlin-test, NOT kotest.

## Handoff Prompt

Run `bill-feature-implement` on
`.feature-specs/SKILL-32-technical-stabilization/spec_subtask_3b_2_scaffold-payload-ports.md`.
