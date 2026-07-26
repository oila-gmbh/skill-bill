---
Status: Complete
Issue: SKILL-32
Parent spec: [spec.md](spec.md)
Parent subtask: [spec_subtask_3_contract-tests-and-python-retirement.md](spec_subtask_3_contract-tests-and-python-retirement.md)
---

# Subtask 3a: Kotlin-Native Contract Tests (Foundation)

## Goal

Land first-class Kotlin contract guarantees for the runtime surfaces and CLI/MCP
outputs that are ALREADY 100% native today: golden JSON fixtures for native
CLI commands, MCP contract fixtures for the six listed MCP tools (all expected
native), architecture tests preventing CLI/MCP adapters from depending on
direct DB / FS / HTTP / Python-bridge APIs (scoped to source sets where the
bridge is already gone), and runtime surface contract tests asserting owner
package, supported operations, contract version, and status for the five
active runtimes.

This subtask is the regression net that the deletion-heavy 3b and 3c work
needs in place before they touch any Python-bridge code.

## Scope

In scope:

1. Golden CLI JSON fixtures for the subset of the eight Phase 5 commands that
   are already native (no Python bridge in `ScaffoldCliCommands.kt` or
   `SystemCliCommands.kt`). Expected native today: `version --format json`,
   `import-review --format json`, `triage --format json`,
   `learnings resolve --format json`, `workflow show --format json`,
   `verify-workflow show --format json`, and `doctor --format json` (no
   subject argument). Each command's nativity must be VERIFIED by reading
   `ScaffoldCliCommands.kt` and `SystemCliCommands.kt:42` before adding its
   golden fixture; any command that bridges to Python is deferred to 3b.
2. MCP contract fixtures for the six tools listed in parent AC 2: `doctor`,
   `import_review`, `triage_findings`, `resolve_learnings`, the workflow
   continuation tools, and `new_skill_scaffold`. Each tool's nativity must be
   VERIFIED by reading the MCP tool registration source (likely
   `runtime-kotlin/runtime-mcp/src/main/kotlin/skillbill/mcp/McpToolRegistry.kt`
   or sibling) before adding its fixture. If any of the six are not native
   today, the fact is documented inline and that tool's fixture work shifts
   to 3b.
3. Architecture tests in `RuntimeArchitectureTest.kt` (or sibling) extended
   with bans that are safe to land WITHOUT removing the existing
   `runPythonCli` / `runPythonScaffoldCli` / `pythonProcess` helpers in
   `ScaffoldCliCommands.kt`. Specifically:
   - Repo-wide ban on the Python-bridge marker (the package or symbol that
     makes the bridge identifiable — to be selected during implementation
     after reading the helpers) outside the bridge helpers themselves.
   - Bans on `java.net.http`, `java.sql`, and `java.nio.file.Files` for the
     `runtime-mcp` adapter source set, where the bridge already does not
     exist.
   - The same FS / HTTP / SQL bans for `runtime-cli` are EXPLICITLY DEFERRED
     to 3b because today's `pythonProcess()` helper imports
     `java.nio.file.Files` and the test cannot be green until 3b deletes the
     helper.
4. Runtime surface contract tests asserting owner package, supported
   operations, contract version, and status for each of: `LauncherRuntime`,
   `InstallRuntime`, `ScaffoldRuntime`, `FeatureImplementWorkflowRuntime`,
   `FeatureVerifyWorkflowRuntime`. For `LauncherRuntime`, the test locks
   the CURRENT pre-retirement supportedOperations (still includes the
   `python-fallback` / `mcp-python-fallback` entries if they exist today);
   the post-retirement update is the responsibility of 3c.

Out of scope:

- Porting any Python-backed CLI command to native (3b).
- Deleting `runPythonCli` / `runPythonScaffoldCli` / `pythonProcess` helpers
  from `ScaffoldCliCommands.kt` (3b).
- Banning `java.nio.file.Files`, `java.net.http`, or `java.sql` from the
  `runtime-cli` source set (3b — must follow the bridge deletion).
- Removing `SKILL_BILL_RUNTIME` / `SKILL_BILL_MCP_RUNTIME` resolution (3c).
- Deleting `skill_bill/cli.py` / `skill_bill/mcp_server.py` (3c).
- Updating `LauncherRuntime.supportedOperations` to drop python-fallback (3c).
- Migration / installer doc edits and release notes (3c).
- Restructuring `docs/getting-started.md` (Subtask 4).

## Acceptance Criteria

1. For each native CLI command (verified, expected: `version`,
   `import-review`, `triage`, `learnings resolve`, `workflow show`,
   `verify-workflow show`, `doctor` no-subject), a `--format json` golden
   fixture exists under `runtime-kotlin/runtime-cli/src/test/resources/golden/`
   and is asserted by an integration test that fails on contract drift.
2. For each MCP tool in the parent AC 2 list that is verified native today,
   a contract fixture exists under
   `runtime-kotlin/runtime-mcp/src/test/resources/golden/` and is asserted
   by an integration test that fails on contract drift.
3. `RuntimeArchitectureTest.kt` (or sibling under
   `runtime-kotlin/runtime-core/src/test/kotlin/skillbill/architecture/`)
   asserts the repo-wide Python-bridge ban (outside the bridge helpers) and
   the FS / HTTP / SQL bans for the `runtime-mcp` adapter source set. Each
   ban includes a comment explaining the scope decision and the deferred
   `runtime-cli` ban is documented as a TODO referencing 3b.
