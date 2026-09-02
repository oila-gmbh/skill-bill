package skillbill.application.featuretask

import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeReviewFinding
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict

fun FeatureTaskRuntimeRunState.verdictFor(phaseId: String): FeatureTaskRuntimeVerdict =
  FeatureTaskRuntimeOutputVerification.verdictFor(phaseId, parsedOutput(outputFor(phaseId)))

fun FeatureTaskRuntimeRunState.settledVerdictsByPhaseId(): Map<String, FeatureTaskRuntimeVerdict> =
  completed.associateWith(::verdictFor)

fun FeatureTaskRuntimeRunState.spanBlockedByEntryGate(span: List<String>): Boolean {
  val settledVerdicts = settledVerdictsByPhaseId()
  return span.any { phaseId -> transitions.entryGateViolation(phaseId, settledVerdicts) != null }
}

fun FeatureTaskRuntimeRunState.unresolvedReviewFindings(phaseId: String): List<FeatureTaskRuntimeReviewFinding> =
  FeatureTaskRuntimeOutputVerification.unresolvedReviewFindings(parsedOutput(outputFor(phaseId)))

fun FeatureTaskRuntimeRunState.durableVerdictFor(phaseId: String): FeatureTaskRuntimeVerdict {
  val record = initialRecords[phaseId] ?: return verdictFor(phaseId)
  return FeatureTaskRuntimeOutputVerification.verdictFor(
    phaseId,
    parsedOutput(validatedRecordToOutput(record)),
  )
}
