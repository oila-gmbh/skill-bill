# SKILL-131 Subtask 2: Runtime Repair and Reconciliation

## Scope

Make audit-gap implementation exhaust the persisted plan in dependency order, validate exact result coverage, reconcile recurring and new gaps, and drive every gate from one normalized phase-output object.

## Acceptance Criteria

1. Audit-gap remediation receives the immutable initial preplan and plan, current worktree, and complete persisted repair plan without regenerating or narrowing planning context.
2. One implementation invocation attempts every runnable repair item in dependency order and returns exactly one terminal `fixed` or `already_satisfied` result with concrete repository and verification evidence for each carried identifier.
3. Missing, deferred, pending, unattempted, unresolved, or mismatched repair results cannot complete or advance; genuinely unresolvable work blocks with durable resumable identifiers.
4. Review and re-audit cannot erase or implicitly satisfy carried repair items, and later phases cannot be named as substitutes for accepted repair work.
5. The next audit classifies each prior gap as resolved or recurring with stable identity and evidence, assigns fresh identities only to genuinely new gaps, and blocks non-progressing equivalent gap sets.
6. Phase output is normalized once; validation, verdict selection, persistence, transition selection, and handoff consume that same object for bare JSON, fenced JSON, Markdown-prefixed JSON, and trailing prose.
7. Standalone and goal-child execution share the same repair, resume, recurrence, review-composition, and canonical-output behavior.

## Non-Goals

- Weakening acceptance criteria or trusting remediation claims without audit verification.
- Combining audit reads and repository mutation in one phase.
- Applying an arbitrary iteration cap that skips correctness.

## Dependency Notes

Depends on subtask 1's schemas and durable repair-plan model.

## Validation Strategy

Run focused application and domain tests for exhaustive result matching, crash/resume seams, canonical parsing, backward transitions, recurrence, non-progress, standalone execution, and goal-child parity.

## Next Path

Continue with subtask 3 for regression fixtures, governed guidance, telemetry, and full validation.
