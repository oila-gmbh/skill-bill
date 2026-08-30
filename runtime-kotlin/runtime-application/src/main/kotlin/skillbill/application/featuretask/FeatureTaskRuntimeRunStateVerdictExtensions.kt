package skillbill.application.featuretask

import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeReviewFinding
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict

internal fun FeatureTaskRuntimeRunState.verdictFor(phaseId: String): FeatureTaskRuntimeVerdict =
  FeatureTaskRuntimeOutputVerification.verdictFor(phaseId, parsedOutput(outputFor(phaseId)))

internal fun FeatureTaskRuntimeRunState.settledVerdictsByPhaseId(): Map<String, FeatureTaskRuntimeVerdict> =
  completed.associateWith(::verdictFor)

internal fun FeatureTaskRuntimeRunState.spanBlockedByEntryGate(span: List<String>): Boolean {
  val settledVerdicts = settledVerdictsByPhaseId()
  return span.any { phaseId -> transitions.entryGateViolation(phaseId, settledVerdicts) != null }
}

internal fun FeatureTaskRuntimeRunState.unresolvedReviewFindings(phaseId: String): List<FeatureTaskRuntimeReviewFinding> =
  FeatureTaskRuntimeOutputVerification.unresolvedReviewFindings(parsedOutput(outputFor(phaseId)))

internal fun FeatureTaskRuntimeRunState.durableVerdictFor(phaseId: String): FeatureTaskRuntimeVerdict =
  FeatureTaskRuntimeOutputVerification.verdictFor(
    phaseId,
    parsedOutput(validatedRecordToOutput(initialRecords[phaseId])),
  )
