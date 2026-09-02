package skillbill.application.featuretask

import skillbill.application.featuretask.model.FeatureTaskRuntimePhaseStateRequest
import skillbill.application.normalizeIssueKey
import skillbill.application.workflow.model.WorkflowFamily
import skillbill.application.workflow.toRecord
import skillbill.error.InvalidWorkflowStateSchemaError
import skillbill.error.WorkflowIssueKeyConflictError
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerOwnership
import skillbill.ports.workflow.WorkflowStateRepository
import skillbill.ports.workflow.model.FeatureTaskWorkflowMode
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.WorkflowSnapshotValidator
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.engine.model.WorkflowUpdateInput
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_STATUS_BLOCKED
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_STATUS_PAUSED
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_STATUS_PENDING
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeImplementationAttemptStatus
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseRecord
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant

internal data class WorkflowRowAdvance(
  val currentStepId: String,
  val workflowStatus: String,
  val stepUpdates: List<Map<String, Any?>>? = null,
) {
  companion object {
    fun keepFrom(record: WorkflowStateSnapshot): WorkflowRowAdvance =
      WorkflowRowAdvance(currentStepId = record.currentStepId, workflowStatus = record.workflowStatus)
  }
}

class FeatureTaskRuntimeWorkflowPersistence(
  private val database: DatabaseSessionFactory,
  workflowSnapshotValidator: WorkflowSnapshotValidator,
) : FeatureTaskRuntimePhaseWorkflowApi {
  private val engine: WorkflowEngine = WorkflowEngine(workflowSnapshotValidator)

  internal fun persistPatch(
    workflowStates: WorkflowStateRepository,
    record: WorkflowStateSnapshot,
    patch: Map<String, Any?>,
    advance: WorkflowRowAdvance = WorkflowRowAdvance.keepFrom(record),
  ) {
    val updated = engine.updateRecord(
      WorkflowFamily.TASK_RUNTIME.definition,
      record,
      WorkflowUpdateInput(
        workflowStatus = advance.workflowStatus,
        currentStepId = advance.currentStepId,
        stepUpdates = advance.stepUpdates,
        artifactsPatch = patch,
        sessionId = record.sessionId.orEmpty(),
      ),
    )
    WorkflowFamily.TASK_RUNTIME.save(workflowStates, updated)
  }

  override fun existingWorkflowMode(workflowId: String, dbOverride: String?): FeatureTaskWorkflowMode? =
    database.read(dbOverride) { unitOfWork ->
      unitOfWork.workflowStates.getFeatureTaskWorkflow(workflowId)?.mode
    }

  override fun workerOwnership(workflowId: String, dbOverride: String?): FeatureTaskRuntimeWorkerOwnership? =
    database.read(dbOverride) { unitOfWork ->
      unitOfWork.workflowStates.getFeatureTaskRuntimeWorkerOwnership(workflowId)
    }
  override fun ensureWorkflowOpen(
    workflowId: String,
    sessionId: String,
    dbOverride: String?,
    issueKey: String?,
  ): Boolean = database.transaction(dbOverride) { unitOfWork ->
    val normalizedIssueKey = normalizeIssueKey(issueKey)
    val existing = unitOfWork.workflowStates.getFeatureTaskRuntimeWorkflow(workflowId)
    if (existing != null) {
      val persistedIssueKey = existing.issueKey
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?.let(::normalizeIssueKey)
      if (
        persistedIssueKey != null &&
        normalizedIssueKey != null &&
        persistedIssueKey != normalizedIssueKey
      ) {
        throw WorkflowIssueKeyConflictError(workflowId, persistedIssueKey, normalizedIssueKey)
      }
      if (persistedIssueKey == null && normalizedIssueKey != null) {
        unitOfWork.workflowStates.saveFeatureTaskRuntimeWorkflow(
          existing.copy(issueKey = normalizedIssueKey, sessionId = existing.sessionId.ifBlank { sessionId }),
        )
      } else if (existing.sessionId.isBlank()) {
        unitOfWork.workflowStates.saveFeatureTaskRuntimeWorkflow(existing.copy(sessionId = sessionId))
      }
      return@transaction true
    }
    val opened = engine.openRecord(
      WorkflowFamily.TASK_RUNTIME.definition,
      workflowId,
      sessionId,
      WorkflowFamily.TASK_RUNTIME.definition.defaultInitialStepId,
    )
    WorkflowFamily.TASK_RUNTIME.saveRecord(
      unitOfWork.workflowStates,
      opened.toRecord().copy(issueKey = normalizedIssueKey),
    )
    true
  }
}

fun stepUpdatesFrom(records: Map<String, FeatureTaskRuntimePhaseRecord>): List<Map<String, Any?>> {
  fun stepStatusFor(record: FeatureTaskRuntimePhaseRecord): String = when {
    record.status == FEATURE_TASK_RUNTIME_PHASE_STATUS_BLOCKED -> FEATURE_TASK_RUNTIME_PHASE_STATUS_BLOCKED
    record.status == FEATURE_TASK_RUNTIME_PHASE_STATUS_PAUSED -> FEATURE_TASK_RUNTIME_PHASE_STATUS_PAUSED
    record.status == FEATURE_TASK_RUNTIME_PHASE_STATUS_PENDING -> FEATURE_TASK_RUNTIME_PHASE_STATUS_PENDING
    record.finishedAt != null -> "completed"
    record.status == "running" || record.status == "completed" -> record.status
    else -> throw InvalidWorkflowStateSchemaError(
      "Feature-task-runtime phase '${record.phaseId}' has unmappable status '${record.status}' for steps[].",
    )
  }
  return records.values.map { record ->
    linkedMapOf<String, Any?>(
      "step_id" to record.phaseId,
      "status" to stepStatusFor(record),
      "attempt_count" to record.attemptCount,
    )
  }
}

fun workflowStatusFor(request: FeatureTaskRuntimePhaseStateRequest): String = when {
  request.status == FEATURE_TASK_RUNTIME_PHASE_STATUS_PAUSED -> "paused"
  request.status == FEATURE_TASK_RUNTIME_PHASE_STATUS_BLOCKED -> "blocked"
  request.finished && request.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.definition.stepIds.last() ->
    "completed"
  else -> "running"
}

fun attemptStatusFor(request: FeatureTaskRuntimePhaseStateRequest): FeatureTaskRuntimeImplementationAttemptStatus =
  when (request.status) {
    "completed" -> FeatureTaskRuntimeImplementationAttemptStatus.COMPLETED
    FEATURE_TASK_RUNTIME_PHASE_STATUS_BLOCKED -> FeatureTaskRuntimeImplementationAttemptStatus.BLOCKED
    else -> FeatureTaskRuntimeImplementationAttemptStatus.INCOMPLETE
  }

fun durationMillis(startedAt: String, finishedAt: String): Long =
  Duration.between(Instant.parse(startedAt), Instant.parse(finishedAt)).toMillis().coerceAtLeast(0)

fun sha256Hex(value: String): String = MessageDigest.getInstance("SHA-256")
  .digest(value.toByteArray())
  .joinToString("") { "%02x".format(it) }

const val PHASE_RECORDER_STATUS_RUNNING = "running"
