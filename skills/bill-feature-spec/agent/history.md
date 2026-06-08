## [2026-06-08] decomposition-manifest-schema-in-skills
Areas: skills/bill-feature-spec, skills/bill-feature-task-prose
- Added v0.3 manifest YAML template to decomposed Output Rules; agents fill it directly from planning subagent RESULT
- Updated Shared Preparation Path: agent writes manifest from template on disk — no CLI/MCP route exists for standalone spec preparation
- Template covers all required schema fields: contract_version "0.3", issue_key, feature_name, parent_spec_path, execution_model, base_branch, feature_branch, stack_branches, current_subtask_intent, plus per-subtask status/branch/commit_sha/workflow_id/blocked_reason/last_resumable_step/linear_issue_id/dependencies
- Root cause: FeatureSpecPreparationWriter is internal Kotlin only; prose agents must write YAML directly — template is the only guardrail
Feature flag: N/A
Acceptance criteria: 4/4 implemented
