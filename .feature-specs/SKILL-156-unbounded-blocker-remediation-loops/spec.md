# SKILL-156 - Unbounded Blocker Remediation Loops With A Warning Threshold

## Mode

decomposed

Four dependency-ordered units:

1. Blocker-driven unbounded review remediation — retire the fixed review pass/edge
   cap; an unresolved Blocker always earns another `implement_fix` → `review` round.
2. No-progress stall guard — the loop terminates on evidence of non-convergence,
   not on an iteration count, for both `review_fix` and `audit_gap`.
3. Loop warning threshold — past three iterations of either remediation loop the
   run warns the operator on every further iteration and keeps going.
4. Governed prose and contract parity — runtime and prose skill content, review
   state schema version, and status/telemetry surfaces describe the same policy.

Unit 2 and Unit 3 both depend on Unit 1's loop-accounting shape. Unit 4 depends on
1–3 landing.

## Intended Outcome

A Blocker is never left in the tree because a counter ran out. The audit and review
remediation loops run as many rounds as it takes to clear Blockers, terminate on
convergence evidence rather than a cap, and tell the operator loudly when a loop is
running long without silently stopping the work.

- An unresolved Blocker from `review` always reopens `implement_fix` and re-reviews,
  regardless of how many rounds have already run.
- An `audit` `gaps_found` verdict keeps reopening `implement` — the behavior it
  already declares (`perEdgeCap = null`) — and is held to the same guard and
  warning policy as review.
- Non-Blocker findings are unchanged: Major, Minor, and Nit never reopen a loop and
  continue to land in the unaddressed-findings ledger.
- A loop that stops making progress stops loudly, naming the unresolved Blocker or
  gap identifiers, instead of spinning.
- Crossing three iterations on either loop emits an operator-visible warning and
  the run continues to completion.

## Problem

### 1. The review remediation loop is capped at one fix round

`FeatureTaskRuntimePhaseWorkflowDefinition.transitions` declares the `review_fix`
backward edge with `perEdgeCap = 1` and
`capExhaustionBehavior = ADVANCE_UNLESS_UNRESOLVED_BLOCKER`
(`FeatureTaskRuntimePhaseWorkflowDefinition.kt:547-555`). `GOAL_SUBTASK_REVIEW_MAX_PASSES = 2`
(`GoalSubtaskReviewState.kt:13`) hard-bounds the review pass sequence alongside it.

The consequence: with a Blocker still unresolved after the single reserved fix
round, the subtask cannot fix it. It either

- pauses resumably for a bounded operator decision — `retry_fix`,
  `accept_and_advance`, `abandon_subtask` (`FeatureTaskRuntimeRunLoop.kt:1263`,
  `GoalSubtaskReviewDisposition.PAUSED`), or
- blocks on cap exhaustion when the pass carried no dispositions
  (`GoalSubtaskReviewDisposition.REVIEW_CAP_REACHED`,
  `GoalSubtaskReviewState.kt:457`).

Both outcomes stop autonomous progress on work the runtime is capable of finishing.
`retry_fix` exists precisely because the cap is wrong for Blockers: the operator's
answer is almost always "keep fixing", and the grant machinery
(`FeatureTaskRuntimeOperatorRetryGrant`, the `operatorRetryRounds` discount in
`effectiveEdgeIterationCount`) is a manual workaround for a policy that should not
have bounded Blockers in the first place.

### 2. Cap exhaustion and non-convergence are conflated

Today the cap doubles as the anti-spin guard. Removing it for Blockers therefore
has to introduce the guard the cap was standing in for. Audit already has the right
shape in prose — "When an audit returns the same unresolved gap set with no
repository change and no newly resolved repair item, stop loudly"
(`skills/bill-feature-task-prose/content.md:361`) — but review has no equivalent,
and the runtime-side enforcement of the audit rule is not asserted as a shared,
loop-agnostic policy.

### 3. A long-running loop is invisible until it ends

There is no operator signal between "iteration 1" and a terminal outcome. A loop on
its seventh round looks identical to a loop on its first in normal output.

### 4. The governed prose states the old policy

`skills/bill-feature-task-prose/content.md:301,320,328` state a hard two-pass review
budget ("Never start pass three"), `:1209` ties `abandoned_at_review` to that budget,
`skills/bill-feature-task-runtime/content.md:245-253` states the one-iteration cap,
and `skills/bill-feature-task-subtask-runner/content.md:56` references the two-pass
Blocker cap. Prose and runtime must state one policy.

