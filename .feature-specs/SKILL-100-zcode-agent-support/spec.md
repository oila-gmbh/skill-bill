# SKILL-100 — ZCode Full Runtime Agent Support

## Outcome

`zcode` becomes a full runtime-capable agent in skill-bill, wired into every
touch point that `claude`/`codex`/`junie` already use: install/skills paths,
native-agent rendering and linking, MCP registration, the headless
`AgentRunCommandBuilder` runtime driver, CLI commands, `install.sh`/
`uninstall.sh`, the install-plan schema, and all parity/contract tests. It
must **not** be treated like opencode's prose-only refusal path — ZCode has a
non-TTY headless mode (`zcode --prompt <text> --json --cwd <dir> --mode yolo
--no-color`) and no short foreground timeout, satisfying both criteria
SKILL-95 required for runtime viability.

## Background

A prior investigation (`.feature-specs/zcode-agent-support-investigation.md`,
verified against a live ZCode 3.2.3 install and the codebase at commit
`ce422474`) confirmed ZCode is runtime-capable and produced a complete,
ordered touch-surface edit list. skill-bill has no single agent registry:
agent identity is hardcoded across 6 duplicated enums, ~8 `when`/list
branches, a bash array, a YAML enum, and parity tests. Adding a new runtime
agent means touching all of them consistently, in dependency order (an enum
entry must exist before anything that switches on it can reference it).

Per repo policy, only Claude and Codex are verified end-to-end as runtime
agents today; agent-support claims are tiered and an unverified agent is
never advertised as equal to a verified one. This spec wires zcode in fully
and gets its parity tests passing — it does not claim zcode is verified
end-to-end, and no code comment, CLI help text, or doc string produced here
may say otherwise.

Two facts genuinely could not be confirmed live during investigation (no
configured Z.AI model provider was available): the exact `--json` output
envelope shape, and whether `~/.zcode/cli/config.json` accepts nested
`mcp.servers` vs. top-level `mcpServers`. Both are implemented against the
best-documented/best-guess shape per the investigation doc's explicit
recommendation, with a clear non-blocking follow-up note left for a human to
confirm against a real run. See Non-Goals.

## Scope

The full 30-item touch list (grouped A–I in the investigation doc) breaks
into three dependency-ordered pieces:

1. **Foundation** — 6 duplicated agent-identity enums (`InstallAgent`,
   `NativeAgentProviderId`, `NativeAgentProvider`, `NativeAgentLinkProvider`,
   `FirstRunSetupAgent`, `AgentSymlinkProvider`/`DesktopAgentSymlinkProvider`)
   plus the install-path/detection layer (`SUPPORTED_AGENTS`, `agentPaths()`,
   `agentIsPresent()`, `agentDirectory()`). Every later piece switches on
   symbols this introduces.
2. **Native-agent linking, MCP registration, runtime command builder** —
   three independently pluggable capability layers that all depend only on
   the foundation: `linkZcodeAgents`/`unlinkZcodeAgents` +
   `nativeAgentInstallers`, a `McpZcodeConfig` writer wired into
   `McpRegistrationOperations`, and a `ZcodeAgentRunCommandBuilder` wired
   into `headlessAgentRunAdapters`.
3. **CLI commands, shell scripts, schema** — the CLI surface
   (`InstallZcodeAgentsPathCommand`/`InstallLinkZcodeAgentsCommand`/
   `InstallUnlinkZcodeAgentsCommand`) that calls into piece 2's link/unlink
   functions, `install.sh`/`uninstall.sh`, the install-plan schema's
   `agentId` enum, and the shell-delegation test whose literal command lists
   must reference the exact subcommand name this piece introduces. Ends with
   a full `./gradlew check` gate.

Do not add zcode to `RUNTIME_REFUSED_AGENTS` at any point — that pattern is
opencode-specific and intentionally not generalized.

## Acceptance Criteria

