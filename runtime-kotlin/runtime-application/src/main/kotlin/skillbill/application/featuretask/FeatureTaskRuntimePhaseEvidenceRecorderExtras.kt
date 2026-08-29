package skillbill.application.featuretask

import skillbill.application.decomposition.decodeArtifacts
import skillbill.application.workflow.WorkflowFamily
import skillbill.contracts.JsonSupport
import skillbill.error.InvalidFeatureTaskRuntimeCheckpointIdentityVersionError
import skillbill.error.InvalidWorkflowStateSchemaError
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_CHECKPOINT_IDENTITIES_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_QUARANTINED_RECORDS_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeCheckpointIdentity
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeQuarantineEntry
import skillbill.workflow.taskruntime.model.QUARANTINE_REJECTION_CLASS_CHECKPOINT_IDENTITY_VERSION
import skillbill.workflow.taskruntime.model.featureTaskRuntimeAppendCheckpointIdentity
import skillbill.workflow.taskruntime.model.featureTaskRuntimeCheckpointIdentitiesFromArtifact
import skillbill.workflow.taskruntime.model.featureTaskRuntimeCheckpointIdentitiesToArtifact
import skillbill.workflow.taskruntime.model.featureTaskRuntimeCheckpointRefName
import skillbill.workflow.taskruntime.model.featureTaskRuntimeOwnedPathDigest
import skillbill.workflow.taskruntime.model.featureTaskRuntimeQuarantineEntriesFromWire
import java.time.Instant

internal fun FeatureTaskRuntimePhaseEvidenceRecorder.quarantineEntriesFrom(
  artifacts: Map<String, Any?>,
): List<FeatureTaskRuntimeQuarantineEntry> {
  val raw = artifacts[FEATURE_TASK_RUNTIME_QUARANTINED_RECORDS_ARTIFACT_KEY] ?: return emptyList()
  val map = JsonSupport.anyToStringAnyMap(raw)
    ?: throw InvalidWorkflowStateSchemaError("Feature-task-runtime quarantine record must be an object.")
  quarantineValidator.validateQuarantineRecord(map, FEATURE_TASK_RUNTIME_QUARANTINED_RECORDS_ARTIFACT_KEY)
  return featureTaskRuntimeQuarantineEntriesFromWire(raw)
}

internal fun FeatureTaskRuntimePhaseEvidenceRecorder.checkpointIdentitiesFrom(
  artifacts: Map<String, Any?>,
): List<FeatureTaskRuntimeCheckpointIdentity> = featureTaskRuntimeCheckpointIdentitiesFromArtifact(
  artifacts[FEATURE_TASK_RUNTIME_CHECKPOINT_IDENTITIES_ARTIFACT_KEY],
)

internal fun FeatureTaskRuntimePhaseEvidenceRecorder.appendCheckpointIdentityAtCurrentVersion(
  args: AppendCheckpointIdentityArgs,
): Boolean = database.transaction(args.dbOverride) { unitOfWork ->
  val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, args.workflowId)
    ?: return@transaction false
  val artifacts = decodeArtifacts(record.artifactsJson)
  val existing = checkpointIdentitiesFrom(artifacts)
  val sequenceNumber = (existing.maxOfOrNull { it.sequenceNumber } ?: -1) + 1
  val entry = FeatureTaskRuntimeCheckpointIdentity(
    sequenceNumber = sequenceNumber,
    issueKey = args.issueKey,
    subtaskId = args.subtaskId,
    checkpointRef = featureTaskRuntimeCheckpointRefName(args.issueKey, args.subtaskId, sequenceNumber),
    branch = args.branch,
    phaseId = args.phaseId,
    generation = args.generation,
    ownedPathDigest = featureTaskRuntimeOwnedPathDigest(args.ownedPaths),
    ownedPathCount = args.ownedPaths.filter(String::isNotBlank).distinct().size,
    commitSha = args.commitSha,
    recordedAt = Instant.now().toString(),
    loopId = args.loopId,
    parentSha = args.parentSha,
  )
  val updated = featureTaskRuntimeAppendCheckpointIdentity(existing, entry)
  workflowPersistence.persistPatch(
    unitOfWork.workflowStates,
    record,
    mapOf(
      FEATURE_TASK_RUNTIME_CHECKPOINT_IDENTITIES_ARTIFACT_KEY to
        featureTaskRuntimeCheckpointIdentitiesToArtifact(updated),
    ),
  )
  true
}

internal fun FeatureTaskRuntimePhaseEvidenceRecorder.quarantineCheckpointIdentitiesOnVersionDrift(
  workflowId: String,
  phaseId: String,
  generation: Int,
  dbOverride: String?,
) {
  val rejected = database.read(dbOverride) { unitOfWork ->
    val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, workflowId)
      ?: return@read null
    val artifacts = decodeArtifacts(record.artifactsJson)
    try {
      checkpointIdentitiesFrom(artifacts)
      null
    } catch (error: InvalidFeatureTaskRuntimeCheckpointIdentityVersionError) {
      error to artifacts[FEATURE_TASK_RUNTIME_CHECKPOINT_IDENTITIES_ARTIFACT_KEY].toString()
    }
  } ?: return
  val (error, rejectedPayload) = rejected
  val iteration = (generation + 1).coerceAtLeast(1)
  appendQuarantineEntry(
    workflowId,
    FeatureTaskRuntimeQuarantineEntry(
      producingPhaseId = phaseId,
      consumingPhaseId = phaseId,
      producingIteration = iteration,
      rejectionClass = QUARANTINE_REJECTION_CLASS_CHECKPOINT_IDENTITY_VERSION,
      rejectionDetail = "seam=FeatureTaskRuntimePhaseRecorder.appendCheckpointIdentity " +
        "expected=${error.expectedContractVersion} actual=${error.actualContractVersion} " +
        "cause=checkpoint-identity store predates the current contract; reset and regenerated forward",
      regenerationAttempt = 1,
      quarantinedAtIteration = iteration,
      diagnosticIdentity = null,
      rejectedRecordByteSize = rejectedPayload.toByteArray().size.toLong(),
      rejectedRecordSha256 = sha256Hex(rejectedPayload),
      diagnosticDegraded = true,
    ),
    dbOverride,
  )
  quarantineCheckpointIdentities(workflowId, dbOverride)
}
