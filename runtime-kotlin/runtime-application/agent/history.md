## [2026-07-25] SKILL-142 subtask 2 — Planning projection gate parity
Areas: runtime-application, runtime-infra-sqlite, runtime-ports
- Added producer-side planning projection gate for preplan/plan phases; completed outputs now validate against the planning projection contract before being marked settled or checkpointed
- Introduced in-band replace path to goal planning preparation store (replaceSharedPreplan/replaceSubtaskPlan) for repairing projection-invalid records without losing the parent workflow id
- Producer gate (FeatureTaskRuntimePlanningProjectionGate) and consumer launch seam (GoalChildPlanningHydrator) now share one validation function through one validator port; closes the parity gap where hydration validated phase-output contract while consumer validated planning projection
- GoalPlanningPreparationCheckpoint rejects projection-invalid shared preplans and subtask plans at write time; descriptor() recovers subSpecHash from stored record independent of projection verdict
- Checkpoint-level acceptance tests: projection-invalid records are replaced; gate-satisfying records with different bytes still loud-fail as immutable
- Breaking changes/limitations: Shared preplan replace uses UPDATE-then-insert rather than DELETE because goal_subtask_plans cascades on the shared row (provenance safety)
Feature flag: N/A
Acceptance criteria: 8/8 implemented

## [2026-07-25] SKILL-141-goal-parent-resume-lifecycle (subtask 1: parent-workflow-non-terminal-status)
Areas: runtime-application, runtime-infra-fs, runtime-mcp
- Added non-terminal `paused` parent-workflow status; GoalRunnerWorkflowStores.importFromManifestProjection and DecompositionWorkflowContinuation stamp `paused` on interruption instead of `abandoned`
- DecompositionWorkflowRuntimeLookup.findDecomposedParentWorkflow reuses non-terminal parents; isStaleAbandonedLineage never GCs them
- GoalChildPlanningHydrator hydrates from non-terminal parents preserving GoalPlanningIdentity; resume reuses the existing parent workflow id with no identity loud-fail
- FeatureTaskRuntimePhasePromptComposer updated to recognise the new status
- AgentRunCommandBuilders (runtime-infra-fs): foreground driver propagates and detects paused parent status on launch
- McpToolRegistry (runtime-mcp): removed `paused` from feature_task_runtime_workflow_update enum; runtime tool now advertises only statuses FeatureTaskRuntimePhaseWorkflowDefinition accepts; prose tool retains paused unchanged
- Explicit operator abandonment path unchanged: still terminal, reason-required, stamps operator-abandonment artifact
Feature flag: N/A
Acceptance criteria: 7/7 implemented
