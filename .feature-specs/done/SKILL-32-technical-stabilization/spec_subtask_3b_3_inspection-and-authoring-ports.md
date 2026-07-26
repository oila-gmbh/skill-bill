---
Status: Complete
Issue: SKILL-32
Parent spec: [spec.md](spec.md)
Parent subtask: [spec_subtask_3b_port-or-retire-python-backed-cli.md](spec_subtask_3b_port-or-retire-python-backed-cli.md)
---

# Subtask 3b_3: Inspection + Authoring Command Ports + Interactive-Mode Disposition

## Goal

Port (default) or explicitly retire the remaining Python-bridged Kotlin CLI
commands left after 3b_1 (install + doctor) and 3b_2 (payload-mode
scaffolds): the inspection commands (`ListSkills`, `ShowSkill`, `Explain`,
`Validate`), the wrapper-regen authoring commands (`Upgrade`, `Render`,
`Edit`, `Fill`), and the interactive-prompt modes for `NewSkillCommand`,
`NewCommand`, `NewAddonCommand`, and `CreateAndFillCommand` that 3b_2 left
on the bridge.

This is the largest port-or-retire subtask. The expectation is that
inspection commands port cheaply (they only read native scaffold-aware
structures), wrapper-regen commands need careful native authoring code
behind them, and interactive prompt modes are the most likely retirement
candidates per parent 3b's "Retirement is acceptable ONLY where porting is
provably out of scope" rule. Each retirement decision MUST be defended in
the commit message and covered by a Kotlin test asserting the error.

## Scope

In scope:

1. Port (default) or retire `ListSkills` (≈ ScaffoldCliCommands.kt line 95).
2. Port or retire `ShowSkill` (≈ line 127).
3. Port or retire `Explain` (≈ line 156).
4. Port or retire `Validate` (≈ line 185).
5. Port or retire `Upgrade` (≈ line 227).
6. Port or retire `Render` (≈ line 254).
7. Port or retire `Edit` — payload mode line ≈ 284, interactive/editor mode
   line ≈ 314.
8. Port or retire `Fill` (≈ line 382).
9. Decide and execute disposition for the interactive-prompt modes of
   `NewSkillCommand` (≈ line 314), `NewCommand` (≈ line 345), and the
   interactive paths of `NewAddonCommand` and `CreateAndFillCommand`. Per
   the digest, "Interactive prompt modes for new-skill / new / new-addon /
   create-and-fill / edit-editor — retirement acceptable per spec if port
   cost is high." Each retirement must emit a clear, stable user-facing
   error naming the replacement (e.g. point to the payload-mode usage
   ported in 3b_2) and be covered by a Kotlin test asserting that error.
10. Kotlin tests under
    `runtime-kotlin/runtime-cli/src/test/kotlin/skillbill/cli/` covering
    each ported command's happy path and each retired command's error
    contract.

Out of scope:

- The four payload-mode scaffold ports (3b_2).
- Install + doctor ports (3b_1).
- Bridge helper deletion + arch ban promotion (3b_4).
- Any change under `skill_bill/` (3c).
- Disposition recording for Python-side tests — that single inline summary
  is owned by 3b_4. THIS subtask may delete Python tests whose subject is
  retired or fully covered by new Kotlin tests, with rationale in the
  commit message; the consolidated summary is finalized in 3b_4.

## Acceptance Criteria

1. Each command listed in this subtask's Scope (1)-(9) is either:
   - fully ported to native Kotlin (no remaining call into the Python
     bridge), with at least one Kotlin test exercising the new code path;
     OR
   - explicitly retired with a Kotlin-native error message naming the
     replacement and a Kotlin test asserting the error.
2. The bridge helpers (`runPythonCli`, `runPythonScaffoldCli`,
   `pythonProcess`) are still present, but no command listed in 3b_1, 3b_2,
   or 3b_3's Scope still calls them. Repo grep confirms.