1. `InstallAgent` enum contains `ZCODE("zcode")` and `ZCODE` is **not** added
   to `RUNTIME_REFUSED_AGENTS`.
2. `NativeAgentProviderId`, `NativeAgentProvider` (with a
   `Zcode("zcode-agents","md")` entry using `renderFrontmatterAgent(mode =
   null)` and `homeAgentDirs = listOf(home.resolve(".zcode/agents"))`),
   `NativeAgentLinkProvider`, `FirstRunSetupAgent` (`ZCODE("zcode","ZCode")`),
   `AgentSymlinkProvider`, and `DesktopAgentSymlinkProvider` all include a
   `ZCODE` entry.
3. `"zcode"` is present in `SUPPORTED_AGENTS`, `agentPaths()` (mapping to
   `~/.zcode/skills`), `agentIsPresent()` (detecting `~/.zcode`), and
   `agentDirectory()` (via a new `InstallOperations.zcodeAgentsPath` helper
   resolving `~/.zcode/agents`, modeled on `junieAgentsPath`).
4. `linkZcodeAgents`/`unlinkZcodeAgents` functions exist, are wired into both
   `FileSystemInstallAdapters` provider `when`-branches, and a
   `NativeAgentInstaller` entry for `ZCODE` exists in the
   `nativeAgentInstallers` list.
5. A `McpZcodeConfig` writer class exists that reads/writes
   `~/.zcode/cli/config.json` under the nested `mcp.servers` key (JSON, not
   TOML), and `McpRegistrationOperations.register`/`.unregister`/
   `.configPathFor` all handle `InstallAgent.ZCODE`.
6. A `ZcodeAgentRunCommandBuilder` exists (`usePtyStdio = false`,
   `idlePolicy = DB_PROGRESS_ONLY`) building `zcode --prompt <prompt> --json
   --cwd <dir> --mode yolo --no-color`, and it is registered in
   `headlessAgentRunAdapters` so it survives the `RUNTIME_REFUSED_AGENTS`
   filter.
7. New CLI commands (`InstallZcodeAgentsPathCommand`,
   `InstallLinkZcodeAgentsCommand`, `InstallUnlinkZcodeAgentsCommand`) exist,
   modeled on the junie equivalents, and are registered as subcommands in
   `ScaffoldCliCommands`.
8. `install.sh`'s `SUPPORTED_AGENTS` array and both help strings include
   zcode; `uninstall.sh` has a `~/.zcode` removal block and includes zcode in
   its agent loop.
9. `orchestration/contracts/install-plan-schema.yaml`'s `agentId` enum
   includes `zcode`.
10. All parity/contract tests pass with zcode included:
    `requireSupportedAgentContract()` (auto-satisfied),
    `FirstRunSetupModelsTest`, `InstallPlanContractCoverageTest` and
    `InstallPlanSchemaValidatesExistingFixturesTest` (including their literal
    per-agent fixture-directory lists), `InstallerShellDelegationTest`'s
    three literal `unlink-*-agents` command lists, `HeadlessAgentRunAdapterTest`
    extended with zcode assertions, and `McpRegistrationOperationsTest`'s
    "non-claude agents stay single-target" test extended with a zcode entry.
11. `(cd runtime-kotlin && ./gradlew check)` passes with zero suppressions
    after all subtasks land.

## Non-Goals

