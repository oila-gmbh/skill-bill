# SKILL-175 — additional removal surfaces (inventory addendum)

This file is **additive to `spec.md` sections A–G and never overrides them**. Section
A–G rows remain the authoritative removal checklist; the rows below are surfaces found
by the subtask-1 read-only grep sweep that sections A–G do not name by path. A conflict
between this file and `spec.md` resolves in favour of `spec.md`.

Sweep used (read-only, run from repo root, allowlist = `.feature-specs/**`):

- case-insensitive `opencode|zcode`
- `mode:prose | feature_task_prose | FeatureImplement | TASK_PROSE | goal_prose |
  feature_implement_sessions | FEATURE_TASK_PROSE | WorkflowFamily.IMPLEMENT`

Actions: **delete** (surface goes away), **retarget** (surface stays, prose/opencode
content removed or repointed at runtime), **keep-as-English** (natural-language use, out
of deletion scope).

## Surfaces named by preplan as missing from the parent map

| Path | Matched token | Owning subtask | Action |
|------|---------------|----------------|--------|
| `runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/domain/skillremove/model/SkillRemovalPreview.kt` | `OPENCODE`, `ZCODE` (AgentSymlinkProvider) | 2 | retarget (drop provider cases) |
| `runtime-kotlin/runtime-infra-fs/src/main/kotlin/skillbill/infrastructure/fs/FileSystemReviewNativeAgentPreflight.kt` | `opencode`, `zcode` | 2 | retarget (drop preflight branches) |
| `runtime-kotlin/runtime-cli/src/main/kotlin/skillbill/cli/scaffold/ScaffoldCliCommands.kt` | `Opencode`, `Zcode` | 5 | retarget (drop provider values) |
| `runtime-kotlin/runtime-cli/src/main/kotlin/skillbill/cli/install/InstallCliCommands.kt` | `opencodeAgentsPath(...)`, `zcodeAgentsPath(...)` call sites | 5 | retarget (drop opencode/zcode install paths) |
| `runtime-kotlin/runtime-core/src/test/kotlin/skillbill/architecture/InstallerShellDelegationTest.kt` | `opencode` | 7 | retarget (drop agent rows from expectations) |
| `runtime-kotlin/runtime-infra-sqlite/src/main/kotlin/skillbill/db/core/StaleReconciliationCandidateQuery.kt` | `feature_implement_sessions` | 6 | retarget (stop reconciling prose sessions) |
| `runtime-kotlin/runtime-infra-sqlite/src/main/kotlin/skillbill/db/core/DatabaseSchema.kt` | `feature_implement_sessions` | 6 | retarget (table retained read-only, no live writer) |
| `runtime-kotlin/runtime-infra-sqlite/src/main/kotlin/skillbill/db/core/StaleSessionReconciler.kt` | `feature_implement_sessions`, `FeatureImplementFinished` | 6 | retarget (drop prose session reconciliation) |
| `runtime-kotlin/runtime-infra-sqlite/src/main/kotlin/skillbill/db/telemetry/GoalTelemetrySaveSupport.kt` | `feature_implement_sessions` | 6 | retarget (drop prose session writes) |
| `runtime-kotlin/runtime-infra-sqlite/src/main/kotlin/skillbill/db/workflow/WorkflowStateStore.kt` | `FeatureImplementWorkflowStateRepository`, `FeatureImplementSessionSummary` | 6 | retarget (delete prose repository surface) |
| `runtime-kotlin/runtime-mcp/src/test/kotlin/skillbill/mcp/McpRuntimeTest.kt` | `FeatureImplementStartedRequest`, `FeatureImplementFinishedRequest` | 4 | retarget (drop prose tool cases) |
| `runtime-kotlin/runtime-mcp/src/test/kotlin/skillbill/mcp/TelemetryReliabilityContractTest.kt` | `FeatureImplementStartedRecord`, `feature_task_prose` | 4 | retarget (drop prose event coverage) |

## Further surfaces found by the sweep

