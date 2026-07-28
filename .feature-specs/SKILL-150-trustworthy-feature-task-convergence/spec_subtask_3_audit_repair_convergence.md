# SKILL-150 Subtask 3: Audit Repair Convergence

## Scope

Make completeness-audit repair batches durable, closure-complete, and recurrence-aware. An audit may clear only after it dispositions every carried production gap against repository evidence, while repair re-entry receives every still-open repair item exactly once.

## Acceptance Criteria

1. The initial completeness audit inspects every acceptance criterion and persists one generation containing its repository checkpoint, satisfied criteria, gaps, closure-complete repair batch, and bounded evidence references.
2. Gap and repair-item identities remain stable across repair and re-audit generations, with explicit `new`, `recurring`, `resolved`, `superseded`, and still-open state transitions.
3. Audit history is append-only: accepting a new plan or repository checkpoint never discards earlier gap text, repair results, recurrence, or the checkpoint at which a decision was made.
4. Implementation re-entry receives the complete ordered set of unresolved repair items, their dependencies, prior result evidence, and non-regression constraints from the durable authority.
5. A repair implementation cannot report `completed` until every carried repair item has one terminal fixed, already-satisfied, or governed superseded disposition; partial repair remains resumable.
6. Follow-up audit must reverify every carried gap and inspect the repair batch's production blast radius for newly introduced gaps before it can emit `satisfied`.
7. A recurring gap increments durable recurrence for the same identity and cannot be counted as resolved merely because a later snapshot replaced the earlier audit plan.
8. Audit first-pass convergence, new-gap count, recurring-gap count, attempted repair items, resolved repair items, and audit-loop count are derived from durable generations and agree with the phase ledger.
9. Completeness audit continues to exclude test adequacy, coverage, fixtures, and assertions as audit gaps; validation owns test execution and failures.
10. Crash and resume at audit-plan persistence, repair-result persistence, and follow-up disposition seams preserve exactly one active repair batch and the complete prior history.

## Non-Goals

- Weakening full-per-criterion audit.
- Reporting test-only concerns as completeness gaps.
- Guaranteeing a faulty repair implementation passes its first follow-up audit.
- Storing full repository diffs or raw phase output in audit history.

## Dependency Notes

Depends on Subtask 1 for durable generations and Subtask 2 for truthful mutating-phase completion.

## Validation Strategy

- Reproduce two repairs that claim to fix exact-byte and canonical-schema gaps while leaving the original defects; assert both gaps recur under the same identities and remain open.
- Apply a correct closure-complete repair and assert one follow-up audit resolves the carried batch without creating a second repair generation.
- Test new gaps, recurring gaps, superseded repairs, multiple criteria, dependencies, and non-regression constraints.
- Compare telemetry counters with the append-only rows and phase ledger.
- Exercise standalone, goal-child, crash, resume, and operator-block scenarios.

## Next Path

Continue with Subtask 4 to preserve the same evidence and disposition guarantees through code-review generations.

