package skillbill.application.featuretask

import skillbill.workflow.model.WorkflowUpdateInput
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_LEDGER_LIMIT
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_OPERATOR_BLOCK_RETRY_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_LEDGER_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_RUN_INVARIANTS_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFeatureSize
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerEntry
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.featureTaskRuntimeRunInvariantsFromArtifactMap
import java.time.OffsetDateTime
import java.time.ZoneOffset

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
): String? {
  val recordedOutputs = settledPhaseOutputs(phaseRecords)
  val stepOrder = FeatureTaskRuntimePhaseWorkflowDefinition.definition.stepIds
  val blockedConsumers = phaseRecords.filterValues { it.status == "blocked" }.keys
  for (consumerPhaseId in blockedConsumers) {
    val declaration = phaseDeclaration(consumerPhaseId, featureSize)
    val missing = missingUpstream(declaration, recordedOutputs) ?: continue
    val unsettled = missing.filter { phaseId -> phaseRecords[phaseId].isUnsettledCompleted() }
    if (unsettled.isNotEmpty()) {
      return unsettled.minBy { stepOrder.indexOf(it).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE }
    }
  }
  return null
}

internal fun buildCompletedUpstreamMissingOutputRepair(
  phaseRecords: Map<String, FeatureTaskRuntimePhaseRecord>,
  ledger: List<FeatureTaskRuntimePhaseLedgerEntry>,
  featureSize: FeatureTaskRuntimeFeatureSize,
  resumePhaseId: String,
  reason: String,
): WorkflowUpdateInput {
  val stepOrder = FeatureTaskRuntimePhaseWorkflowDefinition.definition.stepIds
  val recordedOutputs = settledPhaseOutputs(phaseRecords)
  val phasesToReopen = buildList {
    add(resumePhaseId)
    phaseRecords.forEach { (phaseId, record) ->
      if (record.status == "blocked") {
        val missing = missingUpstream(phaseDeclaration(phaseId, featureSize), recordedOutputs)
        if (missing?.contains(resumePhaseId) == true) add(phaseId)
      }
    }
  }.distinct().sortedBy { stepOrder.indexOf(it).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE }
  val reopenedRecords = LinkedHashMap(phaseRecords)
  phasesToReopen.forEach { phaseId ->
    val existing = requireNotNull(reopenedRecords[phaseId]) {
      "Cannot reopen missing phase record '$phaseId'."
    }
    reopenedRecords[phaseId] = existing.asPendingForOperatorResume()
  }
  val retryEntry = FeatureTaskRuntimePhaseLedgerEntry(
    action = FeatureTaskRuntimePhaseLedgerAction.RETRY,
    sequenceNumber = (ledger.maxOfOrNull { it.sequenceNumber } ?: -1) + 1,
    timestamp = OffsetDateTime.now(ZoneOffset.UTC).toString(),
    phaseId = resumePhaseId,
    attemptCount = requireNotNull(phaseRecords[resumePhaseId]).attemptCount,
    resolvedAgentId = requireNotNull(phaseRecords[resumePhaseId]).resolvedAgentId,
  )
  return WorkflowUpdateInput(
    workflowStatus = "running",
    currentStepId = resumePhaseId,
    stepUpdates = phasesToReopen.map { phaseId ->
      mapOf(
        "step_id" to phaseId,
        "status" to "pending",
        "attempt_count" to 0,
      )
    },
    artifactsPatch = mapOf(
      FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY to
        reopenedRecords.mapValues { (_, record) -> record.toArtifactMap() },
      FEATURE_TASK_RUNTIME_PHASE_LEDGER_ARTIFACT_KEY to
        (ledger.map { it.toArtifactMap() } + retryEntry.toArtifactMap()).takeLast(
          FEATURE_TASK_RUNTIME_PHASE_LEDGER_LIMIT,
        ),
      FEATURE_TASK_RUNTIME_OPERATOR_BLOCK_RETRY_ARTIFACT_KEY to mapOf(
        "phase_id" to resumePhaseId,
        "reason" to reason,
        "retried_at" to OffsetDateTime.now(ZoneOffset.UTC).toString(),
        "previous_blocked_reason" to "completed_upstream_missing_output",
        "reopened_phase_ids" to phasesToReopen,
      ),
      "goal_continuation_outcome" to null,
    ),
    sessionId = "",
  )
}

private fun settledPhaseOutputs(
  phaseRecords: Map<String, FeatureTaskRuntimePhaseRecord>,
): List<FeatureTaskRuntimePhaseOutput> = phaseRecords.values.mapNotNull { record ->
  record.outputArtifact?.takeIf(String::isNotBlank)?.let { artifact ->
    FeatureTaskRuntimePhaseOutput(
      phaseId = record.phaseId,
      iteration = record.attemptCount,
      payload = artifact,
    )
  }
}

private fun FeatureTaskRuntimePhaseRecord?.isUnsettledCompleted(): Boolean =
  this != null && status == "completed" && outputArtifact.isNullOrBlank()
