package skillbill.application.workflow

import skillbill.application.decomposition.DecompositionManifestProjectionSupport
import skillbill.application.decomposition.DecompositionManifestWriter
import skillbill.application.workflow.model.WorkflowUpdateResult
import skillbill.model.RepositoryRoot
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.workflow.decomposition.DecompositionManifestStore
import skillbill.workflow.decomposition.DecompositionManifestValidator
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.engine.model.WorkflowUpdateInput
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_OPERATOR_BLOCK_RETRY_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_OPERATOR_BLOCK_RETRY_REASON_MAX_LENGTH
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_LEDGER_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_LEDGER_LIMIT
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerEntry
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseRecord
import java.time.OffsetDateTime
import java.time.ZoneOffset

class WorkflowServiceBlockedPhaseRetry(
  private val engine: WorkflowEngine,
  private val decompositionManifestValidator: DecompositionManifestValidator,
  private val decompositionManifestStore: DecompositionManifestStore,
  private val decompositionManifestWriter: DecompositionManifestWriter,
  private val repositoryRoot: RepositoryRoot,
) {
  fun retry(
    database: DatabaseSessionFactory,
    workflowId: String,
    phaseId: String,
    reason: String,
    dbOverride: String?,
  ): WorkflowUpdateResult {
    val normalizedReason = reason.trim()
    if (
      normalizedReason.isEmpty() ||
      normalizedReason.length > FEATURE_TASK_RUNTIME_OPERATOR_BLOCK_RETRY_REASON_MAX_LENGTH
    ) {
      return WorkflowUpdateResult.Error(
        workflowId,
        "Blocked-phase retry reason must contain " +
          "1..$FEATURE_TASK_RUNTIME_OPERATOR_BLOCK_RETRY_REASON_MAX_LENGTH characters.",
      )
    }
    val normalizedPhaseId = phaseId.trim()
    val family = WorkflowFamily.TASK_RUNTIME
    if (normalizedPhaseId !in family.definition.stepIds) {
      return WorkflowUpdateResult.Error(
        workflowId,
        "Unknown runtime phase '$normalizedPhaseId'. Allowed: ${family.definition.stepIds.joinToString()}.",
      )
    }
    val request = BlockedPhaseRetryRequest(workflowId, normalizedPhaseId, normalizedReason)
    val persistence = database.transaction(dbOverride) { unitOfWork ->
      retryInTransaction(unitOfWork, request)
    }
    persistence.projectionArtifactsJson?.let { artifactsJson ->
      DecompositionManifestProjectionSupport.requireWritten(
        decompositionManifestWriter.writeProjectionFromWorkflowState(
          repositoryRoot.path,
          artifactsJson,
          decompositionManifestValidator,
          decompositionManifestStore,
        ),
        "Blocked-phase retry reopened the durable goal child but could not write " +
          "its decomposition manifest projection.",
      )
    }
    return persistence.result
  }

  private fun retryInTransaction(
    unitOfWork: UnitOfWork,
    request: BlockedPhaseRetryRequest,
  ): BlockedPhaseRetryPersistence {
    val family = WorkflowFamily.TASK_RUNTIME
    val existing = family.get(unitOfWork.workflowStates, request.workflowId)
      ?: return BlockedPhaseRetryPersistence.error(
        WorkflowUpdateResult.Error(
          request.workflowId,
          "Unknown runtime workflow_id '${request.workflowId}'.",
          unitOfWork.dbPath.toString(),
        ),
      )
    if (existing.workflowStatus in family.definition.terminalStatuses) {
      return BlockedPhaseRetryPersistence.error(
        WorkflowUpdateResult.Error(
          request.workflowId,
          "Runtime workflow '${request.workflowId}' is already terminal with status '${existing.workflowStatus}'.",
          unitOfWork.dbPath.toString(),
        ),
      )
    }
    val artifacts = decodeWorkflowArtifacts(existing.artifactsJson)
    val phaseRecords = decodeFeatureTaskRuntimePhaseRecords(artifacts)
    val ledger = FeatureTaskRuntimePhaseLedgerDecoder.decode(artifacts)
    val blockedRecord = phaseRecords[request.phaseId]
      ?: return BlockedPhaseRetryPersistence.error(
        WorkflowUpdateResult.Error(
          request.workflowId,
          "Runtime workflow '${request.workflowId}' has no durable phase record for '${request.phaseId}'.",
          unitOfWork.dbPath.toString(),
        ),
      )
    return if (blockedRecord.status != "blocked") {
      BlockedPhaseRetryPersistence.error(
        WorkflowUpdateResult.Error(
          request.workflowId,
          "Runtime workflow '${request.workflowId}' phase '${request.phaseId}' is " +
            "'${blockedRecord.status}', not blocked.",
          unitOfWork.dbPath.toString(),
        ),
      )
    } else {
      persistBlockedPhaseRetry(
        unitOfWork,
        existing,
        request,
        BlockedPhaseRetryState(phaseRecords, ledger, blockedRecord),
      )
    }
  }

  private fun persistBlockedPhaseRetry(
    unitOfWork: UnitOfWork,
    existing: WorkflowStateSnapshot,
    request: BlockedPhaseRetryRequest,
    state: BlockedPhaseRetryState,
  ): BlockedPhaseRetryPersistence {
    val updatedRecords = state.reopenedPhaseRecords()
    val retryEntry = FeatureTaskRuntimePhaseLedgerEntry(
      action = FeatureTaskRuntimePhaseLedgerAction.RETRY,
      sequenceNumber = (state.ledger.maxOfOrNull { it.sequenceNumber } ?: -1) + 1,
      timestamp = OffsetDateTime.now(ZoneOffset.UTC).toString(),
      phaseId = request.phaseId,
      attemptCount = state.blockedRecord.attemptCount,
      resolvedAgentId = state.blockedRecord.resolvedAgentId,
    )
    val input = WorkflowUpdateInput(
      workflowStatus = "running",
      currentStepId = request.phaseId,
      stepUpdates = listOf(
        mapOf(
          "step_id" to request.phaseId,
          "status" to "pending",
          "attempt_count" to 0,
        ),
      ),
      artifactsPatch = mapOf(
        FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY to
          updatedRecords.mapValues { (_, record) -> record.toArtifactMap() },
        FEATURE_TASK_RUNTIME_PHASE_LEDGER_ARTIFACT_KEY to
          (state.ledger.map { it.toArtifactMap() } + retryEntry.toArtifactMap()).takeLast(
            FEATURE_TASK_RUNTIME_PHASE_LEDGER_LIMIT,
          ),
        FEATURE_TASK_RUNTIME_OPERATOR_BLOCK_RETRY_ARTIFACT_KEY to mapOf(
          "phase_id" to request.phaseId,
          "reason" to request.reason,
          "retried_at" to OffsetDateTime.now(ZoneOffset.UTC).toString(),
          "previous_blocked_reason" to state.blockedRecord.blockedReason,
          "previous_blocked_record" to state.blockedRecord.toArtifactMap(),
        ),
      ),
      sessionId = "",
    )
    val family = WorkflowFamily.TASK_RUNTIME
    val updated = engine.updateRecord(family.definition, existing, input)
    family.save(unitOfWork.workflowStates, updated)
    val projectionArtifactsJson = engine.updateGoalParentForBlockedPhaseRetry(
      unitOfWork = unitOfWork,
      childWorkflowId = request.workflowId,
      childArtifacts = decodeWorkflowArtifacts(updated.artifactsJson),
      phaseId = request.phaseId,
      validator = decompositionManifestValidator,
    )
    return BlockedPhaseRetryPersistence(
      result = buildUpdateOk(engine, family.definition, updated, input, unitOfWork.dbPath.toString()),
      projectionArtifactsJson = projectionArtifactsJson,
    )
  }
}

