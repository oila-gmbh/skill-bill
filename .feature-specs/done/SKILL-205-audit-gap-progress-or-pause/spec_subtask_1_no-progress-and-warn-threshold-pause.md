# Subtask 1 — No-progress and warn-threshold pause for `audit_gap`

## Scope

Stop unbounded `audit_gap` thrash at the transition seam. When the unmet
criterion set makes no progress across consecutive audit rounds, or when the
loop would enter iteration `warnAfterIterations + 1`, pause for an operator
decision instead of launching another implement session. Wire resume through
the existing operator-decision grant so one explicit retry can continue, then
re-apply the same gates.

Do not yet build the prior-gap memory projection (subtask 2). This subtask may
persist the minimum durable fields needed to compare consecutive unmet sets
(criterion identities from the last completed audit).

## Acceptance Criteria

1. Before recording a live `audit_gap` edge that re-enters implement, the
   runtime compares the new unmet criterion identities to the previous audit
   round's set. No progress (set did not shrink; sticky ids uncleared per the
   parent progress definition) yields a durable pause / needs-user-action
   block naming no-progress, not another implement launch.
2. Crossing `warnAfterIterations` (entering iteration 4 when the threshold is
   3) pauses for an operator decision. The crossing is no longer advisory-only
   control flow; an advisory message may still emit.
3. An operator `retry_fix` (or the documented grant for this pause class)
   allows exactly one further `audit_gap` remediation attempt; a second
   no-progress or threshold condition pauses again.
4. Status and blocked reasons distinguish no-progress pause and warn-threshold
   pause from output-gate / schema failures.
5. Tests cover continue-on-shrink, pause-on-no-progress, pause-on-threshold,
   and single-grant retry. Existing unbounded-edge regression tests that
   required silent continue past iteration 3 are updated to expect pause.

## Non-Goals

- Prior-gap memory briefing / audit re-justification prompts (subtask 2).
- Changing review_fix non-convergence.
- Hard `perEdgeCap` without operator grant.

## Dependency Notes

None. Lands the kill-switch first so thrash stops even before memory lands.

## Validation Strategy

Unit/transition tests around the `audit_gap` edge and pause reasons; update
`UnboundedRemediationLoopRegressionTest` / loop-warning tests for the new
control flow. Full `./gradlew check --continue` before commit.

## Next Path

Subtask 2 adds durable gap memory into continuing audit and implement
briefings so retries that are granted are better grounded.