3. Every retirement choice in this subtask is justified in the commit
   message — the spec's default disposition is PORT, and retirement is the
   exception, not the rule. The justification cites why porting was
   provably out of scope (e.g. "interactive editor loop required terminal
   capabilities we don't expose; payload-mode replacement covers the
   automated use case").
4. The four-command validation gate is green:
   `(cd runtime-kotlin && ./gradlew check)`,
   `.venv/bin/python3 -m unittest discover -s tests`,
   `npx --yes agnix --strict .`,
   `.venv/bin/python3 scripts/validate_agent_configs.py`.
5. No new Detekt suppressions, no `kotlin.Result`, no `Any`, no
   `Dispatchers.*`, no `relaxed=true` mocks, 2-space indent, `.orEmpty()`
   instead of `?: ""`. JUnit5 + kotlin-test stays the runtime-kotlin test
   stack.

## Non-Goals

- Bridge helper deletion (3b_4).
- Arch test ban promotion (3b_4).
- Any change to launcher Python-fallback resolution.
- The consolidated Python-side test disposition summary (3b_4).

## Dependencies

- 3a complete (regression net).
- 3b_1 complete (install + doctor ports prove the idiom; doctor in
  particular establishes how to fan out subject-keyed dispatch in native
  Kotlin).
- 3b_2 complete (payload-mode scaffold ports landed AND the
  `NewSkillCommand --dry-run --format json` golden fixture is in place;
  this subtask leans on that fixture pattern when porting inspection
  commands like `ShowSkill --format json` if their JSON shape needs a
  golden lock).

## Validation Strategy

`bill-quality-check` plus the four-command validation gate. For each ported
command, a Kotlin test must exercise the new code path. For each retired
command, a Kotlin test must assert the user-facing error contract. After
this subtask lands, the only remaining `runPythonCli` /
`runPythonScaffoldCli` / `pythonProcess` references in the runtime-cli
source set should be the helper definitions themselves (lines ≈ 483-661 of
`ScaffoldCliCommands.kt`). Confirm via repo grep before handing off to
3b_4.

## Implementation Notes

- File pointers:
  - All bridge call sites referenced in this subtask's Scope live in
    `runtime-kotlin/runtime-cli/src/main/kotlin/skillbill/cli/ScaffoldCliCommands.kt`
    between lines 95 and 422.
  - Native scaffold runtime: `skillbill.scaffold.ScaffoldRuntime` /
    `skillbill.scaffold.scaffold(payload, dryRun)` in
    `runtime-kotlin/runtime-core/src/main/kotlin/skillbill/scaffold/`.
  - For inspection commands (`ListSkills`, `ShowSkill`, `Explain`,
    `Validate`), the native code path may need a thin
    "scaffold inspection" facade alongside `ScaffoldRuntime` if one does
    not already exist — locate via grep for existing list / show / explain
    Kotlin code.
  - For wrapper-regen commands (`Upgrade`, `Render`, `Edit`, `Fill`),
    expect to surface or build native Kotlin equivalents of the Python
    wrapper-regen helpers. If the cost is provably out of scope for one
    feature-implement run, retirement with a clear replacement message is
    acceptable per parent rules.
- Port pattern: each ported command should call directly into the Kotlin
  runtime API (the same one the MCP adapter calls); no shelling out, no
  reading or writing to disk outside the runtime's own primitives.
- Retirement pattern: stable user-facing error message naming the
  replacement (e.g. "this interactive flow was retired in SKILL-32; use
  `skill-bill new-skill --payload <file>` instead"), exit non-zero,
  assertion in a Kotlin test.
- Constraints: 2-space indent, no `Any`, no `Dispatchers.*`, no
  `kotlin.Result`, no `relaxed=true` mocks, no new suppressions,
  `.orEmpty()` instead of `?: ""`. JUnit5 + kotlin-test, NOT kotest.

## Handoff Prompt

Run `bill-feature-implement` on
`.feature-specs/SKILL-32-technical-stabilization/spec_subtask_3b_3_inspection-and-authoring-ports.md`.
