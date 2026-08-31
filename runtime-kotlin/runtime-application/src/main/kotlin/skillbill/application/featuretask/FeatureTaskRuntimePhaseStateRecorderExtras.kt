package skillbill.application.featuretask

import skillbill.application.decomposition.decodeArtifacts
import skillbill.application.featuretask.model.FeatureTaskRuntimePhaseStateRequest
import skillbill.application.workflow.WorkflowFamily
import skillbill.contracts.JsonSupport
import skillbill.workflow.goal.model.appendBoundedHistoryBySequence
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_FINDING_VERIFICATION_BOUNDARY_SELECTION_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_FINDING_VERIFICATION_CHECKPOINT_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_FINDING_VERIFICATION_DISPOSITIONS_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_IMPLEMENTATION_ATTEMPTS_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_LEDGER_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_LEDGER_LIMIT
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeImplementationAttempt
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeImplementationAttemptStatus
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerAction.COMPLETE
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerEntry
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.featureTaskRuntimeAppendImplementationAttempt
import skillbill.workflow.taskruntime.model.featureTaskRuntimeImplementationAttemptRecordToWire
import skillbill.workflow.taskruntime.model.featureTaskRuntimeImplementationAttemptsFromWire

internal fun FeatureTaskRuntimePhaseStateRecorder.implementationAttemptsFrom(
  artifacts: Map<String, Any?>,
): List<FeatureTaskRuntimeImplementationAttempt> {
  val raw = artifacts[FEATURE_TASK_RUNTIME_IMPLEMENTATION_ATTEMPTS_ARTIFACT_KEY]
    ?: return emptyList()
  return featureTaskRuntimeImplementationAttemptsFromWire(raw)
}

internal fun FeatureTaskRuntimePhaseStateRecorder.implementationAttemptPatch(
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
      recordedAt = clock.instant().toString(),
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

internal fun FeatureTaskRuntimePhaseStateRecorder.findingVerificationCheckpointPatch(
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

internal fun FeatureTaskRuntimePhaseStateRecorder.recordCompletedPhaseWrite(
  request: FeatureTaskRuntimePhaseStateRequest,
  dbOverride: String?,
): Boolean = runtimeOwnedPersistence.requiredWrite(
  seam = "FeatureTaskRuntimePhaseRecorder.recordCompletedPhase",
  expected = "runtime-owned completed phase state",
  dbOverride = dbOverride,
) { unitOfWork ->
  val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, request.workflowId)
    ?: return@requiredWrite false
  val artifacts = decodeArtifacts(record.artifactsJson)
  val existingRecords = phaseRecordsFrom(artifacts)
  val updatedRecords = LinkedHashMap(existingRecords).apply {
    put(request.phaseId, phaseRecordFor(request, existingRecords[request.phaseId], clock.instant().toString()))
  }
  val ledger = phaseLedgerFrom(artifacts)
  val completion = FeatureTaskRuntimePhaseLedgerEntry(
    action = COMPLETE,
    sequenceNumber = (ledger.maxOfOrNull { it.sequenceNumber } ?: -1) + 1,
    timestamp = clock.instant().toString(),
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
      findingVerificationCheckpointPatch(request),
    WorkflowRowAdvance(request.phaseId, workflowStatusFor(request), stepUpdatesFrom(updatedRecords)),
  )
  true
}

internal fun FeatureTaskRuntimePhaseStateRecorder.phaseRecordFor(
  request: FeatureTaskRuntimePhaseStateRequest,
  previous: FeatureTaskRuntimePhaseRecord?,
  now: String,
): FeatureTaskRuntimePhaseRecord = featureTaskRuntimePhaseRecordFor(request, previous, now)
