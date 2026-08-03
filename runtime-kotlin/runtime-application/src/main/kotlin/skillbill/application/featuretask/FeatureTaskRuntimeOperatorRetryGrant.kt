package skillbill.application.featuretask

import skillbill.workflow.taskruntime.model.GoalSubtaskOperatorDecision

/**
 * An operator-granted `retry_fix` must actually enter the `implement_fix` iteration it grants, so it
 * suppresses the unresolved-Blocker pause for exactly one transition. It buys no iteration budget —
 * the `review_fix` edge declares no cap — and it is single-use: once the edge is taken the grant is
 * consumed, so a subsequent unresolved pass pauses again.
 */
internal object FeatureTaskRuntimeOperatorRetryGrant {
  fun active(consumed: Boolean, inSessionGrant: Boolean, persistedDecision: GoalSubtaskOperatorDecision?): Boolean =
    !consumed && (inSessionGrant || persistedDecision == GoalSubtaskOperatorDecision.RETRY_FIX)

  fun pausesOnUnresolvedBlocker(grantActive: Boolean, unresolvedBlockerPresent: Boolean): Boolean =
    unresolvedBlockerPresent && !grantActive
}