## Approach

Replace "cap-terminated" with "evidence-terminated" on both remediation loops:

- **Blocker presence is the loop condition.** `review_fix` re-enters while any prior
  Blocker disposition is `unresolved` or the latest pass raised a new Blocker. With
  no unresolved Blocker, the loop closes and the run advances to `validate` exactly
  as it does today.
- **Convergence is the terminating evidence.** A round that produces the identical
  unresolved Blocker set with no repository change and no newly resolved disposition
  is non-convergent: stop loudly and resumably, reusing the existing operator
  decision surface.
- **Depth stays cheap.** Pass one keeps its resolved tier; every remediation pass
  from two onward runs the inline light tier scoped to the remediation delta since
  the pre-fix checkpoint, so an unbounded loop stays affordable.
- **Warning, not stop.** `REMEDIATION_LOOP_WARNING_THRESHOLD = 3` is declared once
  per loop and drives an operator-visible warning on every iteration past it, with
  no effect on control flow.

## Acceptance Criteria

1. An unresolved Blocker from `review` reopens `implement_fix` and re-runs `review`
   at any iteration count, with no fixed per-edge or per-pass cap terminating the
   loop.
2. An `audit` `gaps_found` verdict reopens `implement` at any iteration count, and
   audit and review share one loop-accounting, guard, and warning policy.
3. A remediation round whose re-review or re-audit returns the identical unresolved
   Blocker/gap identifier set with no repository change and no newly resolved item
   stops loudly and resumably, naming those identifiers.
4. Entering iteration four or higher of `review_fix` or `audit_gap` emits an
   operator-visible warning naming the loop id, subtask, and iteration count, and
   the run continues without any control-flow change.
5. Non-Blocker findings never reopen a remediation loop and continue to land in the
   unaddressed-findings ledger.
6. Review pass one keeps its resolved tier; every pass from two onward resolves to
   the inline light tier scoped to the remediation delta.
7. Durable state, resume, and crash recovery preserve the loop iteration count,
   Blocker dispositions, warning state, and convergence fingerprint across process
   death and parent resume, with no double-applied mutation and no repeated warning
   for an already-warned iteration.
8. Governed prose in `bill-feature-task-runtime`, `bill-feature-task-prose`, and
   `bill-feature-task-subtask-runner` states the unbounded-on-Blocker policy, the
   stall guard, and the warning threshold, and parity tests bind that prose to the
   runtime constants.
9. `skill-bill validate`, `(cd runtime-kotlin && ./gradlew check)`,
   `npx --yes agnix --strict .`, and `scripts/validate_agent_configs` pass.

## Non-Goals

- Changing `FeatureTaskRuntimeFixLoopPolicy.MAX_FIX_LOOP_ITERATIONS` or
  `MAX_FORMAT_RETRY_ATTEMPTS`. Those bound schema-gate and malformed-output repair,
  not semantic remediation, and stay at 3.
- Changing `MAX_RECORD_REGENERATION_ATTEMPTS` or the quarantine-and-regenerate
  edges. A rejected durable record is a producer defect, not a Blocker finding.
- Changing severity calibration, what counts as a Blocker, or the audit gap
  definition.
- Changing the `review_base_sha` baseline or pass-one review scope.
- Loop-cap changes in standalone `bill-code-review` or `bill-feature-verify`.

## Constraints

- The operator decision surface (`retry_fix`, `accept_and_advance`,
  `abandon_subtask`) stays available; it becomes the release for a stall, not for a
  cap.
- `GoalSubtaskReviewState` invariants encode the pass bound in several places
  (`completedPassCount in 0..2`, reserved-pass rules, `review_cap_reached`
  preconditions). Widening them requires a `goal-subtask-review-state-schema.yaml`
  contract-version bump with loud-fail plus in-band quarantine-and-regenerate for
  legacy records, per AGENTS.md.
- Warning emission goes through the existing `diagnostics.warning` seam; no new
  output channel.
- No agent-identity branching in the process runner; loop policy stays declarative
  on the backward edge.

## Open Questions

None. Resolved during preparation:

- Stall guard: keep one, as no-progress detection (identical unresolved set + no
  repository change + no newly resolved item), not a high ceiling.
- Remediation depth for passes three and beyond: inline light tier on the
  remediation delta.
- Surfaces: runtime (Kotlin) and prose skill content. Standalone `bill-code-review`
  and `bill-feature-verify` are out of scope.

## Next Path

```bash
skill-bill goal SKILL-156
```
