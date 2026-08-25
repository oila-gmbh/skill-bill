package skillbill.application.featuretask

import skillbill.contracts.JsonSupport
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFindingVerificationDisposition
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
      ?: return null
    return runCatching {
      FeatureTaskRuntimeFindingVerificationDisposition.parseList(
        dispositionsRaw,
        "produced_outputs.$dispositionsKey",
      )
    }.fold(
      onSuccess = { validateDispositionCoverage(it, reviewFindingIds) },
      onFailure = { null },
    )
  }

  fun reviewVerificationSignal(phaseId: String): String? {
    if (phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW) return null
    return null
  }

  fun auditVerificationSignal(phaseId: String, outputMap: Map<String, Any?>): String? {
    if (phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT) return null
    FeatureTaskRuntimeOutputVerification.auditGapPayloadError(outputMap)?.let { return it }
    return null
  }
}
