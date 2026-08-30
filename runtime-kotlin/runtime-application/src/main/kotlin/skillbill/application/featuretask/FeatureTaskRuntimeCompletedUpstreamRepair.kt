package skillbill.application.featuretask

import skillbill.application.featuretask.model.CompletedUpstreamRepairRequest
import skillbill.workflow.engine.model.WorkflowUpdateInput
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFeatureSize
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeQualityGateSelection
import skillbill.workflow.taskruntime.model.featureTaskRuntimeRunInvariantsFromArtifactMap
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_RUN_INVARIANTS_ARTIFACT_KEY

internal fun featureSizeFromArtifacts(artifacts: Map<String, Any?>): FeatureTaskRuntimeFeatureSize {
  val raw = artifacts[FEATURE_TASK_RUNTIME_RUN_INVARIANTS_ARTIFACT_KEY] as? Map<*, *>
    ?: return FeatureTaskRuntimeFeatureSize.MEDIUM

  @Suppress("UNCHECKED_CAST")
  val invariantsMap = raw as? Map<String, Any?> ?: return FeatureTaskRuntimeFeatureSize.MEDIUM
  return featureTaskRuntimeRunInvariantsFromArtifactMap(invariantsMap).featureSize
}

internal fun diagnoseUnsettledCompletedUpstreamPhaseId(
  phaseRecords: Map<String, FeatureTaskRuntimePhaseRecord>,
  featureSize: FeatureTaskRuntimeFeatureSize,
  qualityGateSelection: FeatureTaskRuntimeQualityGateSelection =
    FeatureTaskRuntimeQualityGateSelection.VALIDATE,
): String? {
  val recordedOutputs = settledPhaseOutputs(phaseRecords)
  val stepOrder = FeatureTaskRuntimePhaseWorkflowDefinition.definition.stepIds
  val blockedConsumers = phaseRecords.filterValues { it.status == "blocked" }.keys
  for (consumerPhaseId in blockedConsumers) {
    val declaration = phaseDeclaration(consumerPhaseId, featureSize, qualityGateSelection)
    val blockedReason = phaseRecords[consumerPhaseId]?.blockedReason.orEmpty()
    val missing = missingUpstream(declaration, recordedOutputs)
      ?.filter { upstreamId ->
        val upstream = phaseRecords[upstreamId] ?: return@filter false
        upstream.outputArtifact.isNullOrBlank()
      }
    if (!missing.isNullOrEmpty()) {
      return missing.minBy { stepOrder.indexOf(it).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE }
    }
    if (
      blockedReason.contains("upstream output", ignoreCase = true) &&
      blockedReason.contains("not present", ignoreCase = true)
    ) {
      return consumerPhaseId
    }
  }
  return null
}

internal fun buildCompletedUpstreamMissingOutputRepair(
  request: CompletedUpstreamRepairRequest,
): WorkflowUpdateInput {
  val recordedOutputs = settledPhaseOutputs(request.phaseRecords)
  val phasesToReopen = phasesToReopenForCompletedUpstreamRepair(request, recordedOutputs)
  val reopenedRecords = LinkedHashMap(request.phaseRecords)
  phasesToReopen.forEach { phaseId ->
    val existing = requireNotNull(reopenedRecords[phaseId]) {
      "Cannot reopen missing phase record '$phaseId'."
    }
    reopenedRecords[phaseId] = existing.asPendingForOperatorResume()
  }
  return completedUpstreamRepairWorkflowUpdate(
    request,
    phasesToReopen,
    reopenedRecords,
    completedUpstreamRepairRetryEntry(request),
  )
}