4. A new `RuntimeSurfaceContractTest` (or per-surface tests) asserts owner
   package, supported operations, contract version, and status for each of
   `LauncherRuntime`, `InstallRuntime`, `ScaffoldRuntime`,
   `FeatureImplementWorkflowRuntime`, `FeatureVerifyWorkflowRuntime`. The
   `LauncherRuntime` lock matches the current pre-retirement value with an
   inline comment that 3c will tighten it.
5. The four-command validation gate is green from a clean checkout:
   `(cd runtime-kotlin && ./gradlew check)`,
   `.venv/bin/python3 -m unittest discover -s tests`,
   `npx --yes agnix --strict .`,
   `.venv/bin/python3 scripts/validate_agent_configs.py`.
6. Each verification (which CLI commands are native, which MCP tools are
   native) is captured as a short note in the spec or as an inline comment in
   the test that owns the fixture, so 3b can pick up the deferred items
   without re-reading the source.
7. No new Detekt suppressions, no `kotlin.Result`, no `Any`, no
   `Dispatchers.*`, no `relaxed=true` mocks, 2-space indent, `.orEmpty()`
   instead of `?: ""`. JUnit5 + kotlin-test stays the runtime-kotlin test
   stack.

## Non-Goals

- Any Python deletion or Python-bridge removal.
- Any port of CLI commands that bridge to Python.
- Any change to `LauncherRuntime` supported operations or contract version.
- Any docs work outside inline test comments and the inline note in this spec
  recording the verified native/non-native split.

## Dependencies

- Subtask 1 must be complete (runtime gate green, ownership table published).
- Subtask 2a, 2b, 2c must be complete (packaged runtime is the default and
  strict MCP schemas are in place — both required so the fixtures lock the
  intended post-Subtask-2 behavior).

## Validation Strategy

`bill-quality-check` plus the four-command validation gate. The new golden
fixtures must each be asserted by a test; each surface contract test must be
asserted by a test; the new arch ban set must be asserted by
`RuntimeArchitectureTest.kt`. Failure modes to confirm by hand at least once:
edit a golden fixture and confirm the test fails; add a stray
`java.nio.file.Files` import in a `runtime-mcp` adapter file and confirm the
arch test fails.

## Implementation Notes

### Native/non-native verification note

Verified on 2026-04-30 for Subtask 3a:

- Native CLI JSON fixture surfaces: `version --format json`, `doctor --format json`
  with no subject, `import-review --format json`, `triage --format json`,
  `learnings resolve --format json`, `workflow show --format json`, and
  `verify-workflow show --format json`.
- Deferred Python-backed CLI surfaces for 3b: `doctor <subject>`, scaffold and
  authoring commands routed through `runPythonCli` / `runPythonScaffoldCli`,
  and install subcommands routed through `runPythonCli` in
  `ScaffoldCliCommands.kt`.
- Native MCP fixture surfaces: `doctor`, `import_review`, `triage_findings`,
  `resolve_learnings`, `new_skill_scaffold`, and the feature implement/verify
  workflow tools registered in `McpToolDispatcher`.
- `new_skill_scaffold` caveat: the MCP handler is native Kotlin, but
  `McpScaffoldRuntime` still imports `java.nio.file.Files` for repo-root
  discovery. The 3a architecture ban therefore excludes only that file from the
  runtime-mcp Files ban while banning HTTP/SQL across the MCP adapter.

- File pointers:
  - Native CLI verification: read
    `runtime-kotlin/runtime-cli/src/main/kotlin/skillbill/cli/ScaffoldCliCommands.kt`
    and
    `runtime-kotlin/runtime-cli/src/main/kotlin/skillbill/cli/SystemCliCommands.kt`
    (specifically line 42 for `doctor <subject>`) before adding any fixture.
  - MCP tool verification: read
    `runtime-kotlin/runtime-mcp/src/main/kotlin/skillbill/mcp/McpToolRegistry.kt`
    (or wherever registrations live — grep for `registerTool` /
    `ToolRegistration`) before adding any contract fixture.
  - Existing golden fixture homes to extend:
    `runtime-kotlin/runtime-cli/src/test/resources/golden/cli-import-review.json`
    and
    `runtime-kotlin/runtime-mcp/src/test/resources/golden/mcp-resolve-learnings.json`.
  - Arch test home:
    `runtime-kotlin/runtime-core/src/test/kotlin/skillbill/architecture/RuntimeArchitectureTest.kt`.
  - Runtime surface contract API: `RuntimeSurfaceContract` is already used by
    the five runtimes named in AC 4. Add per-surface assertion tests in
    `runtime-kotlin/runtime-core/src/test/kotlin/skillbill/contract/` (or
    sibling — follow the existing layout).
- Surface lock convention: each surface contract test should hard-code the
  expected owner, supportedOperations set, contract version, and status as
  literal values, so any drift in source is a test failure. For
  `LauncherRuntime`, leave a `// TODO(3c)` comment next to the
  python-fallback entries so 3c can find and remove them.
- Arch ban scoping: the existing `RuntimeArchitectureTest.kt` should already
  have a pattern for restricting checks to a source set. Follow that pattern
  for `runtime-mcp`-only bans. Document the scoping decision in a one-liner
  comment so 3b can promote the bans repo-wide after deleting
  `pythonProcess()`.
- Constraints: 2-space indent, no `Any`, no `Dispatchers.*`, no
  `kotlin.Result`, no `relaxed=true` mocks, no new suppressions,
  `.orEmpty()` instead of `?: ""`. AGENTS.md mandates the four-command gate.
  JUnit5 + kotlin-test, NOT kotest, inside `runtime-kotlin`.

## Handoff Prompt

Run `bill-feature-implement` on
`.feature-specs/SKILL-32-technical-stabilization/spec_subtask_3a_kotlin-native-contract-tests.md`.
