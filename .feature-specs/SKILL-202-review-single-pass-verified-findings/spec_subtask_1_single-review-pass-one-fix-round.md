# SKILL-202 · Subtask 1 — One review pass, one bounded fix round

Parent: `spec.md`

## Scope

Collapse the review remediation loop. Review settles once, a single fix round
reconciles its findings at any severity, and the run advances to `validate`.
Verification arrives in subtask 2; until then every finding from the one pass
feeds the one round.

Files in play:

- `runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/workflow/taskruntime/FeatureTaskRuntimePhaseWorkflowDefinition.kt`
  — `stepIds`, `stepLabels`, the `review_fix` edge, `REVIEW_FIX_LOOP_ID`,
  `PHASE_PLAN_FIX`, `loopOnlyPhaseIds`, `loopOnlySuccessors`, and the topology
  KDoc that documents the removed span.
- `runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/workflow/taskruntime/model/GoalSubtaskReviewState.kt`
  — `blocksAdvance` and its callers.
- `runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/workflow/taskruntime/model/FeatureTaskRuntimeReviewVerdict.kt`
  — `verdict`, `remediationFindings`, `unresolvedFindings`.
- `runtime-kotlin/runtime-application/src/main/kotlin/skillbill/application/featuretask/FeatureTaskRuntimeRunLoop.kt`
  — Blocker counting at `:2102` and `:2394`, the loop warning, and the
  unresolved-Blocker pause wiring.
- `runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/workflow/taskruntime/FeatureTaskRuntimeTransitionFunction.kt`
  and `.../model/FeatureTaskRuntimeTransitionModels.kt` — the
  `ADVANCE_UNLESS_UNRESOLVED_BLOCKER` behaviour and `TerminalPause`, if this
  change leaves them with no declared caller.

## Preferred Approach

Reorder `stepIds` to `preplan, plan, implement, audit, review, implement_fix,
validate, write_history, commit_push, pr` and delete `PHASE_PLAN_FIX`. Keep
`implement_fix` in `loopOnlyPhaseIds` so the clean path runs
`review -> validate`; its position immediately before `validate` makes the
round's forward edge land on `validate` through the existing
`forwardTransition` skip rule, with no new branch.

Replace the `review_fix` edge with one edge from `review` to `implement_fix`
carrying `perEdgeCap = 1`, `capExhaustionBehavior = ADVANCE`,
`capScope = PER_SUBTASK`, and no `warnAfterIterations`. Subtask 2 moves this
edge's source to `verify_findings`; keep it shaped so that move is a one-line
change.

Stop deriving control flow from severity. `FeatureTaskRuntimeReviewVerdict`
should settle a verdict that means "findings exist" rather than "Blocker or Major
exist", so a pass reporting only Nits still runs the round. Severity survives on
the finding for reporting and for the ledger.

Remove the unresolved-Blocker pause on this path rather than leaving it
unreachable. If `ADVANCE_UNLESS_UNRESOLVED_BLOCKER` and `TerminalPause` end up
with no declared caller, remove them and their tests in the same commit; a
reachable-behaviour enum member no declaration names is dead topology.

## Acceptance Criteria

1. `review` declares no backward edge, and no run path re-enters it after it
   settles.
2. A review pass reporting at least one finding of any severity, Nit included,
   runs exactly one `implement_fix` round and then advances to `validate`.
3. A review pass reporting no findings advances straight to `validate` without
   entering `implement_fix`.
4. The fix round runs at most once per subtask across resumes, enforced by
   `perEdgeCap = 1` with `PER_SUBTASK` scope.
5. No severity value blocks advancement, and no unresolved-finding pause is
   reachable from the review path.
6. `plan_fix` no longer exists in the phase set, the labels, the topology, or the
   prompt directives.
7. The `audit_gap` loop and the three `record_rejected` regeneration edges keep
   their current behaviour, caps, and tests.
8. `review_fix` accounting that can no longer be produced is removed from the
   run loop and from status projection, or retained only as a decode path for
   existing durable records with a comment saying so.
9. Durable records written by the previous topology stay decodable; a record whose
   shape this change invalidates loud-fails with a named error.
10. Transition-function tests cover: findings present, findings absent, cap
    already consumed on resume, and the entry gate that keeps `review` behind a
    satisfied `audit`.
11. The repository validation gate passes.

## Non-Goals

- Adding the verification phase or any per-finding disposition.
- Reading boundary memory.
- Changing which findings a review pass produces, or the review packet contract.

## Dependencies

None. This subtask lands first.

## Validation Strategy

Unit tests over `FeatureTaskRuntimeTransitionFunction` with the new declaration,
asserting the four transition cases above plus the entry gate violation. Run the
runtime-owned validation gate.

## Next Path

`spec_subtask_2_verify-findings-against-intent.md`
