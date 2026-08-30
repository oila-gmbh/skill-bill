package skillbill.application.featuretask

import skillbill.application.decomposition.decodeArtifacts
import skillbill.application.featuretask.model.FeatureTaskRuntimePhaseStateRequest
import skillbill.application.workflow.WorkflowFamily
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.workflow.taskruntime.FeatureTaskRuntimeImplementationAttemptValidator
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeImplementationAttempt
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeImplementationAttemptStatus
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeOperatorBlockRetry
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerEntry
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseRecord
import java.time.Instant

internal class FeatureTaskRuntimePhaseStateRecorder(
  internal val database: DatabaseSessionFactory,
  internal val workflowPersistence: FeatureTaskRuntimeWorkflowPersistence,
  internal val runtimeOwnedPersistence: RuntimeOwnedPersistenceBoundary,
  internal val implementationAttemptValidator: FeatureTaskRuntimeImplementationAttemptValidator,
) : FeatureTaskRuntimePhaseStateApi {
  override fun recordPhaseState(request: FeatureTaskRuntimePhaseStateRequest, dbOverride: String?): Boolean =
    database.transaction(dbOverride) { unitOfWork ->
      val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, request.workflowId)
        ?: return@transaction false
      val artifacts = decodeArtifacts(record.artifactsJson)
      val existingRecords = phaseRecordsFrom(artifacts)
      val now = Instant.now().toString()
      val previous = existingRecords[request.phaseId]
      val phaseRecord = phaseRecordFor(request, previous, now)
      val updatedRecords = LinkedHashMap(existingRecords).apply { put(request.phaseId, phaseRecord) }
      val patch = mapOf(
        FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY to
          updatedRecords.mapValues { (_, value) -> value.toArtifactMap() },
      ) + implementationAttemptPatch(artifacts, request, attemptStatusFor(request)) +
        findingVerificationCheckpointPatch(request)
      workflowPersistence.persistPatch(
        unitOfWork.workflowStates,
        record,
        patch,
        WorkflowRowAdvance(
          currentStepId = request.phaseId,
          workflowStatus = workflowStatusFor(request),
          stepUpdates = stepUpdatesFrom(updatedRecords),
        ),
      )
      true
    }

  override fun recordCompletedPhase(request: FeatureTaskRuntimePhaseStateRequest, dbOverride: String?): Boolean {
    require(request.status == "completed" && request.finished)
    return recordCompletedPhaseWrite(request, dbOverride)
  }

  override fun recordIncompleteImplementationAttempt(
    request: FeatureTaskRuntimePhaseStateRequest,
    dbOverride: String?,
  ): Boolean = database.transaction(dbOverride) { unitOfWork ->
    val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, request.workflowId)
      ?: return@transaction false
    val patch = implementationAttemptPatch(
      decodeArtifacts(record.artifactsJson),
      request,
      FeatureTaskRuntimeImplementationAttemptStatus.INCOMPLETE,
    )
    if (patch.isEmpty()) return@transaction false
    workflowPersistence.persistPatch(unitOfWork.workflowStates, record, patch)
    true
  }
  override fun loadImplementationAttempts(
    workflowId: String,
    dbOverride: String?,
  ): List<FeatureTaskRuntimeImplementationAttempt>? = database.read(dbOverride) { unitOfWork ->
    val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, workflowId)
      ?: return@read null
    implementationAttemptsFrom(decodeArtifacts(record.artifactsJson))
  }

  override fun clearBackwardEdgeContext(
    workflowId: String,
    phaseIds: Collection<String>,
    dbOverride: String?,
  ): Boolean = database.transaction(dbOverride) { unitOfWork ->
    val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, workflowId)
      ?: return@transaction false
    val existingRecords = phaseRecordsFrom(decodeArtifacts(record.artifactsJson))
    val cleared = LinkedHashMap(existingRecords)
    phaseIds.forEach { phaseId ->
      val previous = existingRecords[phaseId] ?: return@forEach
      if (previous.loopId == null && previous.edgeIteration == null) {
        return@forEach
      }
      cleared[phaseId] = previous.copy(loopId = null, edgeIteration = null)
    }
    if (cleared == existingRecords) {
      return@transaction true
    }
    workflowPersistence.persistPatch(
      unitOfWork.workflowStates,
      record,
      mapOf(
        FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY to
          cleared.mapValues { (_, value) -> value.toArtifactMap() },
      ),
    )
    true
  }
  override fun loadPhaseRecords(workflowId: String, dbOverride: String?): Map<String, FeatureTaskRuntimePhaseRecord>? =
    database.read(dbOverride) { unitOfWork ->
      val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, workflowId)
        ?: return@read null
      phaseRecordsFrom(decodeArtifacts(record.artifactsJson))
    }

  override fun loadOperatorBlockRetry(workflowId: String, dbOverride: String?): FeatureTaskRuntimeOperatorBlockRetry? =
    database.read(dbOverride) { unitOfWork ->
      val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, workflowId)
        ?: return@read null
      val artifacts = decodeArtifacts(record.artifactsJson)
      val retry = operatorBlockRetryFrom(artifacts) ?: return@read null
      val phaseEntries = phaseLedgerFrom(artifacts).filter { it.phaseId == retry.phaseId }
      val latestRetry = phaseEntries.lastOrNull { it.action == FeatureTaskRuntimePhaseLedgerAction.RETRY }
        ?: return@read null
      val settledAfterRetry = phaseEntries.any { entry ->
        entry.sequenceNumber > latestRetry.sequenceNumber &&
          entry.action in setOf(
            FeatureTaskRuntimePhaseLedgerAction.BLOCKED,
            FeatureTaskRuntimePhaseLedgerAction.COMPLETE,
          )
      }
      retry.takeUnless { settledAfterRetry }
    }
  override fun loadPhaseLedger(workflowId: String, dbOverride: String?): List<FeatureTaskRuntimePhaseLedgerEntry>? =
    database.read(dbOverride) { unitOfWork ->
      val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, workflowId)
        ?: return@read null
      phaseLedgerFrom(decodeArtifacts(record.artifactsJson))
    }
}

