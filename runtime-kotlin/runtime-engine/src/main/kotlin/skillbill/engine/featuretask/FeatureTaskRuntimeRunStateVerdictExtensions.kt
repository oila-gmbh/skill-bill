package skillbill.engine.featuretask

import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
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

fun FeatureTaskRuntimeRunState.auditGapCriterionRefs(): List<String> =
  if (verdictFor(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT) != FeatureTaskRuntimeVerdict.GAPS_FOUND) {
    emptyList()
  } else {
    FeatureTaskRuntimeOutputVerification.auditGapCriterionRefs(
      parsedOutput(outputFor(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT)),
    ).sorted()
  }

fun FeatureTaskRuntimeRunState.durableVerdictFor(phaseId: String): FeatureTaskRuntimeVerdict {
  val record = initialRecords[phaseId] ?: return verdictFor(phaseId)
  return FeatureTaskRuntimeOutputVerification.verdictFor(
    phaseId,
    parsedOutput(validatedRecordToOutput(record)),
  )
}
