---
issue_key: SKILL-51
feature_name: decomposition-workflow-state
status: Complete
feature_size: LARGE
rollout_needed: false
sources:
  - user briefing in current pre-planning run
---

# SKILL-51: Decomposition Workflow State

## Status

Complete

## Consolidated Spec

Implement durable decomposition workflow state for `bill-feature-implement` so decomposed parent features have a validator-backed parent manifest, resumable subtask execution details, and parent-level continuation behavior.

Same-branch commit-per-subtask execution is the default for decomposed features. Stacked-branch execution is supported as an opt-in parent manifest setting.

Existing single-spec `bill-feature-implement` workflows must continue to work without requiring a decomposition manifest.

## Acceptance Criteria

1. Decomposition creates or updates a validator-backed parent decomposition manifest for every decomposed feature.
2. The manifest declares ordered subtasks, dependencies, spec paths, `execution_model`, base branch, feature branch or stack branches, and current subtask intent.
3. Runtime workflow state tracks per-subtask execution details, including status, branch, commit SHA, workflow id, review result, audit result, validation result, blocked reason, and last resumable step.
4. `continue SKILL-XX` can resolve a decomposed parent feature, determine the next eligible subtask, and resume or start that subtask without the user naming the subtask.
5. If a subtask is in progress, `continue SKILL-XX` resumes that subtask at the last durable workflow step.
6. If no subtask is in progress, `continue SKILL-XX` starts the first pending subtask whose dependencies are complete.
7. If a subtask is blocked, `continue SKILL-XX` reports the blocked reason and does not skip to a later dependent subtask unless the manifest explicitly marks that dependency optional or skipped.
8. Same-branch commit-per-subtask execution is the default for decomposed features.
9. In same-branch mode, every completed subtask produces an individual commit before the runtime advances to the next subtask.
10. Stacked-branch execution is supported as an opt-in parent manifest setting.
11. In stacked-branch mode, the runtime creates or checks out the correct branch for each subtask, records branch base relationships, and does not advance dependent subtasks onto the wrong base.
12. Changing `execution_model` is allowed before any subtask starts. Changing it after execution begins fails loudly with a typed/manual-migration message.
13. Parent and subtask statuses update automatically after implementation, review, completeness audit, validation, commit, PR-description, blocked, and skipped outcomes.
14. Markdown spec status/frontmatter is kept as a human-readable projection of runtime state where practical, but durable runtime state remains authoritative for resume decisions.
15. Existing single-spec `bill-feature-implement` workflows continue to work without requiring a decomposition manifest.
16. Tests cover manifest creation, manifest validation failures, same-branch subtask advancement, stacked-branch branch selection, blocked subtask behavior, resume of an in-progress subtask, and completion of all subtasks.

## Non-goals

- Implement SKILL-52 full hexagonal architecture.
- Implement SKILL-53 shared install-selection persistence.
- Replace the existing feature-implement workflow state contract wholesale.
- Require every feature to be decomposed.
- Force stacked branches as the default.
- Automatically migrate already-started same-branch work into a stacked branch series.
- Create or merge GitHub PRs differently unless the execution model requires branch base metadata for stacked PRs.