internal fun featureTaskRuntimePhaseRecordFor(
  request: FeatureTaskRuntimePhaseStateRequest,
  previous: FeatureTaskRuntimePhaseRecord?,
  now: String,
): FeatureTaskRuntimePhaseRecord {
  val firstStartedAt = previous?.firstStartedAt ?: now
  val startedAt = if (request.status == PHASE_RECORDER_STATUS_RUNNING || previous == null) now else previous.startedAt
  val carryForward = previous != null &&
    previous.attemptCount == request.attemptCount &&
    previous.resolvedAgentId == request.resolvedAgentId
  val launched = when {
    request.launchOutcomeKnown -> request.launchedModel to request.launchedEffort
    carryForward -> previous.launchedModel to previous.launchedEffort
    else -> null to null
  }
  return FeatureTaskRuntimePhaseRecord(
    phaseId = request.phaseId,
    status = request.status,
    attemptCount = request.attemptCount,
    startedAt = startedAt,
    firstStartedAt = firstStartedAt,
    finishedAt = if (request.finished) now else null,
    durationMillis = if (request.finished) durationMillis(startedAt, now) else null,
    resolvedAgentId = request.resolvedAgentId,
    outputArtifact = request.outputArtifact,
    rejectedOutput = request.rejectedOutput,
    blockedReason = request.blockedReason,
    failureDisposition = request.failureDisposition,
    fileManifestBefore = request.fileManifestBefore,
    fileManifestAfter = request.fileManifestAfter,
    fileManifestIntroduced = request.fileManifestIntroduced,
    loopId = request.loopId,
    edgeIteration = request.edgeIteration,
    reviewPassNumber = request.reviewPassNumber,
    repairEvidence = request.repairEvidence,
    launchedModel = launched.first,
    launchedEffort = launched.second,
    reviewRunId = request.reviewRunId ?: previous?.reviewRunId,
  )
}
