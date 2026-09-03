package skillbill.infrastructure.sqlite.goalrunner

import skillbill.goalrunner.model.GoalRunnerSupervisionEvent
import skillbill.ports.goalrunner.persistence.blockedStepId
import skillbill.ports.goalrunner.persistence.decodeWorkflowSteps
import skillbill.ports.goalrunner.persistence.model.GoalRunnerBlockWrite
import skillbill.ports.goalrunner.persistence.toArtifactsMap
import skillbill.ports.goalrunner.persistence.workflowFamilyFor
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.phaseartifacts.asPendingForOperatorResume
import skillbill.ports.phaseartifacts.phaseLedgerFrom
import skillbill.ports.phaseartifacts.phaseRecordsFrom
import skillbill.ports.workflow.WorkflowStateRepository
import skillbill.ports.workflow.decomposition.runtime.decodeArtifacts
import skillbill.ports.workflow.persistence.model.WorkflowFamily
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.model.WorkflowUpdateInput
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_OPERATOR_BLOCK_RETRY_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_LEDGER_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_LEDGER_LIMIT
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerEntry
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseRecord
import java.time.OffsetDateTime
import java.time.ZoneOffset

internal class WorkflowGoalRunnerBlockWrites(
  private val engine: WorkflowEngine,
) {
  fun markBlocked(
    workflowId: String,
    blockedReason: String,
    lastResumableStep: String,
    supervisionEvent: GoalRunnerSupervisionEvent?,
    workflowStates: WorkflowStateRepository,
  ): String? {
    val family = workflowFamilyFor(workflowStates, workflowId) ?: return null
    val record = family.get(workflowStates, workflowId) ?: return null
    return markBlocked(
      GoalRunnerBlockWrite(
        family = family,
        record = record,
        blockedReason = blockedReason,
        lastResumableStep = lastResumableStep,
        workflowStates = workflowStates,
        supervisionEvent = supervisionEvent,
      ),
    )
  }

  fun markBlocked(write: GoalRunnerBlockWrite): String {
    val steps = decodeWorkflowSteps(write.record.stepsJson)
    val definitionStepIds =
      if (write.family == WorkflowFamily.TASK_RUNTIME) {
        write.family.definition.stepIds.filterNot { it in write.family.loopOnlyStepIds }
      } else {
        emptyList()
      }
    val stepId = blockedStepId(write.record, steps, write.lastResumableStep, definitionStepIds)
    val attemptCount = steps.firstOrNull { it.stepId == stepId }?.attemptCount ?: 1
    val updated = engine.updateRecord(
      write.family.definition,
      write.record,
      WorkflowUpdateInput(
        workflowStatus = "blocked",
        currentStepId = stepId,
        stepUpdates = listOf(
          mapOf("step_id" to stepId, "status" to "blocked", "attempt_count" to attemptCount),
        ),
        artifactsPatch = buildMap {
          put("blocked_reason", write.blockedReason)
          write.supervisionEvent?.let { event -> put("supervision_event", event.toArtifactsMap()) }
        },
        sessionId = write.record.sessionId.orEmpty(),
      ),
    )
    write.family.save(write.workflowStates, updated)
    return stepId
  }

  fun reopenBlockedPhaseForOperatorResume(
    unitOfWork: UnitOfWork,
    workflowId: String,
    preferredPhaseId: String,
    reason: String,
  ): Boolean {
    val family = WorkflowFamily.TASK_RUNTIME
    val existing = family.get(unitOfWork.workflowStates, workflowId) ?: return false
    if (existing.workflowStatus in family.definition.terminalStatuses) {
      return false
    }
    val artifacts = decodeArtifacts(existing.artifactsJson)
    val phaseRecords = phaseRecordsFrom(artifacts)
    val blockedRecord = operatorReopenablePhaseRecord(
      phaseRecords,
      preferredPhaseId,
      existing.workflowStatus,
    ) ?: return true
    family.save(
      unitOfWork.workflowStates,
      engine.updateRecord(
        family.definition,
        existing,
        operatorBlockedPhaseReopenUpdate(blockedRecord, phaseRecords, phaseLedgerFrom(artifacts), reason),
      ),
    )
    return true
  }

  private fun operatorReopenablePhaseRecord(
    phaseRecords: Map<String, FeatureTaskRuntimePhaseRecord>,
    preferredPhaseId: String,
    workflowStatus: String,
  ): FeatureTaskRuntimePhaseRecord? {
    val preferred = phaseRecords[preferredPhaseId]
    return preferred?.takeIf { it.status == "blocked" }
      ?: phaseRecords.values.firstOrNull { it.status == "blocked" }
      ?: preferred?.takeIf { workflowStatus == "blocked" && it.status == "running" }
  }

  private fun operatorBlockedPhaseReopenUpdate(
    blockedRecord: FeatureTaskRuntimePhaseRecord,
    phaseRecords: Map<String, FeatureTaskRuntimePhaseRecord>,
    ledger: List<FeatureTaskRuntimePhaseLedgerEntry>,
    reason: String,
  ): WorkflowUpdateInput {
    val reopened = LinkedHashMap(phaseRecords).apply {
      this[blockedRecord.phaseId] = blockedRecord.asPendingForOperatorResume()
    }
    val retryEntry = FeatureTaskRuntimePhaseLedgerEntry(
      action = FeatureTaskRuntimePhaseLedgerAction.RETRY,
      sequenceNumber = (ledger.maxOfOrNull { it.sequenceNumber } ?: -1) + 1,
      timestamp = OffsetDateTime.now(ZoneOffset.UTC).toString(),
      phaseId = blockedRecord.phaseId,
      attemptCount = blockedRecord.attemptCount,
      resolvedAgentId = blockedRecord.resolvedAgentId,
    )
    return WorkflowUpdateInput(
      workflowStatus = "running",
      currentStepId = blockedRecord.phaseId,
      stepUpdates = listOf(
        mapOf(
          "step_id" to blockedRecord.phaseId,
          "status" to "pending",
          "attempt_count" to 0,
        ),
      ),
      artifactsPatch = mapOf(
        FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY to
          reopened.mapValues { (_, record) -> record.toArtifactMap() },
        FEATURE_TASK_RUNTIME_PHASE_LEDGER_ARTIFACT_KEY to
          (ledger.map { it.toArtifactMap() } + retryEntry.toArtifactMap()).takeLast(
            FEATURE_TASK_RUNTIME_PHASE_LEDGER_LIMIT,
          ),
        FEATURE_TASK_RUNTIME_OPERATOR_BLOCK_RETRY_ARTIFACT_KEY to mapOf(
          "phase_id" to blockedRecord.phaseId,
          "reason" to reason,
          "retried_at" to OffsetDateTime.now(ZoneOffset.UTC).toString(),
          "previous_blocked_reason" to blockedRecord.blockedReason,
          "previous_blocked_record" to blockedRecord.toArtifactMap(),
        ),
      ),
      sessionId = "",
    )
  }
}
