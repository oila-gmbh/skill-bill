---
Status: Complete
Issue: SKILL-32
Parent spec: [spec.md](spec.md)
Parent subtask: [spec_subtask_3b_port-or-retire-python-backed-cli.md](spec_subtask_3b_port-or-retire-python-backed-cli.md)
---

# Subtask 3b_4: Bridge Teardown, Arch Ban Promotion, and Python Test Disposition

## Goal

Now that 3b_1, 3b_2, and 3b_3 have ported or retired every Kotlin CLI
caller of the Python bridge, DELETE the bridge itself. Remove
`runPythonCli`, `runPythonScaffoldCli`, `pythonProcess`, the supporting
`java.nio.file.Files` import, and any other dead support code in
`ScaffoldCliCommands.kt`. PROMOTE the architecture bans 3a deferred — the
FS / HTTP / SQL ban for the `runtime-cli` source set in
`RuntimeArchitectureTest.kt` — and remove the `// TODO(3b)` marker plus
the `allowedBridgeFiles` allowlist entries for `ScaffoldCliCommands.kt` and
`SystemCliCommands.kt`. Record the consolidated disposition for the ~10
Python-side `cli` / `scaffold` test files.

This subtask is the close-out of the 3b decomposition. It can ONLY land
after 3b_1, 3b_2, and 3b_3, because the bridge cannot die while any caller
remains.

## Scope

In scope:

