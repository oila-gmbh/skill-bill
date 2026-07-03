# SKILL-100 · Subtask 1 — Foundation: enums & install-path detection

Parent overview: [spec.md](./spec.md)

This is the **first** subtask and has no upstream dependency. It adds a
`ZCODE`/`Zcode` entry to all 6 duplicated agent-identity enums and wires
`"zcode"` into the install-path/detection layer. Every later subtask's
`when`-branches and registries reference the symbols this subtask
introduces, so it must land first.

Branch: `feat/SKILL-100-zcode-agent-support` (same-branch model, one commit
for this subtask).

## Dependencies

- depends_on: `[]` (runs first)
- dependency_reason: No upstream work exists yet; this subtask creates the
  foundational enum entries and install-path wiring everything else
  references by symbol.

## Scope (owns)

### 1a. The six duplicated agent-identity enums

- `InstallAgent` — `runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/install/model/InstallModels.kt`
  (enum entries near line 13). Add `ZCODE("zcode")`. Do **not** add it to
  `RUNTIME_REFUSED_AGENTS` (near line 37) — zcode is runtime-capable.
- `NativeAgentProviderId` — same file, near line 386. Add `ZCODE`.
- `NativeAgentProvider` — `runtime-kotlin/runtime-infra-fs/src/main/kotlin/skillbill/nativeagent/rendering/NativeAgentRendering.kt`
  (enum entries near line 13). Add a `Zcode("zcode-agents", "md")` entry,
  modeled directly on the adjacent `Junie` entry, with
  `render = renderFrontmatterAgent(mode = null)` and `homeAgentDirs`
  returning `listOf(home.resolve(".zcode/agents"))`.
- `NativeAgentLinkProvider` — `runtime-kotlin/runtime-ports/src/main/kotlin/skillbill/ports/install/model/InstallPortModels.kt`
  (near line 11). Add `ZCODE`.
- `FirstRunSetupAgent` — `runtime-kotlin/runtime-desktop/core/domain/src/commonMain/kotlin/skillbill/desktop/core/domain/model/FirstRunSetupModels.kt`
  (entries near line 17). Add `ZCODE("zcode", "ZCode")`; confirm its
  `supportedIds` companion transitively includes `"zcode"`.
- `AgentSymlinkProvider` — `runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/domain/skillremove/model/SkillRemovalPreview.kt`
  (near line 81). Add `ZCODE`.
- `DesktopAgentSymlinkProvider` — `runtime-kotlin/runtime-desktop/core/domain/src/commonMain/kotlin/skillbill/desktop/core/domain/model/SkillRemovalModels.kt`
  (near line 83). Add `ZCODE`.

Re-locate every site above by symbol/pattern search before editing — line
numbers are approximate and may have drifted since the source investigation.

### 1b. Install-path & detection layer

All in `runtime-kotlin/runtime-infra-fs/src/main/kotlin/skillbill/install/plan/InstallPrimitives.kt`
unless noted:

- `SUPPORTED_AGENTS` list (near line 15) — add `"zcode"`.
- `agentPaths(...)` map (near lines 21-30) — add `"zcode" ->
  resolvedHome.resolve(".zcode/skills")`.
- `agentIsPresent(...)` `when (agent)` block (near lines 153-160) — add a
  `"zcode" -> listOf(home.resolve(".zcode"))` case.
- `agentDirectory(...)` `when` block in
  `runtime-kotlin/runtime-infra-fs/src/main/kotlin/skillbill/infrastructure/fs/FileSystemInstallAdapters.kt`
  (near lines 193-199) — add a `"zcode" ->
  InstallOperations.zcodeAgentsPath(request.home)` case.
- New `zcodeAgentsPath(home)` helper in
  `runtime-kotlin/runtime-infra-fs/src/main/kotlin/skillbill/install/runtime/InstallOperations.kt`
  (modeled on `junieAgentsPath`, near lines 53-56), returning
  `home.resolve(".zcode/agents")`.

### 1c. Decide on `INVOKING_AGENT_CONTEXT_SIGNALS`

`INVOKING_AGENT_CONTEXT_SIGNALS` (same `InstallModels.kt`, near lines 70-74)
maps some agents to an env-var detection marker
(`ZCODE_APP_VERSION` would be the candidate for zcode). `JUNIE` currently has
**no** entry there. Explicitly decide whether to add a `ZCODE` marker or
follow the `JUNIE` precedent and omit it, and leave a one-line rationale for
the choice in the diff or commit message — do not add it silently without
considering the precedent.

