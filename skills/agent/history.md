## [2026-06-23] SKILL-93 update-check-on-bill-feature (bill-feature gate)
Areas: skills/bill-feature
- `bill-feature` now calls `mcp__skill-bill__update_check` as the **first** action before any intake or spec-prep work
- Gates on `update_available`: surfaces installed vs latest version, asks update-now-or-continue; stops with `recommended_install_command` if user chooses update
- All other statuses (up-to-date, ahead, unknown) proceed to intake silently — no user prompt (reusable gate pattern for skill entry-point version checks)
Feature flag: N/A
Acceptance criteria: 12/12 implemented

## [2026-06-23] SKILL-92 bill-feature-goal prose lifecycle telemetry wiring
Areas: skills/bill-feature-goal
- `bill-feature-goal mode:prose` now emits goal lifecycle telemetry: calls `goal_prose_started` at goal start, `goal_prose_subtask_finished` after each subtask, and `goal_prose_finished` at goal completion/termination.
- Idempotency is documented in the skill: all three tools are safe to re-call; re-emitting an already-recorded boundary is a no-op.
Feature flag: N/A
Acceptance criteria: 1/1 (AC#5) implemented

## [2026-06-13] prose-goal-subtask-isolation
Areas: skills/bill-feature-goal, skills/bill-feature-task-prose, skills/bill-feature-task-subtask-runner, scripts
- Goal orchestrator (bill-feature-goal mode:prose) stays thin: holds only decomposition manifest + per-subtask terminal outcomes; no phase artifacts accumulated
- Subtask execution delegated to Level-1 Agent-tool spawn (bill-feature-task-subtask-runner) with self-contained briefing; continuation via feature_task_prose_workflow_continue with suppress_pr=true
- Terminal outcome verified via feature_task_prose_workflow_get after each subtask-agent returns; in-session return value is signal only
- Stop-loudly contract: on subtask failure, orchestrator surfaces subtask ID, blocked_reason, workflow ID, and last_resumable_step; does not advance
- New native agent entry bill-feature-task-subtask-runner in skills/bill-feature-task-prose/native-agents/agents.yaml (reusable)
- New skill skills/bill-feature-task-subtask-runner/content.md — Level-1 subtask agent; required by validateAgentConfigs for bill-* references in prose .md files (reusable)
- 3-level Agent nesting verified via scripts/agent_nesting_smoke_test.sh go/no-go gate (pattern reusable for nesting depth validation)
Feature flag: N/A
Acceptance criteria: 10/10 implemented

## [2026-06-08] decomposition-manifest-schema-in-skills
Areas: skills/bill-feature-spec, skills/bill-feature-task-prose
- Added v0.3 manifest YAML template to bill-feature-spec decomposed Output Rules; agents fill it directly from planning subagent RESULT
- Updated Shared Preparation Path: explicitly states agent writes YAML directly — no CLI/MCP route exists for standalone spec preparation (FeatureSpecPreparationWriter is internal Kotlin only)
- Extended bill-feature-task-prose planning subagent decomposition RESULT block with all manifest fields: execution_model, base_branch, feature_branch, stack_branches, current_subtask_intent (top-level) and status/branch/commit_sha/workflow_id/blocked_reason/last_resumable_step/linear_issue_id/dependencies (per-subtask)
- Skill source dirs (skills/<skill>/) may only contain content.md and native-agents/ — do NOT create agent/ subdirectories inside them; boundary history belongs at skills/agent/history.md
Feature flag: N/A
Acceptance criteria: 4/4 implemented
