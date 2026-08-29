package skillbill.application.featuretask

import skillbill.contracts.JsonSupport
import skillbill.error.InvalidFeatureTaskRuntimeFindingVerificationRecordError
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFindingVerificationDisposition
import skillbill.workflow.taskruntime.model.validateDispositionCoverage

internal object FeatureTaskRuntimeVerificationGateReasons {
  fun findingVerificationDisposition(
    phaseId: String,
    outputMap: Map<String, Any?>,
    reviewFindingIds: Set<String>,
  ): String? {
    if (phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS || reviewFindingIds.isEmpty()) {
      return null
    }
    val dispositionsKey = FeatureTaskRuntimeVerificationSignalKeys.FINDINGS_VERIFICATION_DISPOSITIONS
    val dispositionsRaw = outputMap["produced_outputs"]
      ?.let(JsonSupport::anyToStringAnyMap)
      ?.get(dispositionsKey) as? List<*>
      ?: return "verify_findings reported 'completed' without produced_outputs.$dispositionsKey."
    return runCatching {
      FeatureTaskRuntimeFindingVerificationDisposition.parseList(
        dispositionsRaw,
        "produced_outputs.$dispositionsKey",
      )
    }.fold(
      onSuccess = { validateDispositionCoverage(it, reviewFindingIds) },
      onFailure = { failure ->
        when (failure) {
          is InvalidFeatureTaskRuntimeFindingVerificationRecordError ->
            failure.message ?: "finding verification dispositions are not contract-safe."
          else -> failure.message ?: "finding verification dispositions are not contract-safe."
        }
      },
    )
  }

  fun reviewVerificationSignal(phaseId: String, outputMap: Map<String, Any?>): String? {
    if (phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW) return null
    val hasVerdict = (outputMap[FeatureTaskRuntimeVerificationSignalKeys.VERDICT] as? String)?.isNotBlank() == true
    val producedOutputs = outputMap["produced_outputs"] as? Map<*, *>
    val findingsKey = FeatureTaskRuntimeVerificationSignalKeys.REVIEW_FINDINGS
    val hasFindingsArray = producedOutputs?.containsKey(findingsKey) == true && producedOutputs[findingsKey] is List<*>
    return if (hasVerdict || hasFindingsArray) {
      null
    } else {
      "Review phase reported 'completed' without a verification signal: the output must carry either a " +
        "top-level 'verdict' or a 'produced_outputs.findings' array (an explicit empty array affirms no " +
        "blocking findings). A review that emits neither cannot advance past a possible Blocker/Major; " +
        "the schema gate fails rather than silently advancing to validation."
    }
  }
}
