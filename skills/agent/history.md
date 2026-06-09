## [2026-06-08] decomposition-manifest-schema-in-skills
Areas: skills/bill-feature-spec, skills/bill-feature-task-prose
- Added v0.3 manifest YAML template to bill-feature-spec decomposed Output Rules; agents fill it directly from planning subagent RESULT
- Updated Shared Preparation Path: explicitly states agent writes YAML directly — no CLI/MCP route exists for standalone spec preparation (FeatureSpecPreparationWriter is internal Kotlin only)
- Extended bill-feature-task-prose planning subagent decomposition RESULT block with all manifest fields: execution_model, base_branch, feature_branch, stack_branches, current_subtask_intent (top-level) and status/branch/commit_sha/workflow_id/blocked_reason/last_resumable_step/linear_issue_id/dependencies (per-subtask)
- Skill source dirs (skills/<skill>/) may only contain content.md and native-agents/ — do NOT create agent/ subdirectories inside them; boundary history belongs at skills/agent/history.md
Feature flag: N/A
Acceptance criteria: 4/4 implemented
