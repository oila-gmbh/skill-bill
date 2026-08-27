package skillbill.application.featuretask

import skillbill.application.model.FeatureTaskRuntimeImplementationContinuation
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeImplementationAttempt

internal data class FeatureTaskRuntimeImplementationObligations(
  val plannedTaskIds: List<String>,
  val carriedRepairItemIds: List<String>,
  val loopId: String?,
  val edgeIteration: Int? = null,
)

internal data class FeatureTaskRuntimeImplementationClaim(
  val value: String,
  val prompt: String? = null,
)

internal fun featureTaskRuntimeImplementationCompletionReason(
  phaseId: String,
  obligations: FeatureTaskRuntimeImplementationObligations,
  claim: FeatureTaskRuntimeImplementationClaim,
): String? = null

internal fun featureTaskRuntimeOpenObligations(
  obligations: FeatureTaskRuntimeImplementationObligations,
  claim: FeatureTaskRuntimeImplementationClaim,
): List<String> = emptyList()

internal fun featureTaskRuntimeImplementationContinuationFrom(
  phaseId: String,
  attempts: List<FeatureTaskRuntimeImplementationAttempt>,
  obligations: FeatureTaskRuntimeImplementationObligations,
): FeatureTaskRuntimeImplementationContinuation? {
  val phaseAttempts = attempts.filter {
    it.phaseId == phaseId && it.loopId == obligations.loopId && it.edgeIteration == obligations.edgeIteration
  }
  if (phaseAttempts.isEmpty()) return null
  val priorSegments = phaseAttempts.map { it.value }
  val latest = phaseAttempts.maxByOrNull { it.sequenceNumber } ?: return null
  return FeatureTaskRuntimeImplementationContinuation(
    phaseId = phaseId,
    segmentNumber = phaseAttempts.size + 1,
    priorValueSegments = priorSegments,
    latestPrompt = latest.prompt,
    failureDisposition = latest.failureDisposition?.wireValue,
  )
}

internal fun featureTaskRuntimePlannedTaskIdsFrom(): List<String> = emptyList()

internal fun featureTaskRuntimeCarriedRepairItemIds(briefingRepairItemIds: List<String>): List<String> =
  briefingRepairItemIds.distinct()

internal fun featureTaskRuntimeClosedRepairItemIds(outputMap: Map<String, Any?>): List<String> = emptyList()

internal fun featureTaskRuntimeIncompleteWorkGateReason(
  phaseId: String,
  outputMap: Map<String, Any?>,
  obligations: FeatureTaskRuntimeImplementationObligations,
): String? = null
