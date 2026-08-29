package skillbill.application.featuretask

import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaseline
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewInput
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewInputResult
import skillbill.workflow.goal.model.GoalSubtaskReviewState

internal fun selectedGoalReviewBaseline(
  state: GoalSubtaskReviewState,
  scope: FeatureTaskRuntimeGoalContinuationRecorder.GoalReviewInputScope,
): Pair<GoalSubtaskReviewBaseline, GoalReviewBaseField> {
  val exclusions = scope.scopedUntrackedExclusions ?: state.baselineUntrackedPaths
  val remediationBaseline = state.remediationBaseSha
    ?.takeIf { state.completedPassCount >= 1 && state.reservedPassNumber == null }
    ?.let { preFixSha -> GoalSubtaskReviewBaseline(preFixSha, exclusions, scope.ownedPathspec) }
  return if (remediationBaseline != null) {
    remediationBaseline to GoalReviewBaseField.REMEDIATION_BASE
  } else {
    GoalSubtaskReviewBaseline(state.reviewBaseSha, exclusions, scope.ownedPathspec) to GoalReviewBaseField.REVIEW_BASE
  }
}

internal fun FeatureTaskRuntimeGoalReviewInputBuilder.goalReviewInputFromBuildResult(
  result: GoalSubtaskReviewInputResult,
  recovery: GoalReviewInputRecovery?,
): GoalSubtaskReviewInput? = when {
  result.ok -> requireNotNull(result.input)
  recovery is GoalReviewInputRecovery.Recovered -> recovery.input
  else -> null
}

internal fun goalReviewBlockedPreparation(
  result: GoalSubtaskReviewInputResult,
  recovery: GoalReviewInputRecovery?,
): GoalSubtaskReviewInputPreparation = GoalSubtaskReviewInputBlocked(
  when (recovery) {
    is GoalReviewInputRecovery.Failed -> recovery.reason
    is GoalReviewInputRecovery.Ineligible, null -> result.error
    is GoalReviewInputRecovery.Recovered -> error("blocked preparation requested for recovered input")
  },
)