| Path | Matched token | Owning subtask | Action |
|------|---------------|----------------|--------|
| `.agnix.toml` | `opencode = true` | 2 | retarget (drop opencode target) |
| `.gitignore` | `opencode-agents/` ignore rules | 2 | retarget (drop opencode ignore lines) |
| `runtime-kotlin/runtime-application/src/main/kotlin/skillbill/application/scaffold/InstallAgentService.kt` | `opencodeAgentsPath`, `zcodeAgentsPath` | **5** | delete (path helpers) — see compile-order note below |
| `runtime-kotlin/runtime-infra-fs/src/main/kotlin/skillbill/scaffold/pointer/GeneratedAgentAddonArtifactDiscovery.kt` | `"opencode-agents"` | 2 | retarget (drop addon dir) |
| `runtime-kotlin/runtime-infra-fs/src/main/kotlin/skillbill/scaffold/rendering/SubagentScaffoldRendering.kt` | `openCodeSpawnParagraph`, `OpenCode` | 2 | retarget (drop OpenCode rendering branch) |
| `runtime-kotlin/runtime-infra-fs/src/main/kotlin/skillbill/skillremove/SkillRemoveJvmFileSystem.kt` | `AgentSymlinkProvider.OPENCODE`, `.ZCODE`, `unlinkOpencodeAgents` | 2 | retarget (drop provider cases) |
| `runtime-kotlin/runtime-infra-fs/src/main/kotlin/skillbill/launcher/agentrun/AgentRunAdapters.kt` | `zcodeStdoutMapper`, `InstallAgent.ZCODE` | 2 | delete (zcode stdout normalize) |
| `runtime-kotlin/runtime-infra-fs/src/main/kotlin/skillbill/launcher/agentrun/FileSystemAgentRunLauncher.kt` | `opencode, zcode` refusal comment | 2 | retarget (drop refuse-tier remnant) |
| `runtime-kotlin/runtime-infra-fs/src/main/kotlin/skillbill/nativeagent/README.md` | `Opencode`, `{{#opencode}}` | 2 | retarget (drop provider from body/validator docs) |
| `runtime-kotlin/runtime-infra-fs/src/main/kotlin/skillbill/scaffold/payload/ScaffoldPayloadMapPolicy.kt` | `rejectRetiredFeatureImplementFamily` | 5 | keep (already a retired-family loud-fail; verify it still rejects) |
| `runtime-kotlin/runtime-infra-fs/src/main/kotlin/skillbill/scaffold/runtime/RepoValidationRuntime.kt` | `feature_task_prose_workflow_*` | 4 | retarget (drop prose MCP tool names) |
| `runtime-kotlin/runtime-infra-fs/src/test/resources/snapshots/scaffold/bill-kmp-code-review.render.txt` | `OpenCode` | 7 | retarget (regenerate snapshot) |
| `runtime-kotlin/runtime-infra-fs/src/test/resources/snapshots/scaffold/bill-kotlin-code-review.render.txt` | `OpenCode` | 7 | retarget (regenerate snapshot) |
| `orchestration/review-delegation/PLAYBOOK.md` | `## Opencode` | 7 | delete (section; not "unsupported" row) |
| `orchestration/shell-content-contract/PLAYBOOK.md:114` | "Opencode delegated review is intentionally unsupported." | 7 | **delete (line)** — a permanent "unsupported agents" statement, which decision item 3 explicitly forbids preserving. The adjacent Junie line stays. |
| `orchestration/shell-content-contract/SCAFFOLD_PAYLOAD.md` | `OpenCode markdown` | 7 | retarget |
| `orchestration/telemetry-contract/PLAYBOOK.md` | `feature_task_prose_started/_finished` | 4 | retarget |
| `orchestration/workflow-contract/PLAYBOOK.md` | `feature_task_prose_workflow_*` | 4 | retarget |
| `docs/assets/skill-bill-demo-storyboard.md` | `feature_task_prose_workflow_update` | 7 | retarget (runtime surface in storyboard) |
| `agent/history.md`, `runtime-kotlin/agent/history.md`, `runtime-kotlin/runtime-*/agent/history.md`, `skills/agent/history.md` | `opencode`, `zcode`, prose tokens | — | keep (historical record; never rewritten) |
| `.idea/**`, `intellij-plugin/.intellijPlatform/sandbox/**`, `.skill-bill/run-evidence/**` | assorted | — | keep (untracked/generated local state, not product code) |

## Compile-order note: the agents-path helper cluster (subtask 5)

`InstallAgentService.opencodeAgentsPath` / `zcodeAgentsPath` **must not** be
deleted in subtask 2. They have live callers outside subtask 2's scope, and
deleting the helpers first leaves `runtime-cli` non-compiling through subtasks 2,
3 and 4 — the validation gate would fail at three consecutive checkpoints and
every intermediate commit would be broken. The helpers and **all** their callers
are therefore removed together in **subtask 5**:

