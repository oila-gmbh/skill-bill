## [2026-09-03] SKILL-229 single-owner run inputs (subtask 2)
Areas: runtime-kotlin/runtime-cli/{core,goal,featuretask,scaffold,install,system,workflow,repovalidation,codereview}, runtime-kotlin/runtime-core/skillbill/di, runtime-kotlin/runtime-ports/{model,ports/system}, runtime-kotlin/runtime-infra-fs
- The six settings `CliRuntimeContext` and `CliRunState` both carried (`dbPathOverride`, `environment`, `userHome`, `externalCommandRunner`, `liveStdout`, `liveStderr`) now live once in an immutable `CliRunInputs`, constructed in `CliRuntime.run` from `RuntimeComponent.resolvedEnvironmentContext` and injected through `CliComponent`. `dbPathOverride` — the field `CliRuntime.run` never copied — reaches consumers as a settled read.
- reusable PATTERN: `RootFlagProbeCommand` pre-parses the root flags (`--db`, `--home`) and folds them into `RuntimeContext` before `RuntimeComponent` is created, so embedding-context-vs-flag precedence is resolved at exactly one seam and no consumer reconciles. Use this seam for any future run-scoped flag that adapters below `runtime-cli` also need.
- `CliRunState` shrank from eight JVM-seeded `var` defaults to result plus the stdin line cursor; per-run mutable state and resolved inputs are now separate objects. `runtime-cli`'s three subtask-1 architecture baselines (ambient clock, ambient environment, `@Inject` constructor defaults) are empty files.
- Commands read `CliRunInputs.repositoryRoot`, the coordinate SKILL-227 already resolves through `canonicalRepositoryRoot`; the fourteen `Path.of("")` fallbacks are gone and `findRepoRoot` lost its default argument. No second derivation was added.
- reusable `HostPlatformPort` (`runtime-ports/skillbill/ports/system`) exposes `osName`, `jvmClassPath`, `pathSeparator`, with `JdkHostPlatformPort` as an `object` adapter in `runtime-infra-fs` constructed by its DI binding. `SKILL_BILL_QUALITY_GATE_SELECTION` reads the injected `environment` map; scaffold dates come from the injected `Clock`.
- Constructor-parameter pressure from threading inputs was absorbed by `@Inject` dependency holders (`UninstallDependencies`, `ScaffoldNewDependencies`), which detekt's `ignoreDataClasses` exempts — the same shape as `GoalRunDependencies`. Prefer a holder over widening a command's parameter list.
- `CliRuntimeContext` keeps its public field shape and stays the embedding surface, so the `runtime-mcp` `CliRuntime.run` call sites needed no change; the resolution seam is internal to `runtime-cli`.
- Known limitation: the `skillbill.cli.core` split, the `*Extras` renames, the `uninstall` failure policy, and the telemetry-drain record stay open for subtask 3.
Feature flag: N/A
Acceptance criteria: 10/10 implemented

## [2026-08-09] SKILL-179 CLI goal no-terminal parks at implement (subtask 2)
Areas: runtime-kotlin/runtime-cli (CliGoalRuntimeTest / GoalFixtureAgentRunLauncher)
- Bound GoalFixtureAgentRunLauncher.startSubtaskWorkflow to the GoalRunner-preopened child via skillRequest.goalContinuation.assignedWorkflowId (or childWorkflowId on resume) instead of continueByIssueKey Start, which minted an unhydrated preplan row and overwrote the manifest pointer.
- Retained the no-terminal assertion at current_step_id=implement: GoalChildPlanningHydrator hydrates goal children to implement after completed preplan+plan (FeatureTaskRuntimePhaseWorkflowDefinition phase sequence); markBlocked's firstUnfinishedStepId scan parks there. Prior preplan observation was fixture-induced, not contractual.
- Optional noTerminal stamp marks implement running so the blocked row mirrors a live hydration-boundary child. CLI test surface remains free of retired prose step ids assess/create_branch.
Feature flag: N/A
Acceptance criteria: 4/4 implemented (validate passed: runtime-cli compileKotlin + compileTestKotlin)

## [2026-08-09] SKILL-175 remove CLI prose workflow family and implement-stats (subtask 5)
Areas: runtime-kotlin/runtime-cli/{workflow,review}, docs, docs/assets, orchestration/workflow-contract
- Removed the Clikt `skill-bill workflow {open,update,show,get,list,latest,resume,continue}` tree bound to `WorkflowFamilyKind.TASK_PROSE` (`ImplementWorkflow*` commands); `WorkflowTopLevelCommands` now registers only `verify-workflow`. Hard removal — no stub that can open prose.
- Deleted `skill-bill implement-stats` (`FeatureImplementStatsCommand`) from the stats command group; operators use `feature-task-stats` / `goal-stats` / `verify-stats` instead.
- CLI tests and the `cli-workflow-show` golden no longer expect prose workflow commands; `ProseWorkflowTestSupport` covers absence / redirect-to-runtime assertions. reusable PATTERN: retire a CLI family by deleting Clikt wiring + help + goldens together, then assert absence rather than stubbing.
- Getting-started, review-telemetry, demo storyboard/gif script, and workflow PLAYBOOK retarget operators to `feature-task` / `goal` / `verify-workflow` surfaces.
- Known limitation: SQLite prose tables / `FeatureImplement*` persistence and IDE prose branch remain until SKILL-175 subtask 6.
Feature flag: N/A
Acceptance criteria: 6/6 implemented
