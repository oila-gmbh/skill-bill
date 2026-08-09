## [2026-08-09] SKILL-175 remove CLI prose workflow family and implement-stats (subtask 5)
Areas: runtime-kotlin/runtime-cli/{workflow,review}, docs, docs/assets, orchestration/workflow-contract
- Removed the Clikt `skill-bill workflow {open,update,show,get,list,latest,resume,continue}` tree bound to `WorkflowFamilyKind.TASK_PROSE` (`ImplementWorkflow*` commands); `WorkflowTopLevelCommands` now registers only `verify-workflow`. Hard removal — no stub that can open prose.
- Deleted `skill-bill implement-stats` (`FeatureImplementStatsCommand`) from the stats command group; operators use `feature-task-stats` / `goal-stats` / `verify-stats` instead.
- CLI tests and the `cli-workflow-show` golden no longer expect prose workflow commands; `ProseWorkflowTestSupport` covers absence / redirect-to-runtime assertions. reusable PATTERN: retire a CLI family by deleting Clikt wiring + help + goldens together, then assert absence rather than stubbing.
- Getting-started, review-telemetry, demo storyboard/gif script, and workflow PLAYBOOK retarget operators to `feature-task` / `goal` / `verify-workflow` surfaces.
- Known limitation: SQLite prose tables / `FeatureImplement*` persistence and IDE prose branch remain until SKILL-175 subtask 6.
Feature flag: N/A
Acceptance criteria: 6/6 implemented
