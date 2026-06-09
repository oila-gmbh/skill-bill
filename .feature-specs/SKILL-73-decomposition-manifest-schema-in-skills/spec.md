---
issue_key: SKILL-73
feature_name: decomposition-manifest-schema-in-skills
status: Complete
---

# SKILL-73: Decomposition Manifest v0.3 Schema in Skills

## Outcome

Fix `bill-feature-spec` and `bill-feature-task-prose` so the agent writes a valid v0.3
decomposition manifest when preparing a decomposed feature spec. Currently neither skill
documents the required manifest fields, causing agents to write a human-readable format that
fails runtime schema validation.

## Root Cause

`bill-feature-spec` says "write `decomposition-manifest.yaml`" and "the manifest is validated
against the schema by the runtime" — but provides no template, field list, or example YAML.
`FeatureSpecPreparationWriter` (the Kotlin class that writes valid manifests) is not exposed
via CLI or MCP, so agents cannot delegate to it. The result: agents write a human-readable
format derived from spec content structure, missing all v0.3 required fields
(`contract_version`, `feature_branch`, `base_branch`, `stack_branches`,
`current_subtask_intent`, per-subtask runtime state fields).

The "Shared Preparation Path" section in `bill-feature-spec` says "always route through the
shared feature-spec preparation runtime path" — but no such CLI/MCP path exists for standalone
spec preparation. This direction is a dead end for prose-mode agents.

## Acceptance Criteria

- [x] `skills/bill-feature-spec/content.md` decomposed output section includes a complete v0.3
  manifest YAML template with all required fields annotated, covering:
  `contract_version: "0.3"`, `issue_key`, `feature_name`, `parent_spec_path`, `spec_source`,
  `execution_model`, `base_branch`, `feature_branch`, `stack_branches`,
  `current_subtask_intent`, and per-subtask: `id`, `name`, `spec_path`, `status: pending`,
  `branch: null`, `commit_sha: null`, `workflow_id: null`, `blocked_reason: null`,
  `last_resumable_step: null`, `linear_issue_id`, `dependencies`
- [x] The "Shared Preparation Path" section is updated to reflect that the agent writes the
  manifest directly using the template (not via a CLI command), and that schema validation
  happens when the runtime first reads the manifest
- [x] `skills/bill-feature-task-prose/content.md` decomposition planning section is updated so
  the planning subagent output spec lists the v0.3 manifest fields the orchestrator must emit
- [x] A manifest written following the updated skill template would pass the schema defined in
  `orchestration/contracts/decomposition-manifest-schema.yaml` (contract_version "0.3")

## Constraints

- Skill content only — do not touch the Kotlin runtime
- No new CLI commands
- Changes must be agent-agnostic (Claude, Codex, etc.)
- Do not change the `single_spec` flow

## Non-Goals

- Adding a `skill-bill prepare-spec` CLI command
- Fixing the NEWS-139 manifest on disk
- Changing `bill-feature-goal` (it is manifest-consumer-only, not a writer)

## Validation Strategy

Read the updated skill files and verify:
1. A complete v0.3 manifest YAML template appears in `bill-feature-spec` decomposed output rules
2. All fields required by `orchestration/contracts/decomposition-manifest-schema.yaml` are covered
3. The "Shared Preparation Path" section no longer implies a CLI call that does not exist
4. The `bill-feature-task-prose` planning subagent output spec references the v0.3 manifest fields

## Next Path

```bash
Run bill-feature-task on .feature-specs/SKILL-73-decomposition-manifest-schema-in-skills/spec.md
```
