# SKILL-100 · Subtask 2 — Native-agent linking, MCP registration, runtime command builder

Parent overview: [spec.md](./spec.md)

Wires zcode into three pluggable capability layers that each depend only on
subtask 1's enums and are otherwise independent of each other: native-agent
markdown linking, MCP registration, and the headless runtime command
builder. Treat these as one cohesive pass — all three are "plug a new agent
into an existing pluggable system" work inside `runtime-infra-fs`.

Branch: `feat/SKILL-100-zcode-agent-support` (same-branch model, one commit
for this subtask).

## Dependencies

- depends_on: `[1]`
- dependency_reason: This subtask's new functions/classes are wired into
  `when`-branches keyed on `NativeAgentLinkProvider.ZCODE`,
  `NativeAgentProviderId.ZCODE`, and `InstallAgent.ZCODE`, all introduced by
  subtask 1. It cannot compile without subtask 1 landed first.

## Scope (owns)

### 2a. Native-agent linking

- `linkZcodeAgents`/`unlinkZcodeAgents` functions in
  `runtime-kotlin/runtime-infra-fs/src/main/kotlin/skillbill/install/nativeagent/InstallNativeAgentOperations.kt`,
  modeled on `linkJunieAgents` (near lines 96-107) and `unlinkJunieAgents`
  (near lines 109-119).
- `FileSystemInstallAdapters.kt`'s link `when`-branch (near lines 222-227)
  and unlink `when`-branch (near lines 241-246) each add a
  `NativeAgentLinkProvider.ZCODE` case calling the new functions.
- The `nativeAgentInstallers` list in
  `runtime-kotlin/runtime-infra-fs/src/main/kotlin/skillbill/install/apply/InstallApplyNativeAgents.kt`
  (package `skillbill.install.apply` — not `skillbill.apply`; near lines
  199-224) includes a `NativeAgentInstaller(agent=InstallAgent.ZCODE,
  provider=NativeAgentProviderId.ZCODE, link=::linkZcodeAgents,
  unlink=::unlinkZcodeAgents)` entry, modeled on the adjacent Junie block.

### 2b. MCP registration

- A `McpZcodeConfig` class in
  `runtime-kotlin/runtime-infra-fs/src/main/kotlin/skillbill/launcher/mcp/`
  (modeled on `McpJsonConfig.kt`) that reads/writes
  `~/.zcode/cli/config.json` under the **nested** `"mcp"."servers"` key (not
  top-level `mcpServers`), preserving any unrelated existing JSON content in
  that file.
- `McpRegistrationOperations.register` (near lines 22-30), `.unregister`
  (near lines 42-50), and `.configPathFor` (near lines 53-59) each add an
  `InstallAgent.ZCODE` case; `configPathFor` resolves to
  `home.resolve(".zcode/cli/config.json")`.

### 2c. Runtime command builder

- A `ZcodeAgentRunCommandBuilder` class in
  `runtime-kotlin/runtime-infra-fs/src/main/kotlin/skillbill/launcher/agentrun/AgentRunCommandBuilders.kt`
  building the command `zcode --prompt <prompt> --json --cwd <dir> --mode
  yolo --no-color`, modeled on `JunieAgentRunCommandBuilder` (near lines
  95-117 — not Claude's stdin-special-cased builder), relying on the
  `AgentRunCommand` defaults `usePtyStdio = false` and `idlePolicy =
  AgentRunIdlePolicy.DB_PROGRESS_ONLY` rather than overriding them.
- `headlessAgentRunAdapters()` in
  `runtime-kotlin/runtime-infra-fs/src/main/kotlin/skillbill/launcher/agentrun/AgentRunAdapters.kt`
  (near lines 74-80) includes `ZcodeAgentRunCommandBuilder()` in its
  `listOf(...)`; it must survive the `.filterNot { RUNTIME_REFUSED_AGENTS.contains(...)
  }` step since `ZCODE` is not in that set.
- The `--json` output parsing used by `ZcodeAgentRunCommandBuilder` (or its
  output adapter) is implemented against the best-documented/best-guess
  envelope shape and includes a clear, non-blocking follow-up note for
  future live confirmation against a real ZCode session — it must not claim
  to be confirmed.

## Reusable patterns / pitfalls

- Prefer Junie's builder/linking pattern as the template throughout — it has
  no special-case stdin/multi-profile logic that Claude's builder carries.
- The zcode binary is resolved via `PATH` (`zcode`), never a hardcoded
  AppImage mount path — the mount path changes every launch.
- Do not add `ZCODE` to `RUNTIME_REFUSED_AGENTS` or route it through
  opencode's refusal machinery (`isRuntimeRefusedAgent`,
  `OPENCODE_RUNTIME_REFUSAL_MESSAGE`) — that pattern is opencode-specific.
