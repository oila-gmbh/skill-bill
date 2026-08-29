package skillbill.application.featuretask

import skillbill.application.decomposition.decodeArtifacts
import skillbill.application.featuretask.model.FeatureTaskRuntimePhaseLedgerRequest
import skillbill.application.workflow.WorkflowFamily
import skillbill.contracts.JsonSupport
import skillbill.error.InvalidFeatureTaskRuntimeCheckpointIdentityVersionError
import skillbill.error.InvalidWorkflowStateSchemaError
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.workflow.goal.model.appendBoundedHistoryBySequence
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.FeatureTaskRuntimeQuarantineValidator
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_CHECKPOINT_IDENTITIES_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_LEDGER_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_LEDGER_LIMIT
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_QUARANTINED_RECORDS_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_RESOLVED_BRANCH_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeCheckpointIdentity
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerEntry
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeQuarantineEntry
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeResolvedBranch
import skillbill.workflow.taskruntime.model.QUARANTINE_REJECTION_CLASS_CHECKPOINT_IDENTITY_VERSION
import skillbill.workflow.taskruntime.model.featureTaskRuntimeAppendCheckpointIdentity
import skillbill.workflow.taskruntime.model.featureTaskRuntimeCheckpointIdentitiesFromArtifact
import skillbill.workflow.taskruntime.model.featureTaskRuntimeCheckpointIdentitiesToArtifact
import skillbill.workflow.taskruntime.model.featureTaskRuntimeCheckpointRefName
import skillbill.workflow.taskruntime.model.featureTaskRuntimeOwnedPathDigest
import skillbill.workflow.taskruntime.model.featureTaskRuntimeQuarantineEntriesFromWire
import skillbill.workflow.taskruntime.model.featureTaskRuntimeQuarantineRecordToWire
import java.time.Instant

