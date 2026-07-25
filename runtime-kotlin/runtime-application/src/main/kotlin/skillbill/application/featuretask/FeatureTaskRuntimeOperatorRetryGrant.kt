package skillbill.application.featuretask

import skillbill.workflow.taskruntime.model.GoalSubtaskOperatorDecision

/**
 * AC-015. An operator-granted `retry_fix` must actually enter the `implement_fix` iteration it grants:
 * it discounts one consumed `review_fix` iteration and suppresses the unresolved-Blocker pause for
 * exactly one transition. The grant is single-use — once the edge is taken it is consumed, so a
 * subsequent unresolved pass pauses again.
 */
internal object FeatureTaskRuntimeOperatorRetryGrant {
  fun active(consumed: Boolean, inSessionGrant: Boolean, persistedDecision: GoalSubtaskOperatorDecision?): Boolean =
    !consumed && (inSessionGrant || persistedDecision == GoalSubtaskOperatorDecision.RETRY_FIX)

  fun pausesOnUnresolvedBlocker(grantActive: Boolean, unresolvedBlockerPresent: Boolean): Boolean =
    unresolvedBlockerPresent && !grantActive

  fun discountedIterationCount(consumedIterations: Int, grantActive: Boolean): Int =
    if (grantActive) (consumedIterations - 1).coerceAtLeast(0) else consumedIterations
}
