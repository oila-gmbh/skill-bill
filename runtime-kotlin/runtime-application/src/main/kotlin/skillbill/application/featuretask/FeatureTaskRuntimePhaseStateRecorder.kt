package skillbill.application.featuretask

import skillbill.application.decomposition.decodeArtifacts
import skillbill.application.featuretask.model.FeatureTaskRuntimePhaseStateRequest
import skillbill.contracts.JsonSupport
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.workflow.goal.model.appendBoundedHistoryBySequence
import skillbill.workflow.taskruntime.FeatureTaskRuntimeImplementationAttemptValidator
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_FINDING_VERIFICATION_BOUNDARY_SELECTION_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_FINDING_VERIFICATION_CHECKPOINT_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_FINDING_VERIFICATION_DISPOSITIONS_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_IMPLEMENTATION_ATTEMPTS_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_LEDGER_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_LEDGER_LIMIT
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFindingVerificationDisposition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeImplementationAttempt
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeImplementationAttemptStatus
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeOperatorBlockRetry
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerEntry
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.featureTaskRuntimeAppendImplementationAttempt
import skillbill.workflow.taskruntime.model.featureTaskRuntimeImplementationAttemptRecordToWire
import skillbill.workflow.taskruntime.model.featureTaskRuntimeImplementationAttemptsFromWire
import skillbill.application.workflow.WorkflowFamily
import skillbill.error.InvalidWorkflowStateSchemaError
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerAction.COMPLETE
import java.time.Instant