internal class FeatureTaskRuntimePhaseEvidenceRecorder(
  private val database: DatabaseSessionFactory,
  private val workflowPersistence: FeatureTaskRuntimeWorkflowPersistence,
  private val quarantineValidator: FeatureTaskRuntimeQuarantineValidator,
) {
  fun appendLedgerEntry(request: FeatureTaskRuntimePhaseLedgerRequest, dbOverride: String? = null): Boolean =
    database.transaction(dbOverride) { unitOfWork ->
      val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, request.workflowId)
        ?: return@transaction false
      val artifacts = decodeArtifacts(record.artifactsJson)
      val existingEntries = phaseLedgerFrom(artifacts)
      val nextSequence = (existingEntries.maxOfOrNull { it.sequenceNumber } ?: -1) + 1
      val entry = FeatureTaskRuntimePhaseLedgerEntry(
        action = request.action,
        sequenceNumber = nextSequence,
        timestamp = Instant.now().toString(),
        phaseId = request.phaseId,
        attemptCount = request.attemptCount,
        resolvedAgentId = request.resolvedAgentId,
        fixLoopIteration = request.fixLoopIteration,
        blockedReason = request.blockedReason,
        loopId = request.loopId,
        edgeIteration = request.edgeIteration,
      )
      val updatedLedger = appendBoundedHistoryBySequence(
        existing = existingEntries.map { it.toArtifactMap() },
        entry = entry.toArtifactMap(),
        retentionLimit = FEATURE_TASK_RUNTIME_PHASE_LEDGER_LIMIT,
      )
      workflowPersistence.persistPatch(
        unitOfWork.workflowStates,
        record,
        mapOf(FEATURE_TASK_RUNTIME_PHASE_LEDGER_ARTIFACT_KEY to updatedLedger),
      )
      true
    }
  fun appendQuarantineEntry(
    workflowId: String,
    entry: FeatureTaskRuntimeQuarantineEntry,
    dbOverride: String? = null,
  ): Boolean = database.transaction(dbOverride) { unitOfWork ->
    val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, workflowId)
      ?: return@transaction false
    val artifacts = decodeArtifacts(record.artifactsJson)
    val existing = quarantineEntriesFrom(artifacts)
    val alreadyRecorded = existing.any {
      it.producingPhaseId == entry.producingPhaseId &&
        it.producingIteration == entry.producingIteration &&
        it.regenerationAttempt == entry.regenerationAttempt
    }
    if (alreadyRecorded) {
      return@transaction true
    }
    val wire = featureTaskRuntimeQuarantineRecordToWire(existing + entry)
    quarantineValidator.validateQuarantineRecord(wire, FEATURE_TASK_RUNTIME_QUARANTINED_RECORDS_ARTIFACT_KEY)
    workflowPersistence.persistPatch(
      unitOfWork.workflowStates,
      record,
      mapOf(FEATURE_TASK_RUNTIME_QUARANTINED_RECORDS_ARTIFACT_KEY to wire),
    )
    true
  }
  fun loadQuarantinedRecords(
    workflowId: String,
    dbOverride: String? = null,
  ): List<FeatureTaskRuntimeQuarantineEntry>? = database.read(dbOverride) { unitOfWork ->
    val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, workflowId)
      ?: return@read null
    quarantineEntriesFrom(decodeArtifacts(record.artifactsJson))
  }

  private fun quarantineEntriesFrom(artifacts: Map<String, Any?>): List<FeatureTaskRuntimeQuarantineEntry> {
    val raw = artifacts[FEATURE_TASK_RUNTIME_QUARANTINED_RECORDS_ARTIFACT_KEY] ?: return emptyList()
    val map = JsonSupport.anyToStringAnyMap(raw)
      ?: throw InvalidWorkflowStateSchemaError("Feature-task-runtime quarantine record must be an object.")
    quarantineValidator.validateQuarantineRecord(map, FEATURE_TASK_RUNTIME_QUARANTINED_RECORDS_ARTIFACT_KEY)
    return featureTaskRuntimeQuarantineEntriesFromWire(raw)
  }
  fun recordResolvedBranch(
    workflowId: String,
    resolvedBranch: FeatureTaskRuntimeResolvedBranch,
    dbOverride: String? = null,
  ): Boolean = database.transaction(dbOverride) { unitOfWork ->
    val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, workflowId)
      ?: return@transaction false
    val artifacts = decodeArtifacts(record.artifactsJson)
    if (resolvedBranchFrom(artifacts) != null) {
      return@transaction true
    }
    workflowPersistence.persistPatch(
      unitOfWork.workflowStates,
      record,
      mapOf(FEATURE_TASK_RUNTIME_RESOLVED_BRANCH_ARTIFACT_KEY to resolvedBranch.toArtifactMap()),
    )
    true
  }
  fun loadResolvedBranch(workflowId: String, dbOverride: String? = null): FeatureTaskRuntimeResolvedBranch? =
    database.read(dbOverride) { unitOfWork ->
      val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, workflowId)
        ?: return@read null
      resolvedBranchFrom(decodeArtifacts(record.artifactsJson))
    }
  @Suppress("LongParameterList") // one durable identity record; every field is part of its contract
  fun appendCheckpointIdentity(
    workflowId: String,
    issueKey: String,
    subtaskId: String,
    branch: String,
    phaseId: String,
    loopId: String?,
    generation: Int,
    parentSha: String?,
    ownedPaths: List<String>,
    commitSha: String,
    dbOverride: String? = null,
  ): Boolean {
    quarantineCheckpointIdentitiesOnVersionDrift(workflowId, phaseId, generation, dbOverride)
    return appendCheckpointIdentityAtCurrentVersion(
      workflowId = workflowId,
      issueKey = issueKey,
      subtaskId = subtaskId,
      branch = branch,
      phaseId = phaseId,
      loopId = loopId,
      generation = generation,
      parentSha = parentSha,
      ownedPaths = ownedPaths,
      commitSha = commitSha,
      dbOverride = dbOverride,
    )
  }

  @Suppress("LongParameterList") // mirrors appendCheckpointIdentity's durable identity contract
  private fun appendCheckpointIdentityAtCurrentVersion(
    workflowId: String,
    issueKey: String,
    subtaskId: String,
    branch: String,
    phaseId: String,
    loopId: String?,
    generation: Int,
    parentSha: String?,
    ownedPaths: List<String>,
    commitSha: String,
    dbOverride: String?,
  ): Boolean = database.transaction(dbOverride) { unitOfWork ->
    val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, workflowId)
      ?: return@transaction false
    val artifacts = decodeArtifacts(record.artifactsJson)
    val existing = checkpointIdentitiesFrom(artifacts)
    val sequenceNumber = (existing.maxOfOrNull { it.sequenceNumber } ?: -1) + 1
    val entry = FeatureTaskRuntimeCheckpointIdentity(
      sequenceNumber = sequenceNumber,
      issueKey = issueKey,
      subtaskId = subtaskId,
      checkpointRef = featureTaskRuntimeCheckpointRefName(issueKey, subtaskId, sequenceNumber),
      branch = branch,
      phaseId = phaseId,
      generation = generation,
      ownedPathDigest = featureTaskRuntimeOwnedPathDigest(ownedPaths),
      ownedPathCount = ownedPaths.filter(String::isNotBlank).distinct().size,
      commitSha = commitSha,
      recordedAt = Instant.now().toString(),
      loopId = loopId,
      parentSha = parentSha,
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
  fun loadCheckpointIdentities(
    workflowId: String,
    dbOverride: String? = null,
  ): List<FeatureTaskRuntimeCheckpointIdentity>? = database.read(dbOverride) { unitOfWork ->
    val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, workflowId)
      ?: return@read null
    checkpointIdentitiesFrom(decodeArtifacts(record.artifactsJson))
  }
  fun quarantineCheckpointIdentities(workflowId: String, dbOverride: String? = null): Boolean =
    database.transaction(dbOverride) { unitOfWork ->
      val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, workflowId)
        ?: return@transaction false
      workflowPersistence.persistPatch(
        unitOfWork.workflowStates,
        record,
        mapOf(
          FEATURE_TASK_RUNTIME_CHECKPOINT_IDENTITIES_ARTIFACT_KEY to
            featureTaskRuntimeCheckpointIdentitiesToArtifact(emptyList()),
        ),
      )
      true
    }
  private fun quarantineCheckpointIdentitiesOnVersionDrift(
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
    this.appendQuarantineEntry(
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

  private fun checkpointIdentitiesFrom(artifacts: Map<String, Any?>): List<FeatureTaskRuntimeCheckpointIdentity> =
    featureTaskRuntimeCheckpointIdentitiesFromArtifact(
      artifacts[FEATURE_TASK_RUNTIME_CHECKPOINT_IDENTITIES_ARTIFACT_KEY],
    )

  fun recordWorkflowOwnedPaths(workflowId: String, ownedPaths: List<String>, dbOverride: String? = null): Boolean =
    database.transaction(dbOverride) { unitOfWork ->
      val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, workflowId)
        ?: return@transaction false
      val resolved = resolvedBranchFrom(decodeArtifacts(record.artifactsJson)) ?: return@transaction false
      val updated = resolved.copy(workflowOwnedPaths = ownedPaths.distinct().sorted())
      workflowPersistence.persistPatch(
        unitOfWork.workflowStates,
        record,
        mapOf(FEATURE_TASK_RUNTIME_RESOLVED_BRANCH_ARTIFACT_KEY to updated.toArtifactMap()),
      )
      true
    }
}
