## [2026-06-08] decomposition-manifest-schema-in-skills
Areas: skills/bill-feature-task-prose, skills/bill-feature-spec
- Extended decompose RESULT JSON block to include all v0.3 manifest fields: execution_model, base_branch, feature_branch, stack_branches, current_subtask_intent at top level; status/branch/commit_sha/workflow_id/blocked_reason/last_resumable_step/linear_issue_id/dependencies per subtask
- Planning subagent RESULT is now the direct source the orchestrator uses to fill the bill-feature-spec manifest template — no derivation or defaulting needed
- Breaking change to planning subagent contract: callers expecting only depends_on/scope/acceptance_criteria in subtask objects now also receive manifest runtime fields with their initial values
Feature flag: N/A
Acceptance criteria: 4/4 implemented