| Path | Reference |
|------|-----------|
| `runtime-kotlin/runtime-application/src/main/kotlin/skillbill/application/scaffold/InstallAgentService.kt` | helper declarations |
| `runtime-kotlin/runtime-cli/src/main/kotlin/skillbill/cli/install/InstallCliCommands.kt` | direct calls (`:571`, `:601`) |
| `runtime-kotlin/runtime-cli/src/main/kotlin/skillbill/cli/scaffold/ScaffoldCliCommands.kt` | `InstallOpencodeAgentsPathCommand` / `InstallZcodeAgentsPathCommand` wiring |
| `runtime-kotlin/runtime-infra-fs/src/main/kotlin/skillbill/infrastructure/fs/FileSystemInstallAdapters.kt` | adapter calls |
| `runtime-kotlin/runtime-infra-fs/src/main/kotlin/skillbill/install/plan/InstallPrimitives.kt` | own `opencodeAgentsPath` primitive |
| `runtime-kotlin/runtime-infra-fs/src/main/kotlin/skillbill/install/runtime/InstallOperations.kt` | own `opencodeAgentsPath` / `zcodeAgentsPath` |

The KSP-generated `InjectCliComponent.kt` binding regenerates from the command
wiring and needs no explicit row.

## Sweep re-run (post-checklist verification)

The declared case-insensitive `opencode|zcode` sweep was re-run after the rows
above were added, excluding `.git`, `build/`, `.idea/`, `sandbox/`,
`.skill-bill/`, `.feature-specs/` and `agent/history.md`. Every hit is now owned
either by a `spec.md` section A–G row (by path or by symbol) or by a row in this
file. The hits below matched **no** path named in `spec.md`, so they are recorded
here explicitly rather than left to symbol-level inference:

| Path group | Owning subtask | Action |
|------------|----------------|--------|
| `runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/install/model/InstallModels.kt`, `runtime-kotlin/runtime-ports/src/main/kotlin/skillbill/ports/install/model/InstallPortModels.kt` | 2 | retarget (drop `InstallAgent.OPENCODE`/`ZCODE` and provider ids) |
| `runtime-kotlin/runtime-infra-fs/.../launcher/mcp/McpOpenCodeConfig.kt`, `McpZcodeConfig.kt` | 2 | delete (whole files) |
| `runtime-kotlin/runtime-infra-fs/.../launcher/mcp/McpRegistrationOperations.kt` | 2 | retarget (drop opencode/zcode registration branches) |
| `runtime-kotlin/runtime-infra-fs/.../install/nativeagent/{InstallNativeAgentOperations,InstallNativeAgentPrimitives,NativeAgentLinkInventory}.kt`, `install/apply/InstallApplyNativeAgents.kt`, `nativeagent/rendering/NativeAgentRendering.kt` | 2 | retarget (drop provider cases) |
| `runtime-kotlin/runtime-cli/.../cli/system/UninstallCommand.kt`, `cli/goal/GoalCliCommands.kt`, `cli/featuretask/FeatureTaskRuntimeCliCommands.kt`, `cli/codereview/CodeReviewParallelCommand.kt` | 5 | retarget (drop agent options / refusal text) |
| `scripts/install_smoke_test.sh`, `scripts/agent_install_smoke_test.sh` | 5 | retarget (drop opencode/zcode install assertions) |
| `docs/delegated-review/{decision,provider-capability-matrix,provider-failure-dispositions,reliability-contract}.md` | 7 | retarget (drop opencode/zcode provider rows; no "unsupported" residue) |
| `docs/getting-started.md`, `docs/getting-started-for-teams.md` | 7 | retarget (drop opencode/zcode from agent lists) |
| all `src/test/**` hits in `runtime-cli`, `runtime-application`, `runtime-domain`, `runtime-infra-fs` (install, native-agent, launcher, scaffold suites) | 7 | retarget (drop opencode/zcode cases and expectations alongside their production surface) |

## Keep-as-English (do not delete)

| Path | Wording |
|------|---------|
| `AGENTS.md:83` | "Write direct, active prose" |
| `orchestration/contracts/native-agent-composition-schema.yaml:114,204` | "the governed prose already…" |
| `skills/bill-pr-review-fix/content.md:224` | "write 1-3 sentences in plain prose" |
| `orchestration/contracts/review-context-schema.yaml:403` | "a bounded prose summary" |
| `orchestration/contracts/platform-pack-schema.yaml:127,206` | "prose tie-breakers", "Short prose describing…" |
