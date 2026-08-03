# SKILL-156 Subtask 1 - Blocker-Driven Unbounded Review Remediation

## Scope

Retire the fixed cap on the `review_fix` remediation loop and make unresolved
Blocker presence the loop condition.

In scope:

- `FeatureTaskRuntimePhaseWorkflowDefinition.transitions`: the `review_fix`
  backward edge no longer declares a finite `perEdgeCap`. Express the loop
  condition declaratively on the edge so `FeatureTaskRuntimeTransitionFunction`
  stays free of review-specific branching — a Blocker-conditional re-entry
  predicate carried by the edge declaration, evaluated against the existing
  `FeatureTaskRuntimeTransitionContext.unresolvedBlockerPresent`.
- `FeatureTaskRuntimeTransitionFunction`: re-enter while the edge's loop condition
  holds; take the forward transition when it does not. `ADVANCE`,
  `ADVANCE_UNLESS_UNRESOLVED_BLOCKER`, and `BLOCK` exhaustion behaviors remain for
  the still-bounded regeneration edges.
- `GoalSubtaskReviewState`: replace `GOAL_SUBTASK_REVIEW_MAX_PASSES` as a hard bound
  with an unbounded pass counter. `completedPassCount`, `reservedPassNumber`,
  `reserveNextPass`, and `completeReservedPass` accept any pass number `>= 1`,
  keeping ordering, contiguity, and one-disposition-per-Blocker invariants.
  `REVIEW_CAP_REACHED` is no longer produced by pass-count exhaustion; keep the
  enum value readable for legacy records only if the contract migration requires it.
- `FeatureTaskRuntimeReviewPassSequence`: `resolveForPass` accepts any pass number
  `>= 1`. Pass one keeps its resolved tier; passes two and beyond resolve to the
  inline light tier with a deciding rule naming the remediation-pass rule.
  `passes(pinnedMode)` returns the pass-one tier plus the repeating remediation tier
  rather than a fixed two-element list.
- `FeatureTaskRuntimeRunLoop`: `settleExhaustedReviewSequence` no longer settles on
  pass count. The unresolved-Blocker path re-enters the edge; the
  no-unresolved-Blocker path advances with remaining findings written to the
  unaddressed-findings ledger, unchanged.
- `FeatureTaskRuntimeOperatorRetryGrant` and the `effectiveEdgeIterationCount`
  discount: the grant no longer needs to buy an iteration past a cap. Keep the
  operator decision surface intact for the Subtask 2 stall release and remove only
  the cap-discount mechanics that no longer have a cap to discount.
- `goal-subtask-review-state-schema.yaml`: contract-version bump for the widened
  pass fields, with loud-fail on legacy records and in-band quarantine-and-regenerate,
  plus the Kotlin `*_CONTRACT_VERSION` constant and its parity test.
- `priorBlockerFindingIds` mints dispositions against the immediately preceding
  pass's Blockers for every remediation pass, not only pass two.

## Acceptance Criteria

1. The `review_fix` backward edge declares no finite iteration cap and re-enters
   `implement_fix` whenever an unresolved Blocker disposition or a newly raised
   Blocker is present, at any iteration count.
2. With no unresolved Blocker after a remediation pass, the run advances to
   `validate` and every remaining Major, Minor, and Nit finding lands in the
   unaddressed-findings ledger.
3. `GoalSubtaskReviewState` accepts and round-trips pass numbers beyond two while
   preserving pass ordering, contiguity, reserved-pass, and
   one-disposition-per-Blocker invariants.
4. Review pass one resolves to its existing tier; every pass from two onward
   resolves to the inline light tier scoped to the remediation delta, with a
   recorded deciding rule.
5. Each remediation pass dispositions the Blockers of its immediately preceding
   pass, keyed by that pass's own finding identifiers.
6. `FeatureTaskRuntimeTransitionFunction` carries no review-specific branching; the
   loop condition is read from the backward-edge declaration.
7. The bounded regeneration edges (`regenerate_preplan`, `regenerate_plan`,
   `regenerate_implement`) keep `MAX_RECORD_REGENERATION_ATTEMPTS` and their `BLOCK`
   exhaustion behavior unchanged.
8. The review-state schema contract version is bumped, legacy records loud-fail at
   the read seam and are quarantined and regenerated in-band, and a parity test pins
   the schema const to the Kotlin constant.
9. A crash or parent resume mid-loop restores the loop iteration count, reserved
   pass, and Blocker dispositions with no re-reserved consumed pass and no
   double-applied mutation.

## Non-Goals

- The stall guard (Subtask 2) and the warning threshold (Subtask 3).
- Governed prose updates (Subtask 4).
- Any change to `FeatureTaskRuntimeFixLoopPolicy` or the audit backward edge, which
  is already uncapped.

## Dependency Notes

None. This unit lands first and defines the loop-accounting shape Subtasks 2 and 3
build on.

## Validation Strategy

- Domain unit tests: transition function re-entry at iterations 1, 2, 5, and 20 with
  an unresolved Blocker; forward advance at each of those iterations once no Blocker
  is unresolved; unchanged behavior for every bounded edge.
- `GoalSubtaskReviewState` tests for pass numbers beyond two, reserved-pass rules,
  disposition uniqueness, and legacy-record rejection.
- `FeatureTaskRuntimeReviewPassSequence` tests for pass-one tier, pass 2..N inline
  tier, and deciding-rule strings.
- Run-loop tests: a three-round and a six-round Blocker remediation sequence reaching
  `validate` on the first clean pass; ledger contents for non-Blocker findings.
- Crash/resume regression covering mid-`implement_fix` and mid-re-`review` death at
  an iteration above the old cap.
- `(cd runtime-kotlin && ./gradlew check)`.

## Next Path

Subtask 2 — no-progress stall guard.
