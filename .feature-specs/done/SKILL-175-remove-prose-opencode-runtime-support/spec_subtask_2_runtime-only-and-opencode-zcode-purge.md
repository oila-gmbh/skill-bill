# SKILL-175 Subtask 2 - Runtime-only callers and full OpenCode/zcode purge

Parent spec: [.feature-specs/SKILL-175-remove-prose-opencode-runtime-support/spec.md](spec.md)
Issue key: SKILL-175

## Scope

1. Make every user-facing feature entry **runtime-only** (no prose engine
   selector).
2. **Fully remove** OpenCode and zcode from the live product — install, detect,
   MCP, native-agent, CLI, schemas, skills, docs, tests. No refuse-tier leftover
   and no prose shim.

### Detailed surfaces — feature callers (runtime-only)

- `skills/bill-feature-task/content.md` — remove `mode:runtime|prose` router and
  any opencode/zcode branches; runtime is the only engine.
- `skills/bill-feature-task-runtime/content.md` — remove prose redirect and
  opencode/zcode special cases.
- `skills/bill-feature-goal/content.md` — remove `mode:prose`, `goal_prose_*`,
  subtask-runner spawn, and opencode/zcode branches; keep `skill-bill goal`
  runtime only.
- `skills/bill-feature/content.md` — drop dual-engine composition language if
  present.
- `skills/bill-feature-verify/content.md` — remove opencode-safe / prose
  contrast framing.
- `skills/bill-code-review/content.md` / `bill-code-review-parallel/content.md`
  — drop opencode/zcode from capability tables and supported-agent prompts.
- `AGENTS.md` Product Intent, `README.md` — runtime-only engine; no
  OpenCode/zcode as supported agents.
- `docs/internal-skills-architecture.md`, `docs/getting-started*.md` — remove
  dual-engine and OpenCode/zcode install/runtime claims.

### Detailed surfaces — OpenCode/zcode purge (no traces)

**Domain / ports**

- `InstallModels.kt`: `InstallAgent.OPENCODE` / `ZCODE`;
  `RUNTIME_REFUSED_AGENTS`; `RUNTIME_REFUSED_AGENT_MESSAGE`;
  `isRuntimeRefusedAgent`; `isOpencodeAgent` (and zcode equivalents);
  `INVOKING_AGENT_CONTEXT_SIGNALS` for `OPENCODE*` / `ZCODE_*`;
  `NativeAgentProviderId.OPENCODE` / `ZCODE`
- `InstallPortModels` / `SkillRemovalPreview` link/symlink provider enums
- Any `MODEL_DIRECTIVE` / launcher allow-lists still naming them

**Install / FS / MCP registration**

- `InstallPrimitives.kt` / `InstallOperations.kt` / apply / native-agent link
  ops: opencode/zcode skills paths, agents paths, detect, link/unlink
- `McpOpenCodeConfig.kt`, `McpZcodeConfig.kt`, cases in
  `McpRegistrationOperations.kt`
- `NativeAgentRendering.kt` Opencode/Zcode providers + snapshot tests
- `FileSystemAgentRunLauncher` / `AgentRunAdapters` refusal backstops and dead
  zcode `normalizeStdout` remnant; drop refuse-set wiring once enums are gone
- `config.yaml` `opencode.skills_dir` (and zcode if present)

**Shell / scripts**

- `install.sh` / `uninstall.sh`: remove from `SUPPORTED_AGENTS` and all
  opencode/zcode skill/agent install/uninstall helpers (no “supported agent”
  leftover; do not keep product code paths named for these agents)
- `scripts/install_smoke_test.sh`, `scripts/agent_install_smoke_test.sh`, and
  related smokes: drop agents from matrices

**CLI**

- `InstallCliCommands` opencode/zcode path and link/unlink commands
- `RuntimeAgentRefusal` and tests that only exist to assert opencode/zcode
  refuse-to-prose (delete or replace with “unknown/unsupported agent id”
  behavior consistent with remaining agents — **not** a permanent
  OpenCode-specific refuse tier)
- Parallel-review `--agent` allow-lists

**Contracts / docs / delegated-review**

- `install-plan-schema.yaml`, `native-agent-link-inventory-schema.yaml` — remove
  enum values; bump/parity as required
- `docs/delegated-review/provider-capability-matrix.md` and related — **remove
  rows**, do not leave “unsupported” OpenCode/Zcode product rows
- `docs/capabilities.md`, `docs/skill-source-generation.md`,
  `orchestration/shell-content-contract/SCAFFOLD_PAYLOAD.md`, native-agent
  README mentions
- Supersede `agent/decisions.md` OpenCode-prose stance (if not fully covered in
  subtask 1)

**Tests / fixtures (representative)**

- `CliFeatureTaskRuntimeZcodeRefusalTest`, `CliGoalOpencodeRefusalTest`,
  `CliGoalZcodeRefusalTest`, opencode lanes in parallel-review tests
- `InstallPlan*Test`, `InstallApplyTest`, `InstallNativeAgentLinkApplyTest`,
  `McpRegistrationOperationsTest`, `NativeAgentRenderingTest` /
  `NativeAgentRenderSnapshotTest`, `InvokingAgentContextResolverTest`,
  `ModelDirectiveCapabilityTest`, provider COUNT assertions
- Fixtures that create `.config/opencode` / `.zcode` homes solely for these
  agents

## Acceptance Criteria

1. Feature entry skills no longer accept, document, or branch on `mode:prose` /
   `mode:runtime` as an engine selector; runtime is implied.
2. No governed skill instructs the user or agent to run
   `bill-feature-task-prose`, `bill-feature-goal mode:prose`, or
   `bill-feature-task-subtask-runner` as a supported path.
3. `InstallAgent` / native-agent provider / symlink provider enums no longer
   include `opencode` or `zcode`.
4. Install/uninstall/MCP-register/native-agent-link/detect/CLI path helpers for
   those agents are deleted; `install.sh` agent lists no longer name them.
5. `RUNTIME_REFUSED_AGENTS` / prose-redirect refusal machinery for OpenCode/zcode
   is removed (not rewritten into a kept refuse tier).
6. Skills, README, getting-started, capabilities, and delegated-review matrices
   no longer list OpenCode/zcode as Skill Bill agents (supported or
   “unsupported but installed”).
7. Tests/fixtures/smokes that existed only for these agents are deleted or
   retargeted; remaining suites pass for the reduced agent matrix.
8. Targeted compile/test sets for install + CLI agent matrix + skill wiring
   pass; full `./gradlew check` may wait until later subtasks if prose skills
   still exist until subtask 3.

## Non-Goals

- Deleting `skills/bill-feature-task-prose/` or subtask-runner (subtask 3).
- Removing MCP prose tools or CLI `workflow` (subtasks 4–5).
- Implementing a new OpenCode runtime driver.

## Dependency Notes

- Depends on subtask 1 (stance).
- Blocks subtask 3 (callers no longer point at prose; agent matrix already
  reduced).

## Validation Strategy

- Grep live product (exclude `.feature-specs/done/**` and this SKILL-175 tree)
  for `\bopencode\b` / `\bzcode\b` / `OPENCODE` / `ZCODE` — zero hits outside
  allowlisted historical comments if any remain only in done archives.
- Grep skills + install models for `mode:prose`, `bill-feature-task-prose`,
  `goal_prose`.
- Install-plan schema parity + install unit tests.
- `skill-bill install` help / agent list does not mention opencode/zcode.

## Next Path

```bash
skill-bill goal SKILL-175
```

After this subtask: delete prose skill trees and relocate briefing source of
truth (subtask 3).
