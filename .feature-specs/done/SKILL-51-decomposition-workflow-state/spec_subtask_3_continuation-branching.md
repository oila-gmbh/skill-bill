---
issue_key: SKILL-51
feature_name: decomposition-workflow-state
subtask_id: 3
subtask_name: continuation-branching
parent_spec: .feature-specs/SKILL-51-decomposition-workflow-state/spec.md
status: Complete
---

# SKILL-51 Subtask 3: Continuation And Branching

Parent overview: [spec.md](spec.md)

## Scope

Wire parent-level continuation and execution-model branch behavior for decomposed features. `continue SKILL-XX` should resolve the decomposed parent by issue key, inspect the authoritative parent manifest and runtime state, and resume or start the correct subtask without requiring the user to name the subtask.

Continuation must resume an in-progress subtask at its last durable workflow step. If no subtask is in progress, it must start the first pending subtask whose dependencies are complete. If a dependency is blocked, continuation must report the blocked reason and must not skip to a later dependent subtask unless the manifest explicitly marks that dependency optional or skipped.

Implement same-branch commit-per-subtask behavior as the default: each completed subtask must produce an individual commit before the runtime advances to the next subtask. Implement stacked-branch behavior as an opt-in execution model: create or check out the correct branch for each subtask, record branch base relationships, and prevent dependent subtasks from advancing onto the wrong base.

This subtask owns the CLI/MCP/runtime-application seams for parent issue-key continuation, subtask eligibility, branch selection, commit advancement, and branch metadata persistence.

## Acceptance Criteria

- AC4: `continue SKILL-XX` can resolve a decomposed parent feature, determine the next eligible subtask, and resume or start that subtask without the user naming the subtask.
- AC5: If a subtask is in progress, `continue SKILL-XX` resumes that subtask at the last durable workflow step.
- AC6: If no subtask is in progress, `continue SKILL-XX` starts the first pending subtask whose dependencies are complete.
- AC7: If a subtask is blocked, `continue SKILL-XX` reports the blocked reason and does not skip to a later dependent subtask unless the manifest explicitly marks that dependency optional or skipped.
- AC8: Same-branch commit-per-subtask execution is the default for decomposed features.
- AC9: In same-branch mode, every completed subtask produces an individual commit before the runtime advances to the next subtask.
- AC10: Stacked-branch execution is supported as an opt-in parent manifest setting.
- AC11: In stacked-branch mode, the runtime creates or checks out the correct branch for each subtask, records branch base relationships, and does not advance dependent subtasks onto the wrong base.
- AC13, branch slice: Parent and subtask statuses update automatically after commit and branch-related advancement outcomes.
- AC16, continuation slice: Tests cover same-branch advancement, stacked-branch branch selection, blocked subtask behavior, and resume of an in-progress subtask.

## Non-goals

- Do not alter GitHub PR creation or merge behavior beyond recording branch base metadata required for stacked PRs.
- Do not automatically migrate already-started same-branch work into a stacked branch series.
- Do not replace the existing feature-implement workflow state contract wholesale.
- Do not implement SKILL-53 shared install-selection persistence.

## Dependencies

- Depends on subtask 1 for the parent manifest contract and execution-model metadata.
- Depends on subtask 2 for persisted subtask state, statuses, blocked reasons, workflow ids, and last resumable steps.

## Validation Strategy

Run `bill-quality-check`. At minimum, add CLI and MCP continuation tests for parent issue-key resolution, in-progress resume, pending dependency selection, blocked dependency reporting, same-branch commit advancement, stacked branch base selection, and wrong-base rejection.

## Recommended Next Prompt

Run bill-feature-implement on .feature-specs/SKILL-51-decomposition-workflow-state/spec_subtask_3_continuation-branching.md.