- Live-verifying the exact `--json` output envelope shape against a running,
  model-configured ZCode install — infeasible without a configured Z.AI
  provider. Implement the parser against the best-documented/best-guess
  envelope shape (mirroring Claude/Codex/Junie's parsing conventions), and
  leave a clear non-blocking follow-up note in code for a human to confirm
  against a real run before this is relied on in production. Do not claim it
  is confirmed.
- Live-verifying `--attach` vs. argv `--prompt` behavior for large phase
  prompts — implement via `--prompt` argv only (documented safe well under
  Linux's 128 KiB `MAX_ARG_STRLEN`); no `--attach` handling in this spec.
- Live-verifying ZCode's user-scope subagent authoring format by dropping
  test files into `~/.zcode/agents/` — implement `NativeAgentProvider.Zcode`
  using the same `renderFrontmatterAgent(mode = null)` pattern as
  Claude/Junie (the best-evidenced format) without live confirmation.
- Live-verifying whether `~/.zcode/cli/config.json` accepts `mcp.servers` vs.
  `mcpServers` — implement per the official ZCode configuration guide (nested
  `mcp.servers`), matching the investigation doc's explicit recommendation.
- Determining whether bare workspace `.mcp.json` is read by zcode at
  workspace scope — does not affect the user-scope install target and is out
  of scope.
- Adding a `ZCODE` entry to `INVOKING_AGENT_CONTEXT_SIGNALS` unless the
  subtask-1 implementer confirms it's consistent with existing precedent
  (`JUNIE` currently has no entry there either) — do not blindly add a case
  that breaks the existing pattern; leave a one-line rationale either way.
- Advertising or documenting zcode as "verified end-to-end" in README or
  marketing copy — out of scope; only the code and tests are wired in this
  spec.

## Constraints

- zcode must **not** be added to `RUNTIME_REFUSED_AGENTS` or otherwise routed
  through opencode's prose-only refusal machinery (`isRuntimeRefusedAgent`,
  `OPENCODE_RUNTIME_REFUSAL_MESSAGE`) — that pattern is opencode-specific and
  intentionally not generalized.
- The `zcode` binary must be resolved via `PATH` (assume `zcode` is on
  `PATH`), mirroring claude/codex/junie builders — never hardcode the
  AppImage mount path (it changes every launch).
- MCP config write target is `~/.zcode/cli/config.json` under nested
  `mcp.servers`, JSON format — distinct from codex's TOML and from the
  `.agents/mcp.json` fallback's top-level `mcpServers` shape.
- No code comment, CLI help text, or doc string may claim zcode is "verified
  e2e" — passing implementation plus the CLI's own parity tests is the bar,
  not a live e2e session.
- No unnecessary comments — new classes/functions should match the terse
  style of sibling builders. Prefer Junie as the template throughout
  (simplest, most recently added, no special-case stdin/multi-profile logic
  Claude's builder has).
- `InstallApplyNativeAgents.kt` lives at
  `runtime-infra-fs/src/main/kotlin/skillbill/install/apply/InstallApplyNativeAgents.kt`
  (package `skillbill.install.apply`) — the investigation doc's stated
  package (`skillbill.apply`) is wrong; use the corrected path.

## Dependency Notes

Subtask 1 (foundation) has no dependencies and must land first — every later
subtask's `when`-branches and registries reference the
`InstallAgent.ZCODE`/`NativeAgentProviderId.ZCODE`/
`NativeAgentLinkProvider.ZCODE`/`FirstRunSetupAgent.ZCODE` symbols it
introduces. Subtask 2 (native-agent linking, MCP registration, runtime
command builder) depends only on subtask 1. Subtask 3 (CLI commands, shell
scripts, schema) depends on both 1 and 2, since the CLI commands call
subtask 2's link/unlink functions and the shell scripts/tests must reference
the exact subcommand name the CLI introduces.

## Validation Strategy

- Each subtask runs its own targeted Gradle test slice (see each
  `spec_subtask_*.md`).
- Subtask 3 runs the full `(cd runtime-kotlin && ./gradlew check)` gate as
  the final integration checkpoint, confirming zero suppressions and no
  drift anywhere in the 30-item touch surface.
- `bash -n install.sh && bash -n uninstall.sh` as a shell syntax sanity
  check.

## Next Path

This feature is decomposed into 3 dependency-ordered subtasks (see
`decomposition-manifest.yaml`): foundation (6 enums + install-path/detection
wiring), native-agent linking + MCP registration + the headless runtime
command builder, and CLI commands + shell scripts + schema (with the final
`./gradlew check` gate).

```bash
skill-bill goal SKILL-100
```
