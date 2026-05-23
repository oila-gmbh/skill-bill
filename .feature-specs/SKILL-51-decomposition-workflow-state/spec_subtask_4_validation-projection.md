---
issue_key: SKILL-51
feature_name: decomposition-workflow-state
subtask_id: 4
subtask_name: validation-projection
parent_spec: .feature-specs/SKILL-51-decomposition-workflow-state/spec.md
status: Complete
---

# SKILL-51 Subtask 4: Validation And Projection

Parent overview: [spec.md](spec.md)

## Scope

Complete end-to-end validation, documentation/prose wiring, and human-readable projection for decomposed feature workflow state. Update the authored `bill-feature-implement` source guidance and native-agent source so future decomposition planning emits and respects the parent decomposition manifest, current subtask intent, execution model, same-branch default, stacked-branch opt-in, and parent-level continuation behavior.

Keep edits in governed source files only, such as `skills/bill-feature-implement/content.md` and `skills/bill-feature-implement/native-agents/agents.yaml`; do not edit generated `SKILL.md`, generated support pointers, provider-specific native-agent outputs, or install staging artifacts.

Finish any remaining Markdown spec status/frontmatter projection so parent and subtask specs show useful human-readable state after implementation, review, completeness audit, validation, commit, PR-description, blocked, skipped, and completion outcomes. Durable runtime state must remain authoritative for resume decisions.

This subtask is also the final acceptance sweep for SKILL-51. It should ensure tests cover the full contract and that all new contract/resource files are included in repo validation.

## Acceptance Criteria

- AC1: Decomposition creates or updates a validator-backed parent decomposition manifest for every decomposed feature.
- AC2: The manifest declares ordered subtasks, dependencies, spec paths, `execution_model`, base branch, feature branch or stack branches, and current subtask intent.
- AC3: Runtime workflow state tracks per-subtask execution details, including status, branch, commit SHA, workflow id, review result, audit result, validation result, blocked reason, and last resumable step.
- AC4: `continue SKILL-XX` can resolve a decomposed parent feature, determine the next eligible subtask, and resume or start that subtask without the user naming the subtask.
- AC5: If a subtask is in progress, `continue SKILL-XX` resumes that subtask at the last durable workflow step.
- AC6: If no subtask is in progress, `continue SKILL-XX` starts the first pending subtask whose dependencies are complete.
- AC7: If a subtask is blocked, `continue SKILL-XX` reports the blocked reason and does not skip to a later dependent subtask unless the manifest explicitly marks that dependency optional or skipped.
- AC8: Same-branch commit-per-subtask execution is the default for decomposed features.
- AC9: In same-branch mode, every completed subtask produces an individual commit before the runtime advances to the next subtask.
- AC10: Stacked-branch execution is supported as an opt-in parent manifest setting.
- AC11: In stacked-branch mode, the runtime creates or checks out the correct branch for each subtask, records branch base relationships, and does not advance dependent subtasks onto the wrong base.
- AC12: Changing `execution_model` is allowed before any subtask starts; changing it after execution begins fails loudly with a typed/manual-migration message.
- AC13: Parent and subtask statuses update automatically after implementation, review, completeness audit, validation, commit, PR-description, blocked, and skipped outcomes.
- AC14: Markdown spec status/frontmatter is kept as a human-readable projection of runtime state where practical, while durable runtime state remains authoritative for resume decisions.
- AC15: Existing single-spec `bill-feature-implement` workflows continue to work without requiring a decomposition manifest.
- AC16: Tests cover manifest creation, manifest validation failures, same-branch subtask advancement, stacked-branch branch selection, blocked subtask behavior, resume of an in-progress subtask, and completion of all subtasks.

## Non-goals

- Do not implement SKILL-52 full hexagonal architecture.
- Do not implement SKILL-53 shared install-selection persistence.
- Do not require every feature to be decomposed.
- Do not force stacked branches as the default.
- Do not create or merge GitHub PRs differently unless branch base metadata for stacked PRs already requires it.

## Dependencies

- Depends on subtask 1 for manifest creation and validation.
- Depends on subtask 2 for durable per-subtask workflow state and status updates.
- Depends on subtask 3 for continuation, same-branch commits, and stacked-branch behavior.

## Validation Strategy

Run `bill-quality-check`. For final maintainer confidence, also run or document results for `skill-bill validate`, `(cd runtime-kotlin && ./gradlew check)`, `npx --yes agnix --strict .`, and `scripts/validate_agent_configs` when available. The test suite should include end-to-end completion of all subtasks plus the specific AC16 cases.

## Recommended Next Prompt

Run bill-feature-implement on .feature-specs/SKILL-51-decomposition-workflow-state/spec_subtask_4_validation-projection.md.
