package skillbill.application.featuretask

import skillbill.contracts.JsonSupport
import skillbill.error.InvalidFeatureTaskRuntimeFindingVerificationRecordError
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFindingVerificationDisposition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.model.validateDispositionCoverage

internal object FeatureTaskRuntimeVerificationGateReasons {
  fun verifyFindingsWorktree(phaseId: String, fileManifest: FeatureTaskRuntimePhaseFileManifest): String? {
    if (phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS) return null
    val changed = (fileManifest.after.toSet() - fileManifest.before.toSet()) + fileManifest.introduced
    if (changed.isEmpty()) return null
    return "verify_findings must not edit the worktree; changed paths: ${changed.sorted().joinToString()}."
  }

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

  fun auditVerificationSignal(phaseId: String, outputMap: Map<String, Any?>): String? {
    if (phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT) return null
    FeatureTaskRuntimeOutputVerification.auditGapPayloadError(outputMap)?.let { return it }
    val wireVerdict = (outputMap[FeatureTaskRuntimeVerificationSignalKeys.VERDICT] as? String)
      ?.takeIf(String::isNotBlank)
    val producedOutputs = outputMap["produced_outputs"] as? Map<*, *>
    val gapsKey = FeatureTaskRuntimeVerificationSignalKeys.AUDIT_GAPS
    val legacyCriteriaKey = FeatureTaskRuntimeVerificationSignalKeys.AUDIT_UNMET_CRITERIA
    val hasGapsArray = producedOutputs?.containsKey(gapsKey) == true && producedOutputs[gapsKey] is List<*>
    val hasLegacyCriteriaArray =
      producedOutputs?.containsKey(legacyCriteriaKey) == true && producedOutputs[legacyCriteriaKey] is List<*>
    val hasNormalizedRepairPlan = producedOutputs?.containsKey("audit_repair_plan") == true
    if (hasGapsArray || hasLegacyCriteriaArray || hasNormalizedRepairPlan) return null
    val auditVocabulary = FeatureTaskRuntimeVerdict.AUDIT_VERDICTS.joinToString("', '") { it.wireValue }
    return when {
      wireVerdict == null ->
        "Audit phase reported 'completed' without a verification signal: the output must carry either a " +
          "top-level 'verdict' or a 'produced_outputs.gaps' array (an explicit empty array affirms " +
          "every acceptance criterion is met). An audit that emits neither cannot advance past a possibly-unmet " +
          "criterion; the schema gate fails rather than silently advancing past audit."
      FeatureTaskRuntimeVerdict.fromWire(wireVerdict) !in FeatureTaskRuntimeVerdict.AUDIT_VERDICTS ->
        "Audit phase reported 'completed' with the off-vocabulary verdict '$wireVerdict' and no " +
          "'produced_outputs.gaps' array. With no gap array the verdict is the only decidable " +
          "signal and it gates entry into review, so it must be one of '$auditVocabulary' — or emit the " +
          "gaps array (an explicit empty array affirms every acceptance criterion is met)."
      else -> null
    }
  }
}
