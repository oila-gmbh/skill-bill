# SKILL-137 Subtask 4 - Validation, History, Commit, and PR Projections

Parent spec: [.feature-specs/SKILL-137-phase-context-minimization/spec.md](./spec.md)
Issue key: SKILL-137

## Scope

Replace broad downstream artifacts in the finalization sequence with bounded validation, change, history, commit, and PR contracts. Move prior-gate assurance into runtime-owned preconditions, keep telemetry out of domain receipts, and ensure each phase derives authoritative repository evidence at an exact checkpoint.

## Acceptance Criteria

1. `validate` receives a validation request containing resolved validation strategy, exact changed-path scope/checkpoint, required check categories, goal-continuation restrictions, and applicable quality-check add-ons.
2. Validation does not receive complete implementation or audit outputs, implementation narration, audit reasoning, review findings, preplanning context, or unrelated acceptance criteria.
3. Validation independently inspects the repository, fixes allowed validation failures, and emits a typed receipt containing status, validated checkpoint, checks executed with compact outcomes, routed checker/stack/fallback identity, changed paths from validation fixes, and unresolved items.
4. Validation telemetry and progress-write failures are written to telemetry/progress storage and are absent from the validation receipt delivered downstream.
5. `write_history` receives a change receipt, compact validation receipt, diff-derived boundary candidates, issue/feature identity, and the boundary-history policy required to decide write versus skip.
6. History does not receive complete implementation/validation responses, raw tests/logs, review/audit reports, review policy, feature size, or unrelated acceptance criteria.
7. History emits a compact receipt containing disposition, affected history paths, associated boundary ids, and repository checkpoint without embedding written history bodies.
8. `commit_push` receives explicit normalized path inventory, required local-spec inclusion or Linear-spec exclusion, branch/base identity, commit policy, goal-continuation PR suppression, and runtime-owned gate attestations/checkpoint.
9. Commit/push does not receive complete implementation, audit, review, validation, or history phase outputs. The runtime refuses phase entry unless required gates are complete and receipts/checkpoints remain current.
10. The commit agent stages only the explicit inventory plus declared generated-at-phase inclusions; an undeclared new/removed path or stale inventory fails or refreshes through an explicit policy instead of being silently omitted or swept by `git add -A`/`git add .`.
11. Commit/push emits a compact receipt containing commit SHA, branch, pushed status, committed path digest/count, and committed repository checkpoint.
12. `pr` receives acceptance criteria, change receipt, validation summary, commit receipt, issue/feature and branch/base identity, exact authoritative diff reference, PR-template policy, and applicable PR-description instructions.
13. PR does not receive raw implementation, audit, review, validation, history, commit, telemetry, progress, or tool-output bodies.
14. PR creation/reuse emits a compact PR receipt containing URL/number, title, created-versus-reused disposition, branch/head SHA, and body digest rather than the complete PR body as downstream context.
15. A change receipt is derived from implementation/validation receipts and current diff with changed paths, concise behavior summary, criterion mapping, test summary, deviations, and unresolved items under explicit budgets.
16. Runtime launch, retry, and resume use the same finalization projections and reject stale checkpoints before any commit, push, or PR external mutation.
17. Linear/local spec inclusion behavior, goal-child PR suppression, explicit staging, history write/skip policy, and idempotent PR reuse remain unchanged.
18. Tests assert exact required/forbidden fields, telemetry separation, staging inventory safety, checkpoint staleness, commit/PR idempotency, and fresh/resumed parity.

## Non-Goals

- Changing repository validation commands or platform checker routing.
- Changing boundary-history value rules or entry format.
- Changing commit message or PR template policy beyond input minimization.
- Moving external commit, push, or PR authorization boundaries.

## Dependency Notes

Depends on: 2, 3.

Consumes the implementation/change facts from subtask 2 and the settled audit/review gate model from subtask 3.

## Validation Strategy

- Validation request/receipt and telemetry-separation tests.
- Boundary-history projection snapshots and write/skip tests.
- Explicit staging inventory acceptance/rejection tests, including removed and newly created paths.
- Commit and PR stale-checkpoint/idempotency tests.
- Negative prompt assertions for all prior complete phase outputs.
- Standalone and goal-child finalization tests.

## Next Path

Continue with subtask 5 after this subtask commits.

## Spec Path

.feature-specs/SKILL-137-phase-context-minimization/spec_subtask_4_validation-history-commit-and-pr-projections.md
