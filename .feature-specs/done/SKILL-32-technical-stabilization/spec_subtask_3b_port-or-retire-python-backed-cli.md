---
Status: Complete
Issue: SKILL-32
Parent spec: [spec.md](spec.md)
Parent subtask: [spec_subtask_3_contract-tests-and-python-retirement.md](spec_subtask_3_contract-tests-and-python-retirement.md)
---

# Subtask 3b: Port-or-Retire Python-Backed CLI Commands

## Goal

Port (default) or explicitly retire (where porting is genuinely out of scope)
every Kotlin CLI command and `doctor <subject>` branch that currently bridges
to Python via `runPythonCli` / `runPythonScaffoldCli` / `pythonProcess` in
`ScaffoldCliCommands.kt` and `SystemCliCommands.kt:42`. Then DELETE the bridge
helpers and the `java.nio.file.Files` import that supports them, and PROMOTE
the architecture bans that 3a deferred — the FS / HTTP / SQL ban for the
`runtime-cli` source set — so the deletion is locked in regression tests.

This subtask also lands the `new-skill --dry-run --format json` golden fixture
(now native) which closes parent AC 1 for the last of the eight CLI outputs.

## Scope

In scope:

1. Port-or-retire decision and implementation for each Kotlin CLI command
   that currently bridges to Python (per the Subtask 1 ownership table —
   "Python-backed and blocking retirement"):
   - `NewSkillCommand` (interactive + payload modes) — port to
     `skillbill.scaffold.ScaffoldRuntime`. `--dry-run --format json` MUST be
     covered with a golden fixture closing parent AC 1.
   - `NewCommand` (interactive + payload modes) — port or retire.
   - `CreateAndFillCommand` — port or retire.
   - `NewAddonCommand` — port or retire.
   - `ListSkills`, `ShowSkill`, `Explain`, `Validate`, `Upgrade`, `Render`,
     `Edit`, `Fill` — port or retire each.
   - `InstallAgentPath`, `InstallDetectAgents`, `InstallLinkSkill` — port to
     the Kotlin install runtime (locate via grep for `InstallRuntime.contract`,
     likely `skillbill.install.InstallPrimitives` or sibling).
   - `SystemCliCommands.kt:42` `doctor <subject>` branch — port or retire.
2. Delete the Python-bridge helpers from
   `runtime-kotlin/runtime-cli/src/main/kotlin/skillbill/cli/ScaffoldCliCommands.kt`:
   `runPythonCli`, `runPythonScaffoldCli`, `pythonProcess` (≈ lines 483-625).
   Remove the `java.nio.file.Files` import that supported them.
3. Promote the architecture bans 3a deferred: extend the `runtime-cli` source
   set ban set in `RuntimeArchitectureTest.kt` to include `java.net.http`,
   `java.sql`, and `java.nio.file.Files`. Remove the `// TODO(3b)` marker
   3a left.
4. Decide and execute the disposition for the existing Python tests that
   import `skill_bill.cli` / `skill_bill.scaffold` (≈ 20 files such as
   `tests/test_cli.py`, `tests/test_scaffold.py`, etc.). The decision MUST
   be explicit and recorded inline:
   - migrate covered behavior to Kotlin-side fixtures landed in 3a or this
     subtask;
   - delete tests whose subject is a retired command;
   - or fence remaining tests behind a marker that the four-command quality
     check skips (with a documented reason).

Default disposition for each CLI command is PORT, not retire. Retirement is
acceptable ONLY where porting is provably out of scope; each retirement must
emit a clear user-facing error message naming the replacement path.

Out of scope:

- Removing `SKILL_BILL_RUNTIME` / `SKILL_BILL_MCP_RUNTIME` (3c).
- Deleting `skill_bill/cli.py` / `skill_bill/mcp_server.py` /
  `scripts/mcp_server_start.sh` (3c).
- Updating `LauncherRuntime.supportedOperations` to drop python-fallback (3c).
- Migration / installer doc edits, release notes (3c).
- Restructuring `docs/getting-started.md` (Subtask 4).
- Adding new MCP fixtures or arch tests beyond the FS / HTTP / SQL ban
  promotion (3a is the home for fixtures and bans).

## Acceptance Criteria

1. Every CLI command listed in Scope item 1 either:
   - is fully ported to a Kotlin runtime (no remaining call into the Python
     bridge), with at least one Kotlin test asserting the new code path; OR
   - is explicitly retired with a Kotlin-native error message naming the
     replacement and a Kotlin test asserting the error.
2. `NewSkillCommand --dry-run --format json` is covered by a golden fixture
   under `runtime-kotlin/runtime-cli/src/test/resources/golden/` asserted by
   an integration test, closing parent AC 1's eighth CLI output.
3. `runPythonCli`, `runPythonScaffoldCli`, and `pythonProcess` no longer
   exist in
   `runtime-kotlin/runtime-cli/src/main/kotlin/skillbill/cli/ScaffoldCliCommands.kt`,
   and the supporting `java.nio.file.Files` import has been removed. A repo
   grep confirms no remaining call site.
4. `RuntimeArchitectureTest.kt` (or sibling) bans `java.net.http`,
   `java.sql`, and `java.nio.file.Files` for the `runtime-cli` source set,
   and the test passes.
5. The disposition decision for each Python-side CLI/scaffold test is
   recorded inline (commit message, spec amendment, or a single
   `tests/RETIRED.md` note) and the four-command validation gate is green:
   `(cd runtime-kotlin && ./gradlew check)`,
   `.venv/bin/python3 -m unittest discover -s tests`,
   `npx --yes agnix --strict .`,
   `.venv/bin/python3 scripts/validate_agent_configs.py`.