private data class BlockedPhaseRetryRequest(
  val workflowId: String,
  val phaseId: String,
  val reason: String,
)

private data class BlockedPhaseRetryState(
  val phaseRecords: Map<String, FeatureTaskRuntimePhaseRecord>,
  val ledger: List<FeatureTaskRuntimePhaseLedgerEntry>,
  val blockedRecord: FeatureTaskRuntimePhaseRecord,
) {
  fun reopenedPhaseRecords(): Map<String, FeatureTaskRuntimePhaseRecord> = LinkedHashMap(phaseRecords).apply {
    this[blockedRecord.phaseId] = blockedRecord.copy(
      status = "pending",
      finishedAt = null,
      durationMillis = null,
      outputArtifact = null,
      rejectedOutput = null,
      blockedReason = null,
      failureDisposition = null,
      fileManifestBefore = emptyList(),
      fileManifestAfter = emptyList(),
      fileManifestIntroduced = emptyList(),
    )
  }
}

private data class BlockedPhaseRetryPersistence(
  val result: WorkflowUpdateResult,
  val projectionArtifactsJson: String?,
) {
  companion object {
    fun error(result: WorkflowUpdateResult.Error): BlockedPhaseRetryPersistence =
      BlockedPhaseRetryPersistence(result, projectionArtifactsJson = null)
  }
}
