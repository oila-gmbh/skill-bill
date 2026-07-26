---
issue_key: SKILL-51
feature_name: decomposition-workflow-state
subtask_id: 2
subtask_name: runtime-state
parent_spec: .feature-specs/SKILL-51-decomposition-workflow-state/spec.md
status: Complete
---

# SKILL-51 Subtask 2: Runtime State

Parent overview: [spec.md](spec.md)

## Scope

Extend durable workflow state so a decomposed parent feature can track subtask execution details authoritatively at runtime. Add per-subtask state fields for status, branch, commit SHA, workflow id, review result, audit result, validation result, blocked reason, and last resumable step, while preserving the existing single-spec feature-implement workflow behavior.

This subtask owns domain/application/runtime-contract changes needed to persist, load, validate, and update per-subtask details. It should add automatic status updates for implementation, review, completeness audit, validation, blocked, skipped, and PR-description outcomes where those events already pass through the workflow engine/service. Commit-related status advancement may be represented in state here, but actual git commit creation is deferred to the branch orchestration subtask.

Enforce `execution_model` immutability after execution begins. Changing `execution_model` before any subtask starts should be allowed. Changing it after any subtask has started must fail loudly with a typed/manual-migration message.

Markdown status/frontmatter projection may be updated where the runtime already writes human-readable artifacts, but durable runtime state remains authoritative for resume decisions.

## Acceptance Criteria

- AC3: Runtime workflow state tracks per-subtask execution details, including status, branch, commit SHA, workflow id, review result, audit result, validation result, blocked reason, and last resumable step.
- AC12: Changing `execution_model` is allowed before any subtask starts; changing it after execution begins fails loudly with a typed/manual-migration message.
- AC13, state slice: Parent and subtask statuses update automatically after implementation, review, completeness audit, validation, PR-description, blocked, and skipped outcomes.
- AC14, state slice: Markdown spec status/frontmatter is kept as a human-readable projection where practical, while durable runtime state remains authoritative.
- AC15: Existing single-spec `bill-feature-implement` workflows continue to work without requiring a decomposition manifest.
- AC16, state slice: Tests cover runtime state validation and status update behavior needed by later continuation.

## Non-goals

- Do not implement next-subtask selection for `continue SKILL-XX`.
- Do not implement git branch checkout or commit creation.
- Do not create or merge GitHub PRs differently.
- Do not introduce SKILL-52 full hexagonal architecture or replace the existing workflow state contract wholesale.

## Dependencies

- Depends on subtask 1 because runtime state needs the parent decomposition manifest contract and typed validation seams.

## Validation Strategy

Run `bill-quality-check`. At minimum, add workflow-state mapping/persistence tests, schema/golden updates as needed, execution-model immutability tests, blocked/skipped status tests at the service or engine layer, and regressions for existing single-spec workflows.

## Recommended Next Prompt

Run bill-feature-implement on .feature-specs/SKILL-51-decomposition-workflow-state/spec_subtask_2_runtime-state.md.
