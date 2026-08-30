package skillbill.application.featuretask

import skillbill.application.featuretask.model.CompletedUpstreamRepairRequest
import skillbill.workflow.engine.model.WorkflowUpdateInput
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_OPERATOR_BLOCK_RETRY_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_LEDGER_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_LEDGER_LIMIT
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerEntry
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseRecord
import java.time.OffsetDateTime
import java.time.ZoneOffset

internal fun phasesToReopenForCompletedUpstreamRepair(
  request: CompletedUpstreamRepairRequest,
  recordedOutputs: List<FeatureTaskRuntimePhaseOutput>,
): List<String> {
  val phaseRecords = request.phaseRecords
  val resumePhaseId = request.resumePhaseId
  val featureSize = request.featureSize
  val qualityGateSelection = request.qualityGateSelection
  val stepOrder = FeatureTaskRuntimePhaseWorkflowDefinition.definition.stepIds
  return when {
    phaseRecords[resumePhaseId]?.status == "blocked" -> listOf(resumePhaseId)
    else -> buildList {
      add(resumePhaseId)
      phaseRecords.forEach { (phaseId, record) ->
        if (record.status == "blocked") {
          val missing = missingUpstream(
            phaseDeclaration(phaseId, featureSize, qualityGateSelection),
            recordedOutputs,
          )
          if (missing?.contains(resumePhaseId) == true) add(phaseId)
        }
      }
    }.distinct().sortedBy { stepOrder.indexOf(it).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE }
  }
}

internal fun completedUpstreamRepairWorkflowUpdate(
  request: CompletedUpstreamRepairRequest,
  phasesToReopen: List<String>,
  reopenedRecords: Map<String, FeatureTaskRuntimePhaseRecord>,
  retryEntry: FeatureTaskRuntimePhaseLedgerEntry,
): WorkflowUpdateInput = WorkflowUpdateInput(
  workflowStatus = "running",
  currentStepId = request.resumePhaseId,
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
      (request.ledger.map { it.toArtifactMap() } + retryEntry.toArtifactMap()).takeLast(
        FEATURE_TASK_RUNTIME_PHASE_LEDGER_LIMIT,
      ),
    FEATURE_TASK_RUNTIME_OPERATOR_BLOCK_RETRY_ARTIFACT_KEY to mapOf(
      "phase_id" to request.resumePhaseId,
      "reason" to request.reason,
      "retried_at" to OffsetDateTime.now(ZoneOffset.UTC).toString(),
      "previous_blocked_reason" to "completed_upstream_missing_output",
      "reopened_phase_ids" to phasesToReopen,
    ),
    "goal_continuation_outcome" to null,
  ),
  sessionId = "",
)

internal fun completedUpstreamRepairRetryEntry(
  request: CompletedUpstreamRepairRequest,
): FeatureTaskRuntimePhaseLedgerEntry = FeatureTaskRuntimePhaseLedgerEntry(
  action = FeatureTaskRuntimePhaseLedgerAction.RETRY,
  sequenceNumber = (request.ledger.maxOfOrNull { it.sequenceNumber } ?: -1) + 1,
  timestamp = OffsetDateTime.now(ZoneOffset.UTC).toString(),
  phaseId = request.resumePhaseId,
  attemptCount = requireNotNull(request.phaseRecords[request.resumePhaseId]).attemptCount,
  resolvedAgentId = requireNotNull(request.phaseRecords[request.resumePhaseId]).resolvedAgentId,
)

internal fun settledPhaseOutputs(
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