internal class FeatureTaskRuntimePhaseStateRecorder(
  private val database: DatabaseSessionFactory,
  private val workflowPersistence: FeatureTaskRuntimeWorkflowPersistence,
  private val runtimeOwnedPersistence: RuntimeOwnedPersistenceBoundary,
  private val implementationAttemptValidator: FeatureTaskRuntimeImplementationAttemptValidator,
) {
  fun recordPhaseState(request: FeatureTaskRuntimePhaseStateRequest, dbOverride: String? = null): Boolean =
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
        findingVerificationCheckpointPatch(artifacts, request)
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

  @Suppress("LongMethod", "CyclomaticComplexMethod", "ComplexCondition")
  fun recordCompletedPhase(request: FeatureTaskRuntimePhaseStateRequest, dbOverride: String? = null): Boolean {
    require(request.status == "completed" && request.finished)
    return runtimeOwnedPersistence.requiredWrite(
      seam = "FeatureTaskRuntimePhaseRecorder.recordCompletedPhase",
      expected = "runtime-owned completed phase state",
      dbOverride = dbOverride,
    ) { unitOfWork ->
      val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, request.workflowId)
        ?: return@requiredWrite false
      val artifacts = decodeArtifacts(record.artifactsJson)
      val existingRecords = phaseRecordsFrom(artifacts)
      val updatedRecords = LinkedHashMap(existingRecords).apply {
        put(request.phaseId, phaseRecordFor(request, existingRecords[request.phaseId], Instant.now().toString()))
      }
      val ledger = phaseLedgerFrom(artifacts)
      val completion = FeatureTaskRuntimePhaseLedgerEntry(
        action = COMPLETE,
        sequenceNumber = (ledger.maxOfOrNull { it.sequenceNumber } ?: -1) + 1,
        timestamp = Instant.now().toString(),
        phaseId = request.phaseId,
        attemptCount = request.attemptCount,
        resolvedAgentId = request.resolvedAgentId,
        loopId = request.loopId,
        edgeIteration = request.edgeIteration,
      )
      val updatedLedger = appendBoundedHistoryBySequence(
        ledger.map { it.toArtifactMap() },
        completion.toArtifactMap(),
        FEATURE_TASK_RUNTIME_PHASE_LEDGER_LIMIT,
      )
      workflowPersistence.persistPatch(
        unitOfWork.workflowStates,
        record,
        mapOf(
          FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY to
            updatedRecords.mapValues { (_, value) -> value.toArtifactMap() },
          FEATURE_TASK_RUNTIME_PHASE_LEDGER_ARTIFACT_KEY to updatedLedger,
        ) + implementationAttemptPatch(artifacts, request, FeatureTaskRuntimeImplementationAttemptStatus.COMPLETED) +
          findingVerificationCheckpointPatch(artifacts, request),
        WorkflowRowAdvance(request.phaseId, workflowStatusFor(request), stepUpdatesFrom(updatedRecords)),
      )
      true
    }
  }

  private fun implementationAttemptPatch(
    artifacts: Map<String, Any?>,
    request: FeatureTaskRuntimePhaseStateRequest,
    attemptStatus: FeatureTaskRuntimeImplementationAttemptStatus,
  ): Map<String, Any?> {
    if (!FeatureTaskRuntimePhaseWorkflowDefinition.isMutatingPhase(request.phaseId)) return emptyMap()
    val produced = request.normalizedOutput?.envelope
      ?.let { JsonSupport.anyToStringAnyMap(it["produced_outputs"]) }
    val value = produced?.get("value")?.toString()?.trim().orEmpty()
    if (produced == null || value.isBlank()) return emptyMap()
    val prompt = produced["prompt"]?.toString()?.trim()?.takeIf(String::isNotBlank)
    val existing = implementationAttemptsFrom(artifacts)
    val appended = featureTaskRuntimeAppendImplementationAttempt(
      existing = existing,
      entry = FeatureTaskRuntimeImplementationAttempt(
        sequenceNumber = (existing.maxOfOrNull { it.sequenceNumber } ?: -1) + 1,
        phaseId = request.phaseId,
        attemptNumber = request.attemptCount,
        agentId = request.resolvedAgentId,
        status = attemptStatus,
        recordedAt = Instant.now().toString(),
        value = value,
        loopId = request.loopId,
        edgeIteration = request.edgeIteration,
        failureDisposition = request.failureDisposition,
        prompt = prompt,
      ),
    )
    val wire = featureTaskRuntimeImplementationAttemptRecordToWire(appended)
    implementationAttemptValidator.validateImplementationAttemptRecord(
      wire,
      FEATURE_TASK_RUNTIME_IMPLEMENTATION_ATTEMPTS_ARTIFACT_KEY,
    )
    return mapOf(FEATURE_TASK_RUNTIME_IMPLEMENTATION_ATTEMPTS_ARTIFACT_KEY to wire)
  }
  fun recordIncompleteImplementationAttempt(
    request: FeatureTaskRuntimePhaseStateRequest,
    dbOverride: String? = null,
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
  fun loadImplementationAttempts(
    workflowId: String,
    dbOverride: String? = null,
  ): List<FeatureTaskRuntimeImplementationAttempt>? = database.read(dbOverride) { unitOfWork ->
    val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, workflowId)
      ?: return@read null
    implementationAttemptsFrom(decodeArtifacts(record.artifactsJson))
  }

  private fun implementationAttemptsFrom(artifacts: Map<String, Any?>): List<FeatureTaskRuntimeImplementationAttempt> {
    val raw = artifacts[FEATURE_TASK_RUNTIME_IMPLEMENTATION_ATTEMPTS_ARTIFACT_KEY] ?: return emptyList()
    return featureTaskRuntimeImplementationAttemptsFromWire(raw)
  }
  fun clearBackwardEdgeContext(workflowId: String, phaseIds: Collection<String>, dbOverride: String? = null): Boolean =
    database.transaction(dbOverride) { unitOfWork ->
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
  fun loadPhaseRecords(workflowId: String, dbOverride: String? = null): Map<String, FeatureTaskRuntimePhaseRecord>? =
    database.read(dbOverride) { unitOfWork ->
      val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, workflowId)
        ?: return@read null
      phaseRecordsFrom(decodeArtifacts(record.artifactsJson))
    }

  fun loadOperatorBlockRetry(workflowId: String, dbOverride: String? = null): FeatureTaskRuntimeOperatorBlockRetry? =
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
  fun loadPhaseLedger(workflowId: String, dbOverride: String? = null): List<FeatureTaskRuntimePhaseLedgerEntry>? =
    database.read(dbOverride) { unitOfWork ->
      val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, workflowId)
        ?: return@read null
      phaseLedgerFrom(decodeArtifacts(record.artifactsJson))
    }

  @Suppress("UnusedParameter")
  private fun findingVerificationCheckpointPatch(
    artifacts: Map<String, Any?>,
    request: FeatureTaskRuntimePhaseStateRequest,
  ): Map<String, Any?> {
    if (request.phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS) return emptyMap()
    if (request.finished && request.status == "completed") {
      val dispositions = request.normalizedOutput?.envelope
        ?.let(FeatureTaskRuntimeOutputVerification::dispositionsFrom)
        .orEmpty()
      return buildMap {
        put(FEATURE_TASK_RUNTIME_FINDING_VERIFICATION_CHECKPOINT_ARTIFACT_KEY, null)
        put(FEATURE_TASK_RUNTIME_FINDING_VERIFICATION_BOUNDARY_SELECTION_ARTIFACT_KEY, null)
        if (dispositions.isNotEmpty()) {
          put(
            FEATURE_TASK_RUNTIME_FINDING_VERIFICATION_DISPOSITIONS_ARTIFACT_KEY,
            dispositions.map { it.toArtifactMap() },
          )
        }
      }
    }
    val checkpoint = request.findingVerificationCheckpoint?.takeIf { it.isNotEmpty() } ?: return emptyMap()
    return mapOf(
      FEATURE_TASK_RUNTIME_FINDING_VERIFICATION_CHECKPOINT_ARTIFACT_KEY to checkpoint.map { it.toArtifactMap() },
    )
  }

  private fun phaseRecordFor(
    request: FeatureTaskRuntimePhaseStateRequest,
    previous: FeatureTaskRuntimePhaseRecord?,
    now: String,
  ): FeatureTaskRuntimePhaseRecord = featureTaskRuntimePhaseRecordFor(request, previous, now)
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
