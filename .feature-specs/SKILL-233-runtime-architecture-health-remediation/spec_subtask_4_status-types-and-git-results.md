# SKILL-233 · Subtask 4: Closed status types and Git results

## Scope

Replace closed status, mode, kind, phase, and outcome fields in domain and ports with owning enums or sealed types. Migrate `WorkflowGitOperationResult` and all consumers while preserving wire values and behavior.

## Acceptance Criteria

1. Every closed status-family field in `runtime-domain` and `runtime-ports` is an enum or sealed type with one `wireValue` and one `fromWire`; genuinely open fields are inventoried with a reason.
2. `WorkflowGitOperationResult` is a sealed `Ok`/`Failed` result and no consumer reads `.status` or `.ok`.
3. Application status-family comparisons use typed exhaustive branches; no raw literal comparisons remain outside inventoried open cases.
4. All Git operation implementations, mappers, records-nothing-to-commit paths, callers, and tests preserve existing values and error semantics.

## Non-Goals

- Do not centralize wire tokens or payload keys here.
- Do not type open provider-authored fields without an inventory decision.
- Do not change database context plumbing or package placement.

## Dependency Notes

Depends on subtask 3 for final identifier signatures. The vocabulary slice consumes the resulting enum wire values.

## Validation Strategy

Run status architecture and literal-comparison scanners, Git operation tests, exhaustive-branch compilation, and wire round-trip tests for every retained alias.

## Next Path

`skill-bill goal SKILL-233`