6. No new Detekt suppressions, no `kotlin.Result`, no `Any`, no
   `Dispatchers.*`, no `relaxed=true` mocks, 2-space indent, `.orEmpty()`
   instead of `?: ""`. JUnit5 + kotlin-test stays the runtime-kotlin test
   stack.

## Non-Goals

- Removing `SKILL_BILL_RUNTIME` / `SKILL_BILL_MCP_RUNTIME` from launcher
  resolution (3c).
- Deleting any file under `skill_bill/` (3c).
- Updating launcher Python entry paths (3c).
- Restructuring or rewriting any user-facing documentation (Subtask 4).

## Dependencies

- 3a (the regression net — golden fixtures for native commands, MCP
  contract fixtures for the six tools, runtime surface contract tests, and
  the partial arch ban set — must be locked first so changes here cannot
  silently break a contract).
- Subtask 1 (the ownership table identifies which Python items must be
  ported vs. retired).
- Subtask 2a/2b/2c (packaged Kotlin must be the default and strict MCP
  schemas must already be in place).

## Validation Strategy

`bill-quality-check` plus the four-command validation gate. For each ported
command, a Kotlin test must exercise the new code path. For each retired
command, a Kotlin test must assert the user-facing error message. After
deletion of the bridge helpers, repo grep for `runPythonCli`,
`runPythonScaffoldCli`, `pythonProcess`, and `java.nio.file.Files` (under
`runtime-kotlin/runtime-cli/src/main/kotlin/`) must return zero hits.

## Close-Out Evidence

Subtasks 3b_1 through 3b_4 landed the implementation and teardown on
`feat/SKILL-32-install-and-doctor-ports`:

- 3b_1 ports install primitives and retires `doctor <subject>` branches with
  Kotlin-native replacement errors, covered by `CliRuntimeTest`.
- 3b_2 ports payload-mode scaffold commands, including the
  `new-skill --dry-run --format json` golden fixture
  `runtime-kotlin/runtime-cli/src/test/resources/golden/cli-new-skill-scaffold-dry-run.json`
  asserted by `CliScaffoldRuntimeTest`.
- 3b_3 ports inspection and authoring commands. `CliAuthoringParityTest`
  covers `list`, `show`, `explain`, `validate`, `upgrade`, `render`,
  `edit --body-file`, and `fill`, plus retired interactive/editor modes.
- 3b_4 deletes the Python bridge helpers, promotes runtime-cli FS / HTTP /
  SQL architecture bans in `RuntimeArchitectureTest.kt`, and records the
  Python-side CLI/scaffold test disposition for 3c/fallback.

Source audits on 2026-05-01 found no `runPythonCli`,
`runPythonScaffoldCli`, `pythonProcess`, `java.nio.file.Files`, or `Files.`
references under `runtime-kotlin/runtime-cli/src/main/kotlin/skillbill/cli`,
and no `allowedBridgeFiles` or `TODO(3b)` references in
`RuntimeArchitectureTest.kt`.

## Implementation Notes

- File pointers:
  - Bridge helpers to delete:
    `runtime-kotlin/runtime-cli/src/main/kotlin/skillbill/cli/ScaffoldCliCommands.kt`
    around lines 483-625 (`runPythonCli`, `runPythonScaffoldCli`,
    `pythonProcess`); remove the `java.nio.file.Files` import.
  - 14+ call sites in the same file; one in
    `runtime-kotlin/runtime-cli/src/main/kotlin/skillbill/cli/SystemCliCommands.kt:42`
    (`doctor <subject>`).
  - Kotlin scaffold runtime entry point: `skillbill.scaffold.ScaffoldRuntime`
    (locate via grep for `ScaffoldRuntime.contract`).
  - Kotlin install runtime entry point: locate via grep for
    `InstallRuntime.contract` — likely `skillbill.install.InstallPrimitives`.
  - Arch test home:
    `runtime-kotlin/runtime-core/src/test/kotlin/skillbill/architecture/RuntimeArchitectureTest.kt`.
  - Existing golden fixture home to extend:
    `runtime-kotlin/runtime-cli/src/test/resources/golden/`.
  - Python-side tests touching the CLI/scaffold: search `tests/` for imports
    of `skill_bill.cli` and `skill_bill.scaffold`; ≈ 20 files.
- Port pattern: each ported command should call directly into the Kotlin
  runtime API (the same one the MCP adapter calls); no shelling out, no
  reading or writing to disk outside the runtime's own primitives. State
  hoisting + repository-pattern conventions per AGENTS.md.
- Retirement pattern: when retirement is the right call, the command must
  print a stable, user-facing error message (e.g. "this command was retired
  in SKILL-32; use `skill-bill <replacement>` instead") and exit non-zero.
  Cover the message with a Kotlin test so the error is contract-locked.
- Test disposition for Python-side coverage: prefer migration to the
  Kotlin-side fixtures landed in 3a / this subtask. Where the Python test
  asserts behavior already covered by a Kotlin fixture, delete it. Where
  retirement makes the test obsolete, delete it. Avoid skip-markers unless
  there is a concrete reason recorded inline.
- Constraints: 2-space indent, no `Any`, no `Dispatchers.*`, no
  `kotlin.Result`, no `relaxed=true` mocks, no new suppressions,
  `.orEmpty()` instead of `?: ""`. AGENTS.md mandates the four-command gate.
  JUnit5 + kotlin-test, NOT kotest, inside `runtime-kotlin`. Use the
  `DispatcherProvider` abstraction; do not introduce `Dispatchers.*` in the
  ported commands.

## Handoff Prompt

Run `bill-feature-implement` on
`.feature-specs/SKILL-32-technical-stabilization/spec_subtask_3b_port-or-retire-python-backed-cli.md`.
