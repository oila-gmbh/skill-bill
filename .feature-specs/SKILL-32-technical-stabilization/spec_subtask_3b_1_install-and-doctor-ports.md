---
Status: Complete
Issue: SKILL-32
Parent spec: [spec.md](spec.md)
Parent subtask: [spec_subtask_3b_port-or-retire-python-backed-cli.md](spec_subtask_3b_port-or-retire-python-backed-cli.md)
---

# Subtask 3b_1: Install Ports + Doctor Subject Port (Foundation)

## Goal

Land the smallest, lowest-risk Python-bridge ports first: the three install
subcommands (`InstallAgentPath`, `InstallDetectAgents`, `InstallLinkSkill`) and
the `doctor <subject>` branch in `SystemCliCommands.kt:42`. As part of this
subtask, expose any required surface from `skillbill.install.InstallPrimitives`
so the CLI adapter can call native code without crossing module visibility
boundaries. The bridge helpers (`runPythonCli`, `runPythonScaffoldCli`,
`pythonProcess`) STAY in place — they will be deleted in 3b_4 once every
caller has been ported.

This is the foundation subtask for the 3b decomposition. Its ports are small,
have zero new authoring logic, and exercise the test idiom (JUnit5 +
kotlin-test, `CliRuntime.run(args, ctx)` against temp dirs) so later subtasks
can copy the pattern.

## Scope

In scope:

1. Visibility / facade work for `skillbill.install.InstallPrimitives` (locate
   via grep for `InstallRuntime.contract` and the `InstallRuntime` object in
   `runtime-kotlin/runtime-core/src/main/kotlin/skillbill/install/`). If the
   existing primitives are `internal` or otherwise not callable from
   `runtime-cli`, widen visibility or introduce a thin facade. Do NOT change
   their behavior; only adjust what `runtime-cli` can reach.
2. Port `InstallAgentPath` (currently
   `runtime-kotlin/runtime-cli/src/main/kotlin/skillbill/cli/ScaffoldCliCommands.kt`
   line ≈ 446) to call the native install runtime instead of `runPythonCli`.
3. Port `InstallDetectAgents` (line ≈ 455) likewise.
4. Port `InstallLinkSkill` (line ≈ 479) likewise.
5. Port the `doctor <subject>` branch in
   `runtime-kotlin/runtime-cli/src/main/kotlin/skillbill/cli/SystemCliCommands.kt`
   (line 42) — for each supported subject (verify which by reading the
   Python-side doctor logic), call the matching native runtime / facade.
   Where a subject has no native equivalent yet, retirement is acceptable
   per the parent rules (clear user-facing error naming the replacement,
   covered by a Kotlin test).
6. Kotlin tests (JUnit5 + kotlin-test) covering each ported command's happy
   path. Tests live under
   `runtime-kotlin/runtime-cli/src/test/kotlin/skillbill/cli/`. Use the
   existing `CliRuntime.run(args, ctx)`-against-temp-dirs idiom.

Out of scope:

- Deleting `runPythonCli` / `runPythonScaffoldCli` / `pythonProcess` (3b_4).
- Removing the `java.nio.file.Files` import (3b_4).
- Promoting the `runtime-cli` arch bans (3b_4).
- Porting any scaffold / authoring / new-skill commands (3b_2 and 3b_3).
- Any change under `skill_bill/` (3c).
- Disposition of Python-side `tests/test_install*.py` etc. (3b_4 records the
  full disposition; this subtask may delete tests whose subject is fully
  covered by the new Kotlin tests, with the deletion rationale recorded in
  the commit message).

## Acceptance Criteria

1. `InstallAgentPath`, `InstallDetectAgents`, `InstallLinkSkill` no longer
   call `runPythonCli`. Each is implemented in terms of the native install
   runtime and is covered by at least one Kotlin test exercising the new
   code path.
2. `SystemCliCommands.kt:42`'s `doctor <subject>` branch routes every
   supported subject to a native code path. Subjects without a native
   equivalent are retired with a stable user-facing error message naming
   the replacement, and that message is asserted by a Kotlin test.
3. The bridge helpers `runPythonCli`, `runPythonScaffoldCli`,
   `pythonProcess` are still present (deletion is owned by 3b_4) — but no
   command listed in this subtask's Scope still calls them. A repo grep
   confirms.
4. `InstallPrimitives` (or the facade introduced) is reachable from
   `runtime-cli` without requiring `internal` visibility leaks. If a facade
   was introduced, it lives next to `InstallPrimitives` in
   `runtime-kotlin/runtime-core/src/main/kotlin/skillbill/install/`.
5. The four-command validation gate is green:
   `(cd runtime-kotlin && ./gradlew check)`,
   `.venv/bin/python3 -m unittest discover -s tests`,
   `npx --yes agnix --strict .`,
   `.venv/bin/python3 scripts/validate_agent_configs.py`.
6. No new Detekt suppressions, no `kotlin.Result`, no `Any`, no
   `Dispatchers.*`, no `relaxed=true` mocks, 2-space indent, `.orEmpty()`
   instead of `?: ""`. JUnit5 + kotlin-test (NOT kotest) inside
   `runtime-kotlin`. Use the `DispatcherProvider` abstraction.

## Non-Goals

- Bridge helper deletion.
- Arch test ban promotion.
- Any port of scaffold / authoring / new-* commands.
- Any change to launcher Python-fallback resolution.

## Dependencies

- 3a complete (regression net in place).

## Validation Strategy

`bill-quality-check` plus the four-command validation gate. For each ported
command, a Kotlin test must exercise the new code path. For each retired
doctor subject, a Kotlin test must assert the user-facing error message.

## Implementation Notes

- File pointers:
  - Install command call sites:
    `runtime-kotlin/runtime-cli/src/main/kotlin/skillbill/cli/ScaffoldCliCommands.kt`
    around lines 446 (`InstallAgentPath`), 455 (`InstallDetectAgents`),
    479 (`InstallLinkSkill`).
  - `doctor <subject>`:
    `runtime-kotlin/runtime-cli/src/main/kotlin/skillbill/cli/SystemCliCommands.kt:42`.
  - Install runtime entry point:
    `runtime-kotlin/runtime-core/src/main/kotlin/skillbill/install/InstallRuntime.kt`
    plus sibling `InstallPrimitives.kt` (verify exact filename via grep).
  - Test home: `runtime-kotlin/runtime-cli/src/test/kotlin/skillbill/cli/`.
- Port pattern: each ported command should call directly into the Kotlin
  runtime API (the same one the MCP adapter calls); no shelling out, no
  reading or writing to disk outside the runtime's own primitives.
- Retirement pattern: stable user-facing error message, e.g. "this subject
  was retired in SKILL-32; use `skill-bill <replacement>` instead", exit
  non-zero, assertion in a Kotlin test.
- Constraints: 2-space indent, no `Any`, no `Dispatchers.*`, no
  `kotlin.Result`, no `relaxed=true` mocks, no new suppressions,
  `.orEmpty()` instead of `?: ""`. JUnit5 + kotlin-test, NOT kotest.

## Handoff Prompt

Run `bill-feature-implement` on
`.feature-specs/SKILL-32-technical-stabilization/spec_subtask_3b_1_install-and-doctor-ports.md`.
