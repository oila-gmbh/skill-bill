package skillbill.application.featuretask

import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition

fun FeatureTaskRuntimeRunState.reviewGeneration(): Int = reviewGeneration

fun FeatureTaskRuntimeRunState.advanceReviewGeneration(next: Int) {
  if (next > reviewGeneration) reviewGeneration = next
}

fun FeatureTaskRuntimeRunState.evidenceGeneration(phaseId: String): Int =
  if (phaseId in FeatureTaskRuntimePhaseWorkflowDefinition.GENERATION_SCOPED_PHASE_IDS) reviewGeneration else 0

fun FeatureTaskRuntimeRunState.currentReviewPassNumber(): Int? = currentReviewPassNumber

fun FeatureTaskRuntimeRunState.completedReviewPassNumber(): Int? = completedReviewPassNumber

fun FeatureTaskRuntimeRunState.reserveReviewPass(passNumber: Int?) {
  if (passNumber != null) currentReviewPassNumber = passNumber
}
