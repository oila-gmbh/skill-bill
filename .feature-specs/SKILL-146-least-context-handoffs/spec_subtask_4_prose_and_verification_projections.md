# SKILL-146 Subtask 4: Prose continuation and feature-verification least-context parity

## Scope

Apply the runtime receipt/projection semantics to prose feature-task and `bill-feature-verify`. Correct prose order to `implement -> audit -> review -> validate`, remove `review_result` from audit dependencies, and make fresh and resumed continuation use the same current-step projection without retrieving the full artifact map. Keep verification evaluators independent and consolidate compact typed receipts.

## Acceptance Criteria

1. Parent AC 2 and 5 keep raw artifacts out of prose/evaluator prompts and use authoritative checkpoint-scoped repository evidence.
2. Parent AC 18 corrects prose ordering and dependency semantics.
3. Parent AC 19 gives fresh and resumed prose launches the same projection while private diagnostics remain explicit operator actions.
4. Parent AC 20 keeps telemetry outside prose and evaluator domain results.
5. Parent AC 21 keeps code review, unit-test value, completeness, and applicable feature-flag evaluators independent and consolidates only typed receipts.
6. Parent AC 22, 25, and 26 enforce budgets, prove required/forbidden fields, and align governed `content.md`, continuation surfaces, fixtures, and goldens.

## Non-Goals

- Combining verification evaluators or changing flag-audit applicability.
- Removing criteria from consumers that need them.
- Removing operator diagnostics or editing generated wrappers/support pointers.

## Dependency Notes

Depends on Subtask 2 and coordinates shared receipt shapes with Subtask 3. May run alongside Subtasks 3 and 5.

## Validation Strategy

- Prose order and stale-definition rejection tests.
- Fresh versus continuation projection parity and full-artifact-map absence tests.
- Explicit operator diagnostic path tests.
- Evaluator independence, sibling-output absence, and consolidated receipt tests.
- Prompt snapshots, UTF-8 budgets, governed source validation, and render previews without generated commits.

## Next Path

Proceed to Subtask 6 after Subtasks 3, 4, and 5 are complete.

