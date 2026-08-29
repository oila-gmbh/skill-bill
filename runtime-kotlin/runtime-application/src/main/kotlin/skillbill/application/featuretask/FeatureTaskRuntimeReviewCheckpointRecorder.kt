package skillbill.application.featuretask

import skillbill.application.goalrunner.GoalSubtaskReviewSummaryReducer
import skillbill.application.decomposition.decodeArtifacts
import skillbill.application.workflow.WorkflowFamily
import skillbill.error.InvalidWorkflowStateSchemaError
import skillbill.goalrunner.model.UnaddressedFinding
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.db.UnitOfWork
import skillbill.review.model.ReviewFindingVerdict
import skillbill.workflow.goal.model.GOAL_SUBTASK_REVIEW_RESULTS_ARTIFACT_KEY
import skillbill.workflow.goal.model.GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY
import skillbill.workflow.goal.model.GoalSubtaskReviewArtifactDecoder
import skillbill.workflow.goal.model.GoalSubtaskReviewState
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_FINDING_VERIFICATION_BOUNDARY_SELECTION_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_FINDING_VERIFICATION_CHECKPOINT_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_FINDING_VERIFICATION_DISPOSITIONS_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_REVIEW_GENERATION_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFindingVerificationDisposition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerificationBoundaryHeadingProvenance

internal class FeatureTaskRuntimeReviewCheckpointRecorder(
  private val database: DatabaseSessionFactory,
  private val workflowPersistence: FeatureTaskRuntimeWorkflowPersistence,
  private val runtimeOwnedPersistence: RuntimeOwnedPersistenceBoundary,
) {
  internal fun persistReviewGenerationInvalidation(workflowId: String, dbOverride: String? = null): Int? =
    database.transaction(dbOverride) { unitOfWork ->
      val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, workflowId)
        ?: return@transaction null
      val artifacts = decodeArtifacts(record.artifactsJson)
      val storedGeneration = reviewGenerationFrom(artifacts)
      val existingRecords = phaseRecordsFrom(artifacts)
      val previousReview = existingRecords[FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW]
        ?: return@transaction storedGeneration
      val tombstone = FeatureTaskRuntimePhaseRecord(
        phaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW,
        status = STATUS_RUNNING,
        attemptCount = previousReview.attemptCount,
        startedAt = previousReview.startedAt,
        firstStartedAt = previousReview.firstStartedAt,
        resolvedAgentId = REVIEW_INVALIDATION_AGENT_ID,
      )
      val updatedRecords = LinkedHashMap(existingRecords).apply {
        put(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW, tombstone)
      }
      val nextGeneration = storedGeneration + 1
      val patch = linkedMapOf<String, Any?>(
        FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY to
          updatedRecords.mapValues { (_, value) -> value.toArtifactMap() },
        FEATURE_TASK_RUNTIME_REVIEW_GENERATION_ARTIFACT_KEY to nextGeneration,
      )
      GoalSubtaskReviewArtifactDecoder.decode(artifacts)?.state?.let { state ->
        patch[GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY] = GoalSubtaskReviewState.initial(
          reviewBaseSha = state.reviewBaseSha,
          baselineUntrackedPaths = state.baselineUntrackedPaths,
          codeReviewMode = state.codeReviewMode,
        ).toArtifactMap()
        patch[GOAL_SUBTASK_REVIEW_RESULTS_ARTIFACT_KEY] = emptyMap<String, String>()
        unitOfWork.unaddressedFindings.clearWorkflowLedger(workflowId)
      }
      workflowPersistence.persistPatch(
        unitOfWork.workflowStates,
        record,
        patch,
        WorkflowRowAdvance(
          currentStepId = record.currentStepId,
          workflowStatus = record.workflowStatus,
          stepUpdates = stepUpdatesFrom(updatedRecords),
        ),
      )
      nextGeneration
    }
  internal fun reconcileReviewGeneration(workflowId: String, dbOverride: String? = null): Int =
    database.transaction(dbOverride) { unitOfWork ->
      val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, workflowId)
        ?: return@transaction 0
      val artifacts = decodeArtifacts(record.artifactsJson)
      val storedGeneration = reviewGenerationFrom(artifacts)
      val tombstoned = phaseRecordsFrom(artifacts)[FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW]
        ?.resolvedAgentId == REVIEW_INVALIDATION_AGENT_ID
      if (!tombstoned || storedGeneration > 0) return@transaction storedGeneration
      workflowPersistence.persistPatch(
        unitOfWork.workflowStates,
        record,
        mapOf(FEATURE_TASK_RUNTIME_REVIEW_GENERATION_ARTIFACT_KEY to 1),
      )
      1
    }
  internal fun invalidateQuarantinedProducerRecord(
    workflowId: String,
    producerPhaseId: String,
    loopId: String,
    edgeIteration: Int,
    dbOverride: String? = null,
  ): Boolean = database.transaction(dbOverride) { unitOfWork ->
    val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, workflowId)
      ?: return@transaction false
    val artifacts = decodeArtifacts(record.artifactsJson)
    val existingRecords = phaseRecordsFrom(artifacts)
    val previous = existingRecords[producerPhaseId] ?: return@transaction true
    if (previous.status != STATUS_COMPLETED) {
      return@transaction true
    }
    val invalidated = previous.copy(
      status = STATUS_RUNNING,
      finishedAt = null,
      outputArtifact = null,
      rejectedOutput = previous.outputArtifact ?: previous.rejectedOutput,
      loopId = loopId,
      edgeIteration = edgeIteration,
    )
    val updatedRecords = LinkedHashMap(existingRecords).apply { put(producerPhaseId, invalidated) }
    workflowPersistence.persistPatch(
      unitOfWork.workflowStates,
      record,
      mapOf(
        FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY to
          updatedRecords.mapValues { (_, value) -> value.toArtifactMap() },
      ),
      WorkflowRowAdvance(
        currentStepId = record.currentStepId,
        workflowStatus = record.workflowStatus,
        stepUpdates = stepUpdatesFrom(updatedRecords),
      ),
    )
    true
  }

  internal fun recordedFindingVerdicts(
    output: Map<String, Any?>,
    dbOverride: String? = null,
  ): List<ReviewFindingVerdict> {
    val reviewRunId = GoalSubtaskReviewSummaryReducer.reviewRunIdOf(output) ?: return emptyList()
    return runtimeOwnedPersistence.requiredRead(
      seam = "FeatureTaskRuntimePhaseRecorder.recordedFindingVerdicts",
      expected = "runtime-owned finding verdicts",
      dbOverride = dbOverride,
    ) { unitOfWork ->
      unitOfWork.reviews.fetchFindingVerdicts(reviewRunId)
    }
  }

  internal fun fetchUnaddressedLedger(
    workflowId: String,
    dbOverride: String? = null,
  ): List<UnaddressedFinding> = database.transaction(dbOverride) { unitOfWork ->
    unitOfWork.unaddressedFindings.fetchWorkflowLedger(workflowId)
  }

  internal fun appendRejectedVerificationFindings(
    workflowId: String,
    passNumber: Int,
    rejected: List<UnaddressedFinding>,
    dbOverride: String? = null,
  ) {
    if (rejected.isEmpty()) return
    database.transaction(dbOverride) { unitOfWork ->
      val existing = unitOfWork.unaddressedFindings.fetchWorkflowLedger(workflowId)
      val rejectedById = rejected.mapNotNull { finding ->
        finding.findingId?.let { id -> id to finding }
      }.toMap()
      val mergedExisting = existing.map { finding ->
        val rejection = finding.findingId?.let { rejectedById[it] } ?: return@map finding
        finding.copy(
          verificationDisposition = rejection.verificationDisposition,
          verificationReason = rejection.verificationReason,
        )
      }
      val existingIds = existing.mapNotNull { it.findingId }.toSet()
      val appended = rejected.filter { it.findingId !in existingIds }
      unitOfWork.unaddressedFindings.replaceLedgerForPass(
        workflowId,
        passNumber,
        mergedExisting + appended,
      )
    }
  }

  internal fun loadFindingVerificationCheckpoint(
    workflowId: String,
    dbOverride: String? = null,
  ): List<FeatureTaskRuntimeFindingVerificationDisposition>? = database.transaction(dbOverride) { unitOfWork ->
    val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, workflowId) ?: return@transaction null
    val artifacts = decodeArtifacts(record.artifactsJson)
    findingVerificationCheckpointFrom(artifacts[FEATURE_TASK_RUNTIME_FINDING_VERIFICATION_CHECKPOINT_ARTIFACT_KEY])
  }

  internal fun loadFindingVerificationBoundarySelection(
    workflowId: String,
    dbOverride: String? = null,
  ): Map<String, List<FeatureTaskRuntimeVerificationBoundaryHeadingProvenance>>? =
    database.transaction(dbOverride) { unitOfWork ->
      val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, workflowId) ?: return@transaction null
      val artifacts = decodeArtifacts(record.artifactsJson)
      findingVerificationBoundarySelectionFrom(
        artifacts[FEATURE_TASK_RUNTIME_FINDING_VERIFICATION_BOUNDARY_SELECTION_ARTIFACT_KEY],
      )
    }

  internal fun persistFindingVerificationBoundarySelection(
    workflowId: String,
    selections: Map<String, List<FeatureTaskRuntimeVerificationBoundaryHeadingProvenance>>,
    dbOverride: String? = null,
  ): Boolean {
    if (selections.isEmpty()) return false
    return database.transaction(dbOverride) { unitOfWork ->
      val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, workflowId) ?: return@transaction false
      val artifacts = decodeArtifacts(record.artifactsJson)
      workflowPersistence.persistPatch(
        unitOfWork.workflowStates,
        record,
        mapOf(
          FEATURE_TASK_RUNTIME_FINDING_VERIFICATION_BOUNDARY_SELECTION_ARTIFACT_KEY to
            selections.mapValues { (_, headings) -> headings.map { it.toArtifactMap() } },
        ),
        WorkflowRowAdvance.keepFrom(record),
      )
      true
    }
  }

  internal fun loadFindingVerificationDispositions(
    workflowId: String,
    dbOverride: String? = null,
  ): List<FeatureTaskRuntimeFindingVerificationDisposition>? = database.transaction(dbOverride) { unitOfWork ->
    val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, workflowId) ?: return@transaction null
    val artifacts = decodeArtifacts(record.artifactsJson)
    findingVerificationCheckpointFrom(artifacts[FEATURE_TASK_RUNTIME_FINDING_VERIFICATION_DISPOSITIONS_ARTIFACT_KEY])
  }

  internal fun persistFindingVerificationCheckpoint(
    workflowId: String,
    dispositions: List<FeatureTaskRuntimeFindingVerificationDisposition>,
    dbOverride: String? = null,
  ): Boolean {
    if (dispositions.isEmpty()) return false
    return database.transaction(dbOverride) { unitOfWork ->
      val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, workflowId) ?: return@transaction false
      val serialized = dispositions.map { it.toArtifactMap() }
      workflowPersistence.persistPatch(
        unitOfWork.workflowStates,
        record,
        mapOf(
          FEATURE_TASK_RUNTIME_FINDING_VERIFICATION_CHECKPOINT_ARTIFACT_KEY to serialized,
          FEATURE_TASK_RUNTIME_FINDING_VERIFICATION_DISPOSITIONS_ARTIFACT_KEY to serialized,
        ),
        WorkflowRowAdvance.keepFrom(record),
      )
      true
    }
  }

  internal fun clearFindingVerificationCheckpoint(workflowId: String, dbOverride: String? = null): Boolean =
    database.transaction(dbOverride) { unitOfWork ->
      val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, workflowId) ?: return@transaction false
      val artifacts = decodeArtifacts(record.artifactsJson)
      if (artifacts[FEATURE_TASK_RUNTIME_FINDING_VERIFICATION_CHECKPOINT_ARTIFACT_KEY] == null) return@transaction true
      workflowPersistence.persistPatch(
        unitOfWork.workflowStates,
        record,
        mapOf(FEATURE_TASK_RUNTIME_FINDING_VERIFICATION_CHECKPOINT_ARTIFACT_KEY to null),
        WorkflowRowAdvance.keepFrom(record),
      )
      true
    }

  private fun findingVerificationBoundarySelectionFrom(
    raw: Any?,
  ): Map<String, List<FeatureTaskRuntimeVerificationBoundaryHeadingProvenance>>? {
    val entries = raw as? Map<*, *> ?: return null
    return entries.mapNotNull { (findingIdRaw, headingsRaw) ->
      val findingId = findingIdRaw as? String ?: return@mapNotNull null
      val headings = FeatureTaskRuntimeVerificationBoundaryHeadingProvenance.parseList(
        headingsRaw,
        "$FEATURE_TASK_RUNTIME_FINDING_VERIFICATION_BOUNDARY_SELECTION_ARTIFACT_KEY.$findingId",
      )
      findingId to headings
    }.toMap()
  }

  private fun findingVerificationCheckpointFrom(raw: Any?): List<FeatureTaskRuntimeFindingVerificationDisposition>? {
    if (raw == null) return null
    return FeatureTaskRuntimeFindingVerificationDisposition.parseList(
      raw,
      "finding_verification_checkpoint",
    )
  }
}