- No unnecessary comments.

## Acceptance Criteria

1. `linkZcodeAgents`/`unlinkZcodeAgents` functions exist in
   `InstallNativeAgentOperations.kt`, modeled on the Junie equivalents.
2. `FileSystemInstallAdapters.kt`'s link and unlink `when`-branches each add
   a `NativeAgentLinkProvider.ZCODE` case calling the new functions.
3. `nativeAgentInstallers` includes a `NativeAgentInstaller` entry for
   `InstallAgent.ZCODE`/`NativeAgentProviderId.ZCODE` wired to
   `linkZcodeAgents`/`unlinkZcodeAgents`.
4. A `McpZcodeConfig` class exists that reads/writes
   `~/.zcode/cli/config.json` under the nested `mcp.servers` key, preserving
   unrelated existing JSON content.
5. `McpRegistrationOperations.register`, `.unregister`, and `.configPathFor`
   each add an `InstallAgent.ZCODE` case; `configPathFor` resolves to
   `home.resolve(".zcode/cli/config.json")`.
6. A `ZcodeAgentRunCommandBuilder` class exists building the command `zcode
   --prompt <prompt> --json --cwd <dir> --mode yolo --no-color`, using the
   `usePtyStdio = false` / `idlePolicy = DB_PROGRESS_ONLY` defaults.
7. `headlessAgentRunAdapters()` includes `ZcodeAgentRunCommandBuilder()` and
   it is not filtered out by `RUNTIME_REFUSED_AGENTS`.
8. `HeadlessAgentRunAdapterTest` (inside `AgentRunLauncherTest.kt`) is
   extended so its adapter-presence assertion and per-agent command-shape
   checks include `InstallAgent.ZCODE` alongside `CLAUDE`/`CODEX`/`JUNIE`,
   and the test passes.
9. `McpRegistrationOperationsTest`'s "non-claude agents stay single-target"
   test is extended with a `"zcode" -> home.resolve(".zcode/cli/config.json")`
   entry in its expected-paths map, and passes.
10. The `--json` envelope parser carries a clear, non-blocking follow-up note
    that its shape is unconfirmed against a live ZCode session — it does not
    claim to be confirmed.

## Non-goals

- Enum additions — already done in subtask 1.
- CLI command surface exposing link/unlink/path — subtask 3.
- `install.sh`/`uninstall.sh`/schema changes — subtask 3.
- Live verification of the `--json` envelope, `--attach` behavior, or the
  `mcp.servers`-vs-`mcpServers` key naming — deferred per the parent spec's
  non-goals.

## Validation strategy

```bash
cd runtime-kotlin && ./gradlew :runtime-infra-fs:test \
  --tests "*HeadlessAgentRunAdapterTest*" \
  --tests "*McpRegistrationOperationsTest*"
cd runtime-kotlin && ./gradlew :runtime-infra-fs:test
```

## Handoff prompt

Run bill-feature-task on
`.feature-specs/SKILL-100-zcode-agent-support/spec_subtask_2_native-agent-mcp-runtime.md`.
