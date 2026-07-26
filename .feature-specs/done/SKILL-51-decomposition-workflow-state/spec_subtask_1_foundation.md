---
issue_key: SKILL-51
feature_name: decomposition-workflow-state
subtask_id: 1
subtask_name: foundation
parent_spec: .feature-specs/SKILL-51-decomposition-workflow-state/spec.md
status: Complete
---

# SKILL-51 Subtask 1: Foundation

Parent overview: [spec.md](spec.md)

## Scope

Create the durable parent decomposition manifest contract and the runtime seams that create or update that manifest when `bill-feature-implement` planning chooses decomposition mode. The manifest should be validator-backed and should declare ordered subtasks, dependency metadata, subtask spec paths, `execution_model`, base branch, feature branch or stack branch metadata, and current subtask intent.

This subtask owns the contract shape, schema validation, typed loud-fail behavior, classpath/resource wiring, Kotlin model mapping, and initial manifest creation/update from the existing decomposition planning artifact. Use the established runtime-contract pattern from workflow state and platform pack schemas: Draft 2020-12 YAML under `orchestration/contracts/`, a matching Kotlin contract version constant, parity/violation tests, typed `ShellContentContractException`, and read-seam validation.

Same-branch execution must be the default when the manifest is created. Stacked-branch execution should be representable as an opt-in manifest setting, but branch checkout behavior is deferred to a later subtask.

Existing single-spec workflows must continue without a decomposition manifest.

## Acceptance Criteria

- AC1: Decomposition creates or updates a validator-backed parent decomposition manifest for every decomposed feature.
- AC2: The manifest declares ordered subtasks, dependencies, spec paths, `execution_model`, base branch, feature branch or stack branches, and current subtask intent.
- AC8: Same-branch commit-per-subtask execution is the default for decomposed features.
- AC10: Stacked-branch execution is supported as an opt-in parent manifest setting at the manifest/contract level.
- AC15: Existing single-spec `bill-feature-implement` workflows continue to work without requiring a decomposition manifest.
- AC16, foundation slice: Tests cover manifest creation and manifest validation failures.

## Non-goals

- Do not implement parent `continue SKILL-XX` resolution.
- Do not implement per-subtask workflow state persistence beyond fields needed to write the manifest.
- Do not implement branch checkout, commits, stacked branch advancement, or subtask status transitions.
- Do not require every feature to be decomposed.
- Do not replace the existing feature-implement workflow state contract wholesale.

## Dependencies

- No subtask dependency. This is the first subtask and establishes the contract consumed by later runtime work.

## Validation Strategy

Run `bill-quality-check`. At minimum, the implementation should add or update contract parity tests, schema violation tests, manifest creation tests, and a single-spec regression proving non-decomposed workflows do not require the new manifest.

## Recommended Next Prompt

Run bill-feature-implement on .feature-specs/SKILL-51-decomposition-workflow-state/spec_subtask_1_foundation.md.
