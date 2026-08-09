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
