package skillbill.application.featuretask

import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition

internal fun FeatureTaskRuntimeRunState.reviewGeneration(): Int = reviewGeneration

internal fun FeatureTaskRuntimeRunState.advanceReviewGeneration(next: Int) {
  if (next > reviewGeneration) reviewGeneration = next
}

internal fun FeatureTaskRuntimeRunState.evidenceGeneration(phaseId: String): Int =
  if (phaseId in FeatureTaskRuntimePhaseWorkflowDefinition.GENERATION_SCOPED_PHASE_IDS) reviewGeneration else 0

internal fun FeatureTaskRuntimeRunState.currentReviewPassNumber(): Int? = currentReviewPassNumber

internal fun FeatureTaskRuntimeRunState.completedReviewPassNumber(): Int? = completedReviewPassNumber

internal fun FeatureTaskRuntimeRunState.reserveReviewPass(passNumber: Int?) {
  if (passNumber != null) currentReviewPassNumber = passNumber
}