1. Delete `runPythonCli`, `runPythonScaffoldCli`, and `pythonProcess` from
   `runtime-kotlin/runtime-cli/src/main/kotlin/skillbill/cli/ScaffoldCliCommands.kt`
   (the helpers spanning lines ≈ 483-661, including
   `normalizeScaffoldCliResult`, `normalizeStartedPayload`,
   `normalizeFinishedPayload`, `readScaffoldPayload`, `collectCliResult`,
   `buildPythonPath`, `findRepoRoot` if no other caller uses them; verify
   each helper's call graph before deletion).
2. Remove the `java.nio.file.Files` import (line 18). Audit lines 17
   (`java.io.File`) and 19 (`java.nio.file.Path`) — remove if also
   unused.
3. Promote the deferred arch bans in
   `runtime-kotlin/runtime-core/src/test/kotlin/skillbill/architecture/RuntimeArchitectureTest.kt`:
   extend the runtime-cli source set ban set to include `java.net.http`,
   `java.sql`, and `java.nio.file.Files`. Remove the `// TODO(3b)` marker
   3a left at lines 261-262.
4. Remove `ScaffoldCliCommands.kt` and `SystemCliCommands.kt` from the
   `allowedBridgeFiles` allowlist in `RuntimeArchitectureTest.kt`.
5. Decide and record the disposition for the ~10 Python-side tests in
   `tests/` whose subject is `skill_bill.cli` or `skill_bill.scaffold`
   (per the digest: `tests/test_cli.py`, `tests/test_scaffold.py`, etc.).
   The consolidated record lives inline as either the commit message body,
   a single `tests/RETIRED.md` note, or an amendment to this spec. The
   digest's recommendation is: "tests/test_cli.py and tests/test_scaffold.py
   deleted class-by-class as commands port; tests/test_review_pipeline_smoke.py
   and 7 other files with single skill_bill.cli imports left for 3c." This
   subtask MAY delete the test files retired in 3b_1 / 3b_2 / 3b_3 if not
   already deleted, and MUST record the final per-file disposition.
6. Repo-grep audit confirming zero remaining call sites for `runPythonCli`,
   `runPythonScaffoldCli`, `pythonProcess`, and zero `java.nio.file.Files`
   imports under `runtime-kotlin/runtime-cli/src/main/kotlin/`.

Out of scope:

- Removing `SKILL_BILL_RUNTIME` / `SKILL_BILL_MCP_RUNTIME` (3c).
- Deleting any file under `skill_bill/` (3c).
- Updating `LauncherRuntime.supportedOperations` to drop python-fallback
  (3c).
- Migration / installer doc edits, release notes (3c).
- Any further CLI ports — by this point every Python-bridged Kotlin CLI
  command has been ported or retired in 3b_1 / 3b_2 / 3b_3.

## Acceptance Criteria

1. `runPythonCli`, `runPythonScaffoldCli`, and `pythonProcess` no longer
   exist in
   `runtime-kotlin/runtime-cli/src/main/kotlin/skillbill/cli/ScaffoldCliCommands.kt`.
   The supporting `java.nio.file.Files` import has been removed. A repo
   grep confirms no remaining call site (closes parent 3b AC 3).
2. `RuntimeArchitectureTest.kt` extends the runtime-cli source set ban set
   to include `java.net.http`, `java.sql`, and `java.nio.file.Files`. The
   `// TODO(3b)` marker is gone. `ScaffoldCliCommands.kt` and
   `SystemCliCommands.kt` have been removed from `allowedBridgeFiles`.
   The test passes (closes parent 3b AC 4).
3. Disposition decision for each Python-side cli/scaffold test file (~10
   files) is recorded inline (commit message, spec amendment, or single
   `tests/RETIRED.md` note). Files retired here are deleted from the repo;
   files left for 3c are explicitly named with the rationale (closes
   parent 3b AC 5).
4. The four-command validation gate is green:
   `(cd runtime-kotlin && ./gradlew check)`,
   `.venv/bin/python3 -m unittest discover -s tests`,
   `npx --yes agnix --strict .`,
   `.venv/bin/python3 scripts/validate_agent_configs.py`.
5. No new Detekt suppressions, no `kotlin.Result`, no `Any`, no
   `Dispatchers.*`, no `relaxed=true` mocks, 2-space indent, `.orEmpty()`
   instead of `?: ""`. JUnit5 + kotlin-test stays the runtime-kotlin test
   stack.

## Python Test Disposition

No Python-side test files are deleted in this subtask. The Kotlin CLI bridge
is removed here, but the Python runtime, fallback launcher surface, and
Python-side regression coverage remain available for 3c/fallback validation.

- `tests/test_cli.py` — remains for 3c/fallback. It still covers the legacy
  Python CLI entrypoint and command behavior while `skill_bill/cli.py` exists.
- `tests/test_scaffold.py` — remains for 3c/fallback. It still covers the
  Python scaffold implementation and interactive/payload scaffolding behavior
  while `skill_bill/scaffold` remains in the repo.
- `tests/test_feature_implement_workflow_e2e.py` — remains for 3c/fallback.
  It invokes `python -m skill_bill.cli` for Python workflow E2E coverage.
- `tests/test_feature_verify_workflow_e2e.py` — remains for 3c/fallback. It
  invokes `python -m skill_bill.cli` for Python workflow verification E2E
  coverage.
- `tests/test_migration.py` — remains for 3c/fallback. It imports scaffold
  template helpers used by Python migration coverage.
- `tests/test_remote_telemetry_stats.py` — remains for 3c/fallback. It covers
  the Python CLI telemetry stats path.
- `tests/test_review_metrics.py` — remains for 3c/fallback. It covers Python
  CLI review metrics behavior.
- `tests/test_review_pipeline_smoke.py` — remains for 3c/fallback. It covers
  Python CLI review pipeline smoke behavior.
- `tests/test_validate_agent_configs_e2e.py` — remains for 3c/fallback. It
  imports Python scaffold template helpers for agent config validation E2E
  coverage.
- `tests/test_workflow_stats.py` — remains for 3c/fallback. It covers Python
  CLI workflow stats behavior.

## Validation Notes

- Source audits pass for this subtask: no `runPythonCli`,
  `runPythonScaffoldCli`, `pythonProcess`, `java.nio.file.Files`, or `Files.`
  references remain under `runtime-kotlin/runtime-cli/src/main/kotlin`, and
  `RuntimeArchitectureTest.kt` no longer contains `allowedBridgeFiles` or
  `TODO(3b)`.
- `(cd runtime-kotlin && ./gradlew check)` passes after clearing the known
  stale Spotless configuration cache with
  `rm -rf runtime-kotlin/.gradle/configuration-cache`.
- The current-branch `AuthoringOperations.kt` validation blocker was fixed by
  moving helper groups into smaller sibling files and returning the `upgrade`
  payload from inside the rollback wrapper.

## Non-Goals

- Any further CLI port-or-retire decisions (owned by 3b_1, 3b_2, 3b_3).
- Removing the Python launcher fallback (3c).
- Deleting `skill_bill/cli.py` or `skill_bill/scaffold/...` (3c).

## Dependencies

- 3b_1, 3b_2, AND 3b_3 ALL complete. The bridge cannot die while any caller
  remains; verify by repo grep before starting this subtask.
- 3a complete (the deferred ban set is the predicate this subtask
  promotes).

## Validation Strategy

`bill-quality-check` plus the four-command validation gate. After deletion,
repo grep for `runPythonCli`, `runPythonScaffoldCli`, `pythonProcess`, and
`java.nio.file.Files` (under `runtime-kotlin/runtime-cli/src/main/kotlin/`)
must return zero hits. Confirm by hand once that the new arch ban catches
regressions: temporarily reintroduce a `java.nio.file.Files` import in a
runtime-cli file and verify `RuntimeArchitectureTest` fails before reverting.

## Implementation Notes

- File pointers:
  - Bridge helpers to delete: `ScaffoldCliCommands.kt` lines ≈ 483-661.
    Verify the exact span by grepping for `runPythonCli`,
    `runPythonScaffoldCli`, `pythonProcess`, `normalizeScaffoldCliResult`,
    `normalizeStartedPayload`, `normalizeFinishedPayload`,
    `readScaffoldPayload`, `collectCliResult`, `buildPythonPath`,
    `findRepoRoot`. Each helper that has zero remaining callers post-3b_3
    is fair game for deletion.
  - Imports to audit and likely remove: `java.nio.file.Files` (line 18),
    `java.io.File` (line 17), `java.nio.file.Path` (line 19) — verify each
    is unused before removing.
  - Arch test home:
    `runtime-kotlin/runtime-core/src/test/kotlin/skillbill/architecture/RuntimeArchitectureTest.kt`,
    lines 261-262 for the `// TODO(3b)` marker; locate `allowedBridgeFiles`
    via grep.
  - Python tests to consider: `tests/test_cli.py`, `tests/test_scaffold.py`,
    plus the ~7 remaining files with single `skill_bill.cli` imports
    (locate via `grep -l 'skill_bill\.cli\|skill_bill\.scaffold' tests/`).
- Consolidated disposition recording: prefer either a paragraph in the
  bridge-teardown commit message OR a new `tests/RETIRED.md` (one per
  retired file, one line each). Avoid skip-markers unless there is a
  concrete reason recorded inline.
- Constraints: 2-space indent, no `Any`, no `Dispatchers.*`, no
  `kotlin.Result`, no `relaxed=true` mocks, no new suppressions,
  `.orEmpty()` instead of `?: ""`. JUnit5 + kotlin-test, NOT kotest.

## Handoff Prompt

Run `bill-feature-implement` on
`.feature-specs/SKILL-32-technical-stabilization/spec_subtask_3b_4_bridge-teardown-and-arch-ban.md`.
