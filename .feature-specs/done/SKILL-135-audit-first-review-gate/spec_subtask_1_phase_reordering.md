# SKILL-135 Subtask 1: Audit-First Phase Reordering

## Scope

Reorder the feature-task phase workflow so audit runs to a satisfied verdict before review begins, remove review's backward edge into audit, and preserve audit's existing gap-repair cycle unchanged.

## Acceptance Criteria

1. The phase workflow definition orders implementation, audit, review, and validation, with audit reaching a terminal satisfied verdict before review is reachable.
2. The transition function rejects any path that enters review before audit is satisfied, failing loudly through a typed runtime-consistent error rather than silently advancing.
3. Review has no backward edge into audit: a review verdict, a review fix pass, and review pass exhaustion each advance toward validation or block, and none reopens an audit repair plan.
4. Audit retains its existing repair-plan contract, stable identifiers, reconciliation, cumulative unresolved-gap ledger, and non-progress detection. Each audit verifies only the acceptance criteria not yet durably marked satisfied; a criterion that reached a satisfied verdict is durably closed and is not re-verified by a later audit-gap iteration. Because review no longer sits inside the reopened `[implement, audit]` span, non-progress detection becomes the only bound on the uncapped cycle: it must treat an unknown or uncomputable repository fingerprint as unproven progress and block an equivalent recurring gap set, rather than disarming and looping forever.
5. Standalone feature-task runs and goal-child runs resolve the same phase order, and a resume from any durable step lands on the reordered graph rather than a stale sequence.
6. Contract-version, transition-function, and resume tests cover acceptance and rejection paths for the reordered graph.

## Non-Goals

- Changing review pass counts, review mode selection, or finding disposition.
- Changing audit's internal gap-repair behavior. Narrowing audit's verification scope to the not-yet-satisfied criteria is in scope; changing how it diagnoses or repairs a gap it does report is not.
- Adding or changing persistence for review findings.

## Dependency Notes

No dependencies. This subtask establishes the phase graph that subtasks 2 and 3 build on.

## Validation Strategy

- Transition-function acceptance and rejection tests for the reordered graph.
- Resume tests from each durable step proving no run lands on a stale ordering.
- Audit-scope tests proving a satisfied criterion is durably closed, that a later audit-gap iteration does not re-verify it, and that a repeatedly-disagreeing audit terminates because the unsatisfied set shrinks rather than because review exhausted a budget.
- Standalone and goal-child parity tests for phase order.
- Focused Gradle tests during implementation.

## Next Path

Continue with subtask 2 after this subtask commits.
