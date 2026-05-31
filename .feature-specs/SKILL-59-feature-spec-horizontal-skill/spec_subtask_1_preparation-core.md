---
status: Complete
---

# SKILL-59 Subtask 1 - Shared Preparation Core

Parent spec: [.feature-specs/SKILL-59-feature-spec-horizontal-skill/spec.md](./spec.md)
Issue key: SKILL-59

## Scope

Create the runtime/domain foundation for one shared feature-spec preparation path that can be invoked by `bill-feature-spec`, `bill-feature-implement`, and `bill-goal` orchestration without duplicating decomposition logic. Define typed input/output models, intake validation, issue-key loud-fail behavior, and classification into `single_spec` or `decomposed`.

The implementation should follow the existing runtime layering: domain models and errors in `runtime-domain`, application orchestration in `runtime-application`, ports in `runtime-ports`, and file/system adapters only where needed later.

## Acceptance Criteria

1. Intake requires issue key, outcome, criteria, and constraints.
2. Missing issue key loud-fails through a typed error rather than producing a placeholder.
3. Preparation classifies requests into `single_spec` or `decomposed`.
4. The shared preparation API is reusable by `bill-feature-implement` and `bill-goal` without copying decomposition planning logic.
5. Regression coverage proves both future callers exercise the same core preparation path.

## Non-Goals

- Do not add a platform-specific skill or platform-pack entry.
- Do not write subtask spec files or decomposition manifests in this subtask except through narrow test fakes.
- Do not change the decomposition manifest schema unless implementation proves it is unavoidable.
- Do not change `skill-bill goal` CLI behavior in this subtask.

## Dependencies

This is the first subtask and has no dependencies.

## Validation Strategy

Run `bill-quality-check`. At minimum, include focused runtime-domain/application tests for validation errors, required intake fields, classification, and the shared-call-path invariant.

## Next Path

After this subtask completes and is committed, run `bill-feature-implement` on `.feature-specs/SKILL-59-feature-spec-horizontal-skill/spec_subtask_2_spec-writing-runtime.md`.

