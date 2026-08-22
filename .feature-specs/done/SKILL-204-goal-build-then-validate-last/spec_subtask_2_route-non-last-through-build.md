# Subtask 2 — Route non-last goal children through `build`

## Scope

Stamp quality-gate selection on goal-continuation launches and advance the
child loop accordingly: non-last non-skipped children run `build` instead of
`validate`; the last non-skipped child (and every non-goal / single-subtask
launch) keeps `validate`. Teach `write_history` and `commit_push` to accept
either a settled `build_receipt` or a settled `validation_receipt`.

## Acceptance Criteria

1. `GoalRunner` (or the continuation stamp it owns) sets selection to
   `validate` iff the subtask is the last non-skipped entry in manifest array
   order; otherwise `build`. Skipped ordinal-last promotes `validate` to the
   previous last non-skipped entry. Single-subtask goals always `validate`.
2. A child stamped `build` transitions `review` (approved) → `build` →
   `write_history` and never enters `validate` on that visit. A child stamped
   `validate` keeps today's `review` → `validate` → `write_history` path.
3. `write_history` and `commit_push` require exactly one settled quality-gate
   receipt for the child's selected phase (`build_receipt` or
   `validation_receipt`) and loud-fail if the wrong one is present or both are
   missing.
4. Selection survives CLI launch and resume on the goal-continuation artifact;
   legacy rows without the field resolve to `validate`.
5. Goal status/watch surfaces `build` when that phase is current.
6. Tests cover three-child `build`/`build`/`validate`, single-child
   `validate`, last-skipped promotion, and resume round-trip of the stamp.

## Non-Goals

- Redefining the build pack command or validate collect-all semantics (subtask 1).
- Parent `finalizeGoal` re-validation.
- Operator UX beyond status naming the active phase.

## Dependency Notes

Depends on subtask 1: the `build` phase, pack command, and `build_receipt`
must exist before routing selects them.

## Validation Strategy

GoalRunner routing tests; continuation adoption/persistence tests; handoff
projection tests for either-or receipts; one integration-style child-path
assertion that a `build`-stamped run does not schedule `validate`. Full
`./gradlew check --continue` before commit.

## Next Path

Feature complete after this subtask's commit; no further subtasks.
