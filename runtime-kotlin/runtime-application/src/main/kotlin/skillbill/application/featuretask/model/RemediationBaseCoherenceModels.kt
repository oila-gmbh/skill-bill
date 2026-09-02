package skillbill.application.featuretask.model

import skillbill.workflow.goal.model.GoalSubtaskReviewState

sealed interface RemediationBaseCoherenceResult

data class RemediationBaseCoherent(val state: GoalSubtaskReviewState?) : RemediationBaseCoherenceResult

data class RemediationBaseBlocked(val operatorGuidance: String) : RemediationBaseCoherenceResult