## Reusable patterns / pitfalls

- Model every new enum entry directly on the adjacent `Junie`/`JUNIE` case —
  it is the simplest, most recently added agent with no special-case
  stdin/multi-profile logic.
- `requireSupportedAgentContract()` (`InstallPlanBuilder.kt`, near lines
  50-54) ties `SUPPORTED_AGENTS` to `InstallAgent.supportedIds` — it is
  auto-satisfied only if both are updated together in this subtask.
- No unnecessary comments — match the terse style of sibling enum entries
  and functions.

## Acceptance Criteria

1. `InstallAgent` enum contains `ZCODE("zcode")`; `RUNTIME_REFUSED_AGENTS` is
   **not** modified to include it.
2. `NativeAgentProviderId` enum contains `ZCODE`.
3. `NativeAgentProvider` enum contains a `Zcode("zcode-agents", "md")` entry
   with `render = renderFrontmatterAgent(mode = null)` and `homeAgentDirs`
   returning `listOf(home.resolve(".zcode/agents"))`, modeled on the
   adjacent `Junie` entry.
4. `NativeAgentLinkProvider` enum contains `ZCODE`.
5. `FirstRunSetupAgent` enum contains `ZCODE("zcode", "ZCode")`, and its
   `supportedIds` companion transitively includes `"zcode"`.
6. `AgentSymlinkProvider` and `DesktopAgentSymlinkProvider` both contain
   `ZCODE`.
7. `SUPPORTED_AGENTS` includes `"zcode"`.
8. `agentPaths()` maps `"zcode"` to `resolvedHome.resolve(".zcode/skills")`.
9. `agentIsPresent()`'s `when(agent)` block adds a `"zcode" ->
   listOf(home.resolve(".zcode"))` case.
10. `agentDirectory()`'s `when` block adds a `"zcode" ->
    InstallOperations.zcodeAgentsPath(request.home)` case, backed by a new
    `zcodeAgentsPath(home)` helper returning `home.resolve(".zcode/agents")`.
11. `requireSupportedAgentContract()` continues to pass.
12. `FirstRunSetupModelsTest.kt` asserts `"zcode"` is present in
    `FirstRunSetupAgent.supportedIds`, and passes.
13. `InstallPlanContractCoverageTest.kt` and
    `InstallPlanSchemaValidatesExistingFixturesTest.kt` each add a `.zcode`
    fixture-directory entry alongside `.copilot`/`.claude`/`.codex`/
    `.config/opencode`/`.junie` in their literal fixture lists, and both
    tests pass.
14. The implementer has explicitly decided whether to add a `ZCODE` marker
    to `INVOKING_AGENT_CONTEXT_SIGNALS`, with a one-line rationale recorded
    in the diff or commit message.

## Non-goals

- Native-agent linking functions (`linkZcodeAgents`/`unlinkZcodeAgents`) and
  the `FileSystemInstallAdapters` provider `when`-branches that call them —
  subtask 2.
- MCP registration (`McpZcodeConfig`, `McpRegistrationOperations`) —
  subtask 2.
- The runtime `AgentRunCommandBuilder` — subtask 2.
- CLI commands, shell scripts, and the install-plan-schema.yaml enum —
  subtask 3.

## Validation strategy

```bash
cd runtime-kotlin && ./gradlew :runtime-domain:test :runtime-ports:test \
  :runtime-infra-fs:test :runtime-desktop:core:domain:jvmTest \
  --tests "*FirstRunSetupModelsTest*" \
  --tests "*InstallPlanContractCoverageTest*" \
  --tests "*InstallPlanSchemaValidatesExistingFixturesTest*"
```

If any module/task name doesn't resolve, fall back to
`(cd runtime-kotlin && ./gradlew check)` scoped to this subtask's affected
modules. Also run `(cd runtime-kotlin && ./gradlew :runtime-infra-fs:test)`
to confirm `requireSupportedAgentContract()` still passes.

## Handoff prompt

Run bill-feature-task on
`.feature-specs/SKILL-100-zcode-agent-support/spec_subtask_1_foundation.md`.
