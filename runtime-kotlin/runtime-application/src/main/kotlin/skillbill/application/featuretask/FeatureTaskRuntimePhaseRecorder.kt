package skillbill.application.featuretask

import me.tatarka.inject.annotations.Inject
import skillbill.application.decomposition.decodeArtifacts
import skillbill.application.goalrunner.GoalSubtaskReviewSummaryReducer
import skillbill.application.model.FeatureTaskRuntimePhaseLaunchBriefing
import skillbill.application.model.FeatureTaskRuntimePhaseLedgerRequest
import skillbill.application.model.FeatureTaskRuntimePhaseStateRequest
import skillbill.application.normalizeIssueKey
import skillbill.application.workflow.WorkflowFamily
import skillbill.application.workflow.toRecord
import skillbill.boundary.OpenBoundaryMap
import skillbill.contracts.JsonSupport
import skillbill.error.InvalidFeatureTaskRuntimeHandoffProjectionError
import skillbill.error.InvalidWorkflowStateSchemaError
import skillbill.error.WorkflowIssueKeyConflictError
import skillbill.ports.persistence.ConvergenceReplayConflictException
import skillbill.ports.persistence.DatabaseSessionFactory
import skillbill.ports.persistence.ProducerOutputEvidence
import skillbill.ports.persistence.RejectedOutputDiagnosticMetadataValidator
import skillbill.ports.persistence.UnavailableConvergenceStateRepository
import skillbill.ports.persistence.UnavailableReviewGenerationRepository
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.persistence.WorkflowStateRepository
import skillbill.ports.persistence.model.FeatureTaskRuntimeWorkerOwnership
import skillbill.ports.persistence.model.FeatureTaskWorkflowMode
import skillbill.ports.persistence.model.RejectedOutputDiagnosticError
import skillbill.ports.persistence.model.AuditRepairItemResult as PersistedAuditRepairItemResult
import skillbill.workflow.FeatureTaskRuntimeHandoffEnvelopeValidator
import skillbill.workflow.FeatureTaskRuntimeHandoffFoundationValidator
import skillbill.workflow.FeatureTaskRuntimeQuarantineValidator
import skillbill.workflow.NoopFeatureTaskRuntimeQuarantineValidator
import skillbill.workflow.WorkflowEngine
import skillbill.workflow.WorkflowSnapshotValidator
import skillbill.workflow.model.WorkflowStateSnapshot
import skillbill.workflow.model.WorkflowUpdateInput
import skillbill.workflow.model.appendBoundedHistoryBySequence
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.AUDIT_REPAIR_CONTRACT_VERSION
import skillbill.workflow.taskruntime.model.AuditGap
import skillbill.workflow.taskruntime.model.AuditGapDisposition
import skillbill.workflow.taskruntime.model.AuditGapStatus
import skillbill.workflow.taskruntime.model.AuditGeneration
import skillbill.workflow.taskruntime.model.AuditGenerationIdentities
import skillbill.workflow.taskruntime.model.AuditRepairBatch
import skillbill.workflow.taskruntime.model.AuditRepairItem
import skillbill.workflow.taskruntime.model.ConvergenceIdentities
import skillbill.workflow.taskruntime.model.ConvergenceProvenance
import skillbill.workflow.taskruntime.model.ConvergenceRecord
import skillbill.workflow.taskruntime.model.ConvergenceRecordKind
import skillbill.workflow.taskruntime.model.ConvergenceStatus
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_AUDIT_REPAIR_STATE_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_DECOMPOSE_TERMINAL_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_DELIVERED_PROJECTIONS_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_BRIEFINGS_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_LEDGER_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_LEDGER_LIMIT
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_STATUS_BLOCKED
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_STATUS_PAUSED
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_QUARANTINED_RECORDS_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_RESOLVED_BRANCH_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditRepairPlan
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditRepairState
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeDecomposeTerminal
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeDeliveredProjectionRecord
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeEvidence
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeGoalContinuationArtifact
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeOperatorBlockRetry
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerEntry
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePriorGapDisposition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeProducerIteration
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeProjectionFailureClassification
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeProjectionMeasurement
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeQuarantineEntry
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairItemResult
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeResolvedBranch
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.model.GOAL_SUBTASK_REVIEW_RESULTS_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.GoalSubtaskBlockerDisposition
import skillbill.workflow.taskruntime.model.GoalSubtaskReviewArtifactDecoder
import skillbill.workflow.taskruntime.model.GoalSubtaskReviewCompactFinding
import skillbill.workflow.taskruntime.model.GoalSubtaskReviewState
import skillbill.workflow.taskruntime.model.PhaseHandoffProjectionDeclaration
import skillbill.workflow.taskruntime.model.ReplayResult
import skillbill.workflow.taskruntime.model.RepositoryCheckpoint
import skillbill.workflow.taskruntime.model.featureTaskRuntimeQuarantineEntriesFromWire
import skillbill.workflow.taskruntime.model.featureTaskRuntimeQuarantineRecordToWire
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

internal data class FeatureTaskRuntimeProjectionRejection(
  val workflowId: String,
  val consumerPhaseId: String,
  val projectionContractId: String,
  val producerIteration: FeatureTaskRuntimeProducerIteration,
  val repositoryCheckpointFingerprint: String?,
  val failureClassification: FeatureTaskRuntimeProjectionFailureClassification,
  val sourceLabel: String,
)
@Inject
@Suppress("TooManyFunctions", "LargeClass")
class FeatureTaskRuntimePhaseRecorder(
  private val database: DatabaseSessionFactory,
  private val workflowSnapshotValidator: WorkflowSnapshotValidator,
  private val handoffEnvelopeValidator: FeatureTaskRuntimeHandoffEnvelopeValidator,
  private val handoffFoundationValidator: FeatureTaskRuntimeHandoffFoundationValidator,
  private val quarantineValidator: FeatureTaskRuntimeQuarantineValidator = NoopFeatureTaskRuntimeQuarantineValidator,
  private val rejectedOutputDiagnosticMetadataValidator: RejectedOutputDiagnosticMetadataValidator = { },
) {
  private val engine: WorkflowEngine = WorkflowEngine(workflowSnapshotValidator)

  fun recordRejectedOutput(request: RejectedOutputDiagnosticRequest, dbOverride: String? = null) =
    database.transaction(dbOverride) { unitOfWork ->
      val repository = unitOfWork.rejectedOutputDiagnostics
        ?: throw RejectedOutputDiagnosticError.Persistence("repository-unavailable")
      val permissions = unitOfWork.rejectedOutputDiagnosticPermissions
        ?: throw RejectedOutputDiagnosticError.Permission("permissions-unavailable")
      val service = RejectedOutputDiagnosticService(repository, permissions, rejectedOutputDiagnosticMetadataValidator)
      val evidence = ProducerOutputEvidence(
        workflowId = request.workflowId,
        phaseId = request.phaseId,
        attempt = request.attempt,
        agentId = request.agentId,
        model = request.model,
        recordedAt = java.time.Instant.now(),
        byteSize = request.observedByteSize,
        sha256 = request.observedSha256,
        payload = request.rawResponse.takeUnless { request.truncated },
      )
      request.rawResponsePath?.let { path ->
        service.retainProducerOutput(evidence, Path.of(path))
      } ?: service.retainProducerOutput(evidence)
      service.record(request)
    }

  fun retainProducerOutput(evidence: ProducerOutputEvidence, dbOverride: String? = null) =
    database.transaction(dbOverride) { unitOfWork ->
      val repository = unitOfWork.rejectedOutputDiagnostics
        ?: throw RejectedOutputDiagnosticError.Persistence("repository-unavailable")
      val permissions = unitOfWork.rejectedOutputDiagnosticPermissions
        ?: throw RejectedOutputDiagnosticError.Permission("permissions-unavailable")
      RejectedOutputDiagnosticService(repository, permissions, rejectedOutputDiagnosticMetadataValidator)
        .retainProducerOutput(evidence)
    }

  fun producerOutput(workflowId: String, phaseId: String, attempt: Int, dbOverride: String? = null) =
    database.read(dbOverride) {
      it.rejectedOutputDiagnostics?.producerOutputs?.read(workflowId, phaseId, attempt)
    }

  fun latestProducerOutputAttempt(workflowId: String, phaseId: String, dbOverride: String? = null): Int =
    database.read(dbOverride) {
      it.rejectedOutputDiagnostics?.producerOutputs?.latestAttempt(workflowId, phaseId) ?: 0
    }

  fun releaseCapturedOutput(path: String, dbOverride: String? = null) {
    database.read(dbOverride) {
      it.rejectedOutputDiagnostics?.filePayloads?.release(Path.of(path))
    }
  }
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
      val partialRepairPatch = partialRepairPatch(request, artifacts)
      val patch = mapOf(
        FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY to
          updatedRecords.mapValues { (_, value) -> value.toArtifactMap() },
      ) + partialRepairPatch
      persistPatch(
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

  private fun partialRepairPatch(
    request: FeatureTaskRuntimePhaseStateRequest,
    artifacts: Map<String, Any?>,
  ): Map<String, Any?> {
    val isBlockedAuditRepair = request.status == "blocked" &&
      request.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT &&
      request.loopId == FeatureTaskRuntimePhaseWorkflowDefinition.AUDIT_GAP_LOOP_ID
    if (!isBlockedAuditRepair) return emptyMap()
    val produced = request.normalizedOutput?.envelope?.get("produced_outputs")
      ?.let(JsonSupport::anyToStringAnyMap)
    val results = (produced?.get("repair_item_results") as? List<*>)
      ?.mapIndexed { index, value ->
        repairItemResultFromWire(value, "implement.repair_item_results[$index]")
      }.orEmpty()
    val prior = artifacts[FEATURE_TASK_RUNTIME_AUDIT_REPAIR_STATE_ARTIFACT_KEY]?.let {
      auditRepairStateFromWire(it, FEATURE_TASK_RUNTIME_AUDIT_REPAIR_STATE_ARTIFACT_KEY)
    }
    if (results.isEmpty() || prior == null) return emptyMap()
    val reconciled = FeatureTaskRuntimeAuditRepairReconciler.reconcile(
      AuditRepairReconciliation(
        prior = prior,
        latestPlan = null,
        repairResults = results,
        dispositions = null,
        repositoryFingerprint = request.repositoryFingerprint,
        edgeIteration = request.edgeIteration,
      ),
    )
    return mapOf(
      FEATURE_TASK_RUNTIME_AUDIT_REPAIR_STATE_ARTIFACT_KEY to auditRepairStateToWire(reconciled),
    )
  }

  @Suppress("LongMethod", "CyclomaticComplexMethod", "ComplexCondition")
  fun recordCompletedPhase(request: FeatureTaskRuntimePhaseStateRequest, dbOverride: String? = null): Boolean {
    require(request.status == "completed" && request.finished)
    return database.transaction(dbOverride) { unitOfWork ->
      val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, request.workflowId)
        ?: return@transaction false
      val artifacts = decodeArtifacts(record.artifactsJson)
      val existingRecords = phaseRecordsFrom(artifacts)
      val completionTimestamp = existingRecords[request.phaseId]?.finishedAt ?: Instant.now().toString()
      val convergenceAvailable = unitOfWork.convergenceStates !== UnavailableConvergenceStateRepository
      if (convergenceAvailable) reconcileLegacyArtifacts(unitOfWork, request.workflowId, existingRecords)
      val convergenceRecords = if (convergenceAvailable) {
        convergenceRecordsForCompletion(request, completionTimestamp)
      } else {
        emptyList()
      }
      val replayResults = convergenceRecords.map(unitOfWork.convergenceStates::append)
      replayResults.filterIsInstance<ReplayResult.Conflict>().firstOrNull()?.let {
        throw ConvergenceReplayConflictException(it.proposed.recordId)
      }
      if (replayResults.isNotEmpty() &&
        replayResults.all { it is ReplayResult.Identical } &&
        existingRecords[request.phaseId]?.let { it.status == "completed" && it.finishedAt != null } == true
      ) {
        return@transaction true
      }
      val updatedRecords = LinkedHashMap(existingRecords).apply {
        put(request.phaseId, phaseRecordFor(request, existingRecords[request.phaseId], completionTimestamp))
      }
      val outputProduced = request.normalizedOutput
        ?.envelope
        ?.get("produced_outputs")
        ?.let(JsonSupport::anyToStringAnyMap)
      val priorAuditState = artifacts[FEATURE_TASK_RUNTIME_AUDIT_REPAIR_STATE_ARTIFACT_KEY]?.let {
        auditRepairStateFromWire(it, FEATURE_TASK_RUNTIME_AUDIT_REPAIR_STATE_ARTIFACT_KEY)
      }
      val latestPlan = outputProduced?.get("audit_repair_plan")
        ?.takeIf { request.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT }
        ?.let { auditRepairPlanFromWire(it, "audit.produced_outputs.audit_repair_plan") }
        ?: FeatureTaskRuntimeAuditRepairPlan(
          contractVersion = AUDIT_REPAIR_CONTRACT_VERSION,
          gaps = emptyList(),
        ).takeIf {
          request.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT &&
            priorAuditState == null &&
            request.auditScopeCriterionRefs.isNotEmpty()
        }
      val repairResults = (outputProduced?.get("repair_item_results") as? List<*>)
        ?.takeIf {
          request.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT &&
            request.loopId == FeatureTaskRuntimePhaseWorkflowDefinition.AUDIT_GAP_LOOP_ID
        }
        ?.mapIndexed { index, value -> repairItemResultFromWire(value, "implement.repair_item_results[$index]") }
      val currentDispositions = (outputProduced?.get("prior_gap_dispositions") as? List<*>)
        ?.takeIf { request.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT }
        ?.mapIndexed { index, value -> priorGapDispositionFromWire(value, "audit.prior_gap_dispositions[$index]") }
      val effectiveDispositions = currentDispositions ?: inferAuditGapDispositions(
        phaseId = request.phaseId,
        prior = priorAuditState,
        latestPlan = latestPlan,
      )
      val reconcilesAuditState = request.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT &&
        priorAuditState != null
      val reconciledAuditState = if (latestPlan != null || repairResults != null || effectiveDispositions != null ||
        reconcilesAuditState
      ) {
        if (latestPlan != null && priorAuditState == null) {
          if (!currentDispositions.isNullOrEmpty()) {
            schemaError(
              "An initial audit cannot disposition a gap the durable ledger never carried; " +
                "dispositioned=${currentDispositions.map { it.gapId }.sorted()}.",
            )
          }
          CompletenessAuditPhase.handleInitialAudit(
            auditPlan = latestPlan,
            repositoryFingerprint = request.repositoryFingerprint,
            edgeIteration = request.edgeIteration,
            declaredCriteria = request.auditScopeCriterionRefs,
          )
        } else {
          FeatureTaskRuntimeAuditRepairReconciler.reconcile(
            AuditRepairReconciliation(
              prior = priorAuditState,
              latestPlan = latestPlan,
              repairResults = repairResults.orEmpty(),
              dispositions = effectiveDispositions,
              repositoryFingerprint = request.repositoryFingerprint,
              edgeIteration = request.edgeIteration,
              auditWrite = request.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT,
              auditScopeCriterionRefs = request.auditScopeCriterionRefs,
            ),
          )
        }
      } else {
        priorAuditState
      }
      if (convergenceAvailable && reconciledAuditState != null) {
        persistNormalizedAuditState(unitOfWork, request, reconciledAuditState, completionTimestamp)
        auditConvergenceRecords(request, reconciledAuditState, completionTimestamp).forEach { convergenceRecord ->
          when (val result = unitOfWork.convergenceStates.append(convergenceRecord)) {
            is ReplayResult.Conflict -> throw ConvergenceReplayConflictException(result.proposed.recordId)
            else -> Unit
          }
        }
      }
      val auditRepairPatch = reconciledAuditState?.let {
        mapOf(FEATURE_TASK_RUNTIME_AUDIT_REPAIR_STATE_ARTIFACT_KEY to auditRepairStateToWire(it))
      }.orEmpty()
      val ledger = phaseLedgerFrom(artifacts)
      val completion = FeatureTaskRuntimePhaseLedgerEntry(
        action = skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerAction.COMPLETE,
        sequenceNumber = (ledger.maxOfOrNull { it.sequenceNumber } ?: -1) + 1,
        timestamp = Instant.now().toString(),
        phaseId = request.phaseId,
        attemptCount = request.attemptCount,
        resolvedAgentId = request.resolvedAgentId,
        loopId = request.loopId,
        edgeIteration = request.edgeIteration,
        auditRepairProgress = reconciledAuditState?.progress,
      )
      val updatedLedger = appendBoundedHistoryBySequence(
        ledger.map { it.toArtifactMap() },
        completion.toArtifactMap(),
        FEATURE_TASK_RUNTIME_PHASE_LEDGER_LIMIT,
      )
      persistPatch(
        unitOfWork.workflowStates,
        record,
        mapOf(
          FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY to
            updatedRecords.mapValues { (_, value) -> value.toArtifactMap() },
          FEATURE_TASK_RUNTIME_PHASE_LEDGER_ARTIFACT_KEY to updatedLedger,
        ) + auditRepairPatch,
        WorkflowRowAdvance(request.phaseId, workflowStatusFor(request), stepUpdatesFrom(updatedRecords)),
      )
      true
    }
  }

  private fun inferAuditGapDispositions(
    phaseId: String,
    prior: FeatureTaskRuntimeAuditRepairState?,
    latestPlan: skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditRepairPlan?,
  ): List<FeatureTaskRuntimePriorGapDisposition>? {
    if (phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT || prior == null) return null
    val latestByGapId = latestPlan?.gaps.orEmpty().associateBy { it.gapId }
    val priorByGapId = prior.acceptedPlans.last().gaps.associateBy { it.gapId }
    return prior.unresolvedGapLedger.unresolvedGaps.map { unresolved ->
      val recurring = latestByGapId[unresolved.gapId]
      val evidenceSource = recurring ?: requireNotNull(priorByGapId[unresolved.gapId])
      FeatureTaskRuntimePriorGapDisposition(
        gapId = unresolved.gapId,
        status = if (recurring == null) {
          FeatureTaskRuntimePriorGapDisposition.Status.RESOLVED
        } else {
          FeatureTaskRuntimePriorGapDisposition.Status.RECURRING
        },
        evidence = FeatureTaskRuntimeEvidence(
          observation = if (recurring == null) {
            FeatureTaskRuntimeEvidence.Observation.RESOLUTION_VERIFIED
          } else {
            FeatureTaskRuntimeEvidence.Observation.RECURRENCE_VERIFIED
          },
          artifactRef = evidenceSource.failureEvidence.artifactRef,
          checkRef = unresolved.acceptanceCriterionRef,
        ),
      )
    }
  }

  internal fun persistReviewGenerationInvalidation(workflowId: String, dbOverride: String? = null): Boolean =
    database.transaction(dbOverride) { unitOfWork ->
      val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, workflowId)
        ?: return@transaction false
      val artifacts = decodeArtifacts(record.artifactsJson)
      val existingRecords = phaseRecordsFrom(artifacts)
      val previousReview = existingRecords[FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW]
        ?: return@transaction true
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
      val patch = linkedMapOf<String, Any?>(
        FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY to
          updatedRecords.mapValues { (_, value) -> value.toArtifactMap() },
      )
      GoalSubtaskReviewArtifactDecoder.decode(artifacts)?.state?.let { state ->
        val priorResults = (artifacts[GOAL_SUBTASK_REVIEW_RESULT_HISTORY_ARTIFACT_KEY] as? Map<*, *>)
          ?.entries
          ?.associate { (key, value) -> key.toString() to value }
          .orEmpty()
        patch[GOAL_SUBTASK_REVIEW_RESULT_HISTORY_ARTIFACT_KEY] =
          priorResults + reviewArtifactsHistoryEntry(workflowId, state, artifacts, priorResults.keys)
        patch[GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY] = GoalSubtaskReviewState.initial(
          reviewBaseSha = state.reviewBaseSha,
          baselineUntrackedPaths = state.baselineUntrackedPaths,
          codeReviewMode = state.codeReviewMode,
        ).toArtifactMap()
        patch[GOAL_SUBTASK_REVIEW_RESULTS_ARTIFACT_KEY] = emptyMap<String, String>()
      }
      persistPatch(
        unitOfWork.workflowStates,
        record,
        patch,
        WorkflowRowAdvance(
          currentStepId = record.currentStepId,
          workflowStatus = record.workflowStatus,
          stepUpdates = stepUpdatesFrom(updatedRecords),
        ),
      )
      true
    }

  private fun reviewArtifactsHistoryEntry(
    workflowId: String,
    state: GoalSubtaskReviewState,
    artifacts: Map<String, Any?>,
    existingIdentities: Set<String>,
  ): Map<String, Any?> {
    val reviewedDeltaDigest = state.reviewedDeltaDigest
    val reviewedRepositoryFingerprint = state.reviewedRepositoryFingerprint
    val generationIdentity = if (
      reviewedDeltaDigest != null &&
      reviewedRepositoryFingerprint != null &&
      state.completedPassCount > 0
    ) {
      goalSubtaskReviewGenerationId(
        workflowId = workflowId,
        reviewBase = state.remediationBaseSha
          ?.takeIf { state.completedPassCount == 2 }
          ?: state.reviewBaseSha,
        reviewedDeltaDigest = reviewedDeltaDigest,
        passNumber = state.completedPassCount,
        repositoryCheckpoint = reviewedRepositoryFingerprint,
      )
    } else {
      listOf(
        state.reviewBaseSha,
        state.reviewedDeltaDigest ?: "legacy",
        state.completedPassCount.toString(),
        state.reviewedRepositoryFingerprint ?: "checkpoint-unavailable",
      ).joinToString(":")
    }
    val identity = generateSequence(0) { it + 1 }
      .map { invalidationIndex ->
        if (invalidationIndex == 0) generationIdentity else "$generationIdentity-invalidation-$invalidationIndex"
      }
      .first { it !in existingIdentities }
    return mapOf(
      identity to linkedMapOf(
        "state" to state.toArtifactMap(),
        "raw_results" to artifacts[GOAL_SUBTASK_REVIEW_RESULTS_ARTIFACT_KEY],
      ),
    )
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
    persistPatch(
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

  internal fun completeGoalReviewPhase(
    completion: GoalReviewPhaseCompletionRequest,
    dbOverride: String? = null,
  ): Boolean {
    val request = validatedGoalReviewPhaseState(completion)
    return database.transaction(dbOverride) { unitOfWork ->
      val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, request.workflowId)
        ?: return@transaction false
      val artifacts = decodeArtifacts(record.artifactsJson)
      val reviewArtifacts = GoalSubtaskReviewArtifactDecoder.decode(artifacts)
        ?: return@transaction false
      val settlement = unitOfWork.settleGoalSubtaskReviewGeneration(
        GoalSubtaskReviewGenerationSettlementRequest(
          workflowId = request.workflowId,
          state = reviewArtifacts.state,
          verdict = completion.verdict,
          unresolvedFindingCount = completion.unresolvedFindingCount,
          findings = completion.findings,
          blockerDispositions = completion.blockerDispositions,
          repositoryCheckpoint = request.repositoryFingerprint,
          reviewedDelta = reviewedDeltaFromArtifacts(artifacts),
        ),
      )
      val completedState = settlement.state
      val passNumber = settlement.passNumber.toString()
      val phaseCompletion = completedGoalReviewPhaseRecords(request, artifacts)
      val continuation = reviewArtifacts.continuation
      persistUnaddressedFindings(unitOfWork, request, continuation, passNumber.toInt())
      persistPatch(
        unitOfWork.workflowStates,
        record,
        linkedMapOf<String, Any?>(
          GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY to completedState.toArtifactMap(),
          GOAL_SUBTASK_REVIEW_RESULTS_ARTIFACT_KEY to
            (reviewArtifacts.rawResults + (passNumber to completion.rawReviewResult)),
          FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY to
            phaseCompletion.records.mapValues { (_, value) -> value.toArtifactMap() },
          FEATURE_TASK_RUNTIME_PHASE_LEDGER_ARTIFACT_KEY to phaseCompletion.ledger,
        ).apply {
          if (reviewArtifacts.state.passResults.any { it.passNumber == passNumber.toInt() }) {
            put(
              GOAL_SUBTASK_REVIEW_RESULT_HISTORY_ARTIFACT_KEY,
              (artifacts[GOAL_SUBTASK_REVIEW_RESULT_HISTORY_ARTIFACT_KEY] as? Map<*, *>)
                .orEmpty()
                .mapKeys { it.key.toString() } +
                (
                  "retry-$passNumber-${reviewArtifacts.state.passResults.size}" to
                    reviewArtifacts.rawResults[passNumber]
                  ),
            )
          }
        },
        WorkflowRowAdvance(
          currentStepId = request.phaseId,
          workflowStatus = workflowStatusFor(request),
          stepUpdates = stepUpdatesFrom(phaseCompletion.records),
        ),
      )
      true
    }
  }

  private fun completedGoalReviewPhaseRecords(
    request: FeatureTaskRuntimePhaseStateRequest,
    artifacts: Map<String, Any?>,
  ): CompletedGoalReviewPhaseRecords {
    val existingRecords = phaseRecordsFrom(artifacts)
    val phaseRecord = phaseRecordFor(request, existingRecords[request.phaseId], Instant.now().toString())
    val updatedRecords = LinkedHashMap(existingRecords).apply { put(request.phaseId, phaseRecord) }
    val ledger = phaseLedgerFrom(artifacts)
    val completionEntry = FeatureTaskRuntimePhaseLedgerEntry(
      action = FeatureTaskRuntimePhaseLedgerAction.COMPLETE,
      sequenceNumber = (ledger.maxOfOrNull { it.sequenceNumber } ?: -1) + 1,
      timestamp = Instant.now().toString(),
      phaseId = request.phaseId,
      attemptCount = request.attemptCount,
      resolvedAgentId = request.resolvedAgentId,
      loopId = request.loopId,
      edgeIteration = request.edgeIteration,
    )
    return CompletedGoalReviewPhaseRecords(
      records = updatedRecords,
      ledger = appendBoundedHistoryBySequence(
        ledger.map { it.toArtifactMap() },
        completionEntry.toArtifactMap(),
        FEATURE_TASK_RUNTIME_PHASE_LEDGER_LIMIT,
      ),
    )
  }

  internal fun unresolvedReviewBlockers(workflowId: String, dbOverride: String? = null) = database.read(dbOverride) {
    if (it.reviewGenerations === UnavailableReviewGenerationRepository) {
      emptyList()
    } else {
      it.reviewGenerations.unresolvedBlockers(workflowId)
    }
  }

  internal fun reviewGenerationSummary(workflowId: String, dbOverride: String? = null) = database.read(dbOverride) {
    if (it.reviewGenerations === UnavailableReviewGenerationRepository) {
      null
    } else {
      it.reviewGenerations.summary(workflowId)
    }
  }

  private fun persistUnaddressedFindings(
    unitOfWork: UnitOfWork,
    request: FeatureTaskRuntimePhaseStateRequest,
    continuation: FeatureTaskRuntimeGoalContinuationArtifact,
    passNumber: Int,
  ) {
    val output = requireNotNull(request.normalizedOutput) {
      "Goal review completion requires normalized output to persist the unaddressed-findings ledger."
    }.envelope
    val findings = GoalSubtaskReviewSummaryReducer.unaddressedFindings(
      output = output,
      issueKey = continuation.issueKey,
      subtaskId = continuation.subtaskId,
      workflowId = request.workflowId,
      reviewPassNumber = passNumber,
    )
    unitOfWork.unaddressedFindings.replaceLedgerForPass(request.workflowId, passNumber, findings)
  }

  private fun validatedGoalReviewPhaseState(
    completion: GoalReviewPhaseCompletionRequest,
  ): FeatureTaskRuntimePhaseStateRequest {
    val request = completion.phaseState
    require(request.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW) {
      "Goal review completion can only persist the review phase."
    }
    require(request.status == "completed" && request.finished) {
      "Goal review completion must persist a finished completed review phase."
    }
    require(completion.rawReviewResult.isNotBlank()) { "Goal-subtask review pass result must be non-blank." }
    return request
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
      persistPatch(
        unitOfWork.workflowStates,
        record,
        mapOf(
          FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY to
            cleared.mapValues { (_, value) -> value.toArtifactMap() },
        ),
      )
      true
    }
  fun recordPhaseBriefing(
    workflowId: String,
    briefing: FeatureTaskRuntimePhaseLaunchBriefing,
    dbOverride: String? = null,
  ): Boolean = database.transaction(dbOverride) { unitOfWork ->
    val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, workflowId)
      ?: return@transaction false
    handoffEnvelopeValidator.validateEnvelope(briefing.handoffEnvelope.toEnvelopeMap(), workflowId)
    val artifacts = decodeArtifacts(record.artifactsJson)
    val updatedBriefings = LinkedHashMap(phaseBriefingsFrom(artifacts, ::validateEnvelopeWire))
      .apply { put(briefing.phaseId, briefing) }
    val deliveredHistory = deliveredProjectionHistoryFrom(
      artifacts,
      ::validateEnvelopeWire,
      ::validatePersistenceWire,
    )
    val delivered = nextDeliveredProjectionRecord(workflowId, briefing, deliveredHistory)
    handoffFoundationValidator.validatePersistenceRecord(
      delivered.toArtifactMap(),
      "delivered-projection:${briefing.phaseId}",
    )
    recordProjectionMeasurements(unitOfWork, workflowId, briefing, delivered, artifacts)
    val updatedDelivered = LinkedHashMap(deliveredHistory)
      .apply { put(deliveredProjectionKey(delivered), delivered) }
    val patch = mapOf(
      FEATURE_TASK_RUNTIME_PHASE_BRIEFINGS_ARTIFACT_KEY to
        updatedBriefings.mapValues { (_, value) -> value.toArtifactMap() },
      FEATURE_TASK_RUNTIME_DELIVERED_PROJECTIONS_ARTIFACT_KEY to
        updatedDelivered.mapValues { (_, value) -> value.toArtifactMap() },
    )
    persistPatch(unitOfWork.workflowStates, record, patch)
    true
  }

  private fun nextDeliveredProjectionRecord(
    workflowId: String,
    briefing: FeatureTaskRuntimePhaseLaunchBriefing,
    deliveredHistory: Map<String, FeatureTaskRuntimeDeliveredProjectionRecord>,
  ): FeatureTaskRuntimeDeliveredProjectionRecord {
    val existingDelivered = deliveredHistory.values
      .filter { it.consumerPhaseId == briefing.phaseId }
      .maxByOrNull(FeatureTaskRuntimeDeliveredProjectionRecord::iteration)
    return FeatureTaskRuntimeDeliveredProjectionRecord(
      workflowId = workflowId,
      consumerPhaseId = briefing.phaseId,
      iteration = (existingDelivered?.iteration ?: 0) + 1,
      envelope = briefing.handoffEnvelope,
    )
  }

  private fun recordProjectionMeasurements(
    unitOfWork: UnitOfWork,
    workflowId: String,
    briefing: FeatureTaskRuntimePhaseLaunchBriefing,
    delivered: FeatureTaskRuntimeDeliveredProjectionRecord,
    artifacts: Map<String, Any?>,
  ) {
    val privatePhaseRecords = phaseRecordsFrom(artifacts)
    briefing.handoffEnvelope.projections.forEach { projection ->
      val deliveredProjectionUtf8Bytes = projection.utf8ByteSize
      val privateEvidenceUtf8Bytes =
        privatePhaseRecords[projection.producerIteration.phaseId]
          ?.outputArtifact
          ?.toByteArray(Charsets.UTF_8)
          ?.size
          ?: 0
      val measurement = FeatureTaskRuntimeProjectionMeasurement(
        workflowId = workflowId,
        consumerPhaseId = briefing.phaseId,
        projectionContractId = projection.projectionContractId,
        producerIteration = projection.producerIteration,
        repositoryCheckpointFingerprint = delivered.repositoryCheckpointFingerprint,
        projectedUtf8Bytes = projection.utf8ByteSize,
        projectedCollectionItems = projection.itemCount,
        estimatedTokens = (projection.utf8ByteSize + 3) / 4,
        privateEvidenceUtf8Bytes = privateEvidenceUtf8Bytes,
        deliveredProjectionUtf8Bytes = deliveredProjectionUtf8Bytes,
      )
      handoffFoundationValidator.validateMeasurement(
        measurement.toTelemetryMap(),
        "projection-delivery:${briefing.phaseId}:${projection.projectionName}",
      )
      unitOfWork.lifecycleTelemetry.featureTaskRuntimeProjectionMeasurement(measurement)
    }
  }

  private fun deliveredProjectionKey(delivered: FeatureTaskRuntimeDeliveredProjectionRecord): String = listOf(
    delivered.workflowId,
    delivered.consumerPhaseId,
    delivered.iteration.toString(),
    delivered.sourceProducerIterations
      .sortedWith(
        compareBy(
          FeatureTaskRuntimeProducerIteration::phaseId,
          FeatureTaskRuntimeProducerIteration::iteration,
        ),
      )
      .joinToString(separator = ",") { "${it.phaseId}#${it.iteration}" },
    delivered.repositoryCheckpointFingerprint,
  ).joinToString(separator = "|")
  fun recordProjectionRejection(
    workflowId: String,
    consumerPhaseId: String,
    error: InvalidFeatureTaskRuntimeHandoffProjectionError,
    repositoryCheckpointFingerprint: String?,
    dbOverride: String? = null,
  ): Boolean = database.transaction(dbOverride) { unitOfWork ->
    recordProjectionRejectionMeasurement(
      unitOfWork,
      FeatureTaskRuntimeProjectionRejection(
        workflowId = workflowId,
        consumerPhaseId = consumerPhaseId,
        projectionContractId = error.projectionContractId.ifBlank { "unknown" },
        producerIteration = FeatureTaskRuntimeProducerIteration(consumerPhaseId, 1),
        repositoryCheckpointFingerprint = repositoryCheckpointFingerprint,
        failureClassification = error.failureKind.toMeasurementFailureClassification(),
        sourceLabel = error.projectionName,
      ),
    )
  }

  internal fun recordProjectionRejection(
    rejection: FeatureTaskRuntimeProjectionRejection,
    dbOverride: String? = null,
  ): Boolean = database.transaction(dbOverride) { unitOfWork ->
    recordProjectionRejectionMeasurement(unitOfWork, rejection)
  }

  private fun recordProjectionRejectionMeasurement(
    unitOfWork: UnitOfWork,
    rejection: FeatureTaskRuntimeProjectionRejection,
  ): Boolean {
    if (WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, rejection.workflowId) == null) {
      return false
    }
    val measurement = FeatureTaskRuntimeProjectionMeasurement(
      workflowId = rejection.workflowId,
      consumerPhaseId = rejection.consumerPhaseId,
      projectionContractId = rejection.projectionContractId.ifBlank { "unknown" },
      producerIteration = rejection.producerIteration,
      repositoryCheckpointFingerprint = rejection.repositoryCheckpointFingerprint
        ?: "not_resolved:${rejection.consumerPhaseId}",
      projectedUtf8Bytes = 0,
      projectedCollectionItems = 0,
      estimatedTokens = 0,
      privateEvidenceUtf8Bytes = 0,
      deliveredProjectionUtf8Bytes = 0,
      failureClassification = rejection.failureClassification,
    )
    handoffFoundationValidator.validateMeasurement(
      measurement.toTelemetryMap(),
      "projection-rejection:${rejection.consumerPhaseId}:${rejection.sourceLabel}",
    )
    unitOfWork.lifecycleTelemetry.featureTaskRuntimeProjectionMeasurement(measurement)
    return true
  }

  fun validateHandoffDeclarations(declarations: List<PhaseHandoffProjectionDeclaration>) {
    declarations.forEach { declaration ->
      handoffFoundationValidator.validateDeclaration(
        declaration.toArtifactMap(),
        "phase-handoff-declaration:${declaration.consumerPhaseId}:${declaration.projectionName}",
      )
    }
  }
  fun loadPhaseBriefings(
    workflowId: String,
    dbOverride: String? = null,
  ): Map<String, FeatureTaskRuntimePhaseLaunchBriefing>? = database.read(dbOverride) { unitOfWork ->
    val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, workflowId)
      ?: return@read null
    phaseBriefingsFrom(decodeArtifacts(record.artifactsJson)) { envelope ->
      handoffEnvelopeValidator.validateEnvelope(envelope, workflowId)
    }
  }
  fun loadDeliveredProjections(
    workflowId: String,
    dbOverride: String? = null,
  ): Map<String, FeatureTaskRuntimeDeliveredProjectionRecord>? = database.read(dbOverride) { unitOfWork ->
    val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, workflowId)
      ?: return@read null
    deliveredProjectionsFrom(
      decodeArtifacts(record.artifactsJson),
      validateEnvelope = { envelope -> handoffEnvelopeValidator.validateEnvelope(envelope, workflowId) },
      validatePersistenceRecord = { persistence ->
        handoffFoundationValidator.validatePersistenceRecord(persistence, "delivered-projection:$workflowId")
      },
    )
  }

  private fun validateEnvelopeWire(envelope: Map<String, Any?>) =
    handoffEnvelopeValidator.validateEnvelope(envelope, workflowId = null)

  private fun validatePersistenceWire(record: Map<String, Any?>) =
    handoffFoundationValidator.validatePersistenceRecord(record, "delivered-projection")

  fun loadAuditRepairState(workflowId: String, dbOverride: String? = null): FeatureTaskRuntimeAuditRepairState? =
    database.read(dbOverride) { unitOfWork ->
      val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, workflowId)
        ?: return@read null
      val artifact = decodeArtifacts(record.artifactsJson)[FEATURE_TASK_RUNTIME_AUDIT_REPAIR_STATE_ARTIFACT_KEY]
        ?: return@read null
      auditRepairStateFromWire(artifact, FEATURE_TASK_RUNTIME_AUDIT_REPAIR_STATE_ARTIFACT_KEY)
    }
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
        auditRepairProgress = artifacts[FEATURE_TASK_RUNTIME_AUDIT_REPAIR_STATE_ARTIFACT_KEY]?.let {
          auditRepairStateFromWire(it, FEATURE_TASK_RUNTIME_AUDIT_REPAIR_STATE_ARTIFACT_KEY).progress
        },
      )
      val updatedLedger = appendBoundedHistoryBySequence(
        existing = existingEntries.map { it.toArtifactMap() },
        entry = entry.toArtifactMap(),
        retentionLimit = FEATURE_TASK_RUNTIME_PHASE_LEDGER_LIMIT,
      )
      persistPatch(
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
    persistPatch(
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
    persistPatch(
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

  fun recordWorkflowOwnedPaths(
    workflowId: String,
    ownedPaths: List<String>,
    contentIdentities: Map<String, String> = emptyMap(),
    dbOverride: String? = null,
  ): Boolean = database.transaction(dbOverride) { unitOfWork ->
    val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, workflowId)
      ?: return@transaction false
    val resolved = resolvedBranchFrom(decodeArtifacts(record.artifactsJson)) ?: return@transaction false
    val updated = resolved.copy(
      workflowOwnedPaths = ownedPaths.distinct().sorted(),
      workflowOwnedPathContentIdentities = contentIdentities.toSortedMap(),
    )
    persistPatch(
      unitOfWork.workflowStates,
      record,
      mapOf(FEATURE_TASK_RUNTIME_RESOLVED_BRANCH_ARTIFACT_KEY to updated.toArtifactMap()),
    )
    true
  }

  fun recordCheckpointIdentity(
    workflowId: String,
    identity: skillbill.workflow.taskruntime.model.WorkflowCheckpointIdentity,
    dbOverride: String? = null,
  ): Boolean = database.transaction(dbOverride) { unitOfWork ->
    val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, workflowId)
      ?: return@transaction false
    val resolved = resolvedBranchFrom(decodeArtifacts(record.artifactsJson)) ?: return@transaction false
    val existing = resolved.checkpointIdentities.firstOrNull {
      it.phase == identity.phase && it.loop == identity.loop && it.generation == identity.generation
    }
    require(existing == null || existing == identity) {
      "Checkpoint identity for ${identity.phase}/${identity.loop}/${identity.generation} already names " +
        "commit ${existing?.commitSha}; refusing to advance with ${identity.commitSha}."
    }
    if (existing == identity) return@transaction true
    val updated = resolved.copy(checkpointIdentities = resolved.checkpointIdentities + identity)
    persistPatch(
      unitOfWork.workflowStates,
      record,
      mapOf(FEATURE_TASK_RUNTIME_RESOLVED_BRANCH_ARTIFACT_KEY to updated.toArtifactMap()),
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
  fun loadConvergenceState(
    workflowId: String,
    dbOverride: String? = null,
  ): skillbill.workflow.taskruntime.model.UnresolvedConvergence? = try {
    database.read(dbOverride) { unitOfWork ->
      unitOfWork.convergenceStates.unresolved(workflowId)
    }
  } catch (_: IllegalStateException) {
    null
  }
  @OpenBoundaryMap("Implementation receipt wire map at the persistence boundary")
  fun loadPriorImplementationReceiptEnvelope(workflowId: String, dbOverride: String? = null): Map<String, Any?>? {
    val latestAttempt = loadImplementationReceiptAttemptHistory(workflowId, dbOverride).lastOrNull()
    return latestAttempt ?: loadPhaseRecords(workflowId, dbOverride)
      ?.get(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT)
      ?.outputArtifact
      ?.let(JsonSupport::parseObjectOrNull)
      ?.let(JsonSupport::jsonElementToValue)
      ?.let(JsonSupport::anyToStringAnyMap)
  }
  internal fun recordImplementationReceiptAttempt(
    workflowId: String,
    envelope: Map<String, Any?>,
    dbOverride: String? = null,
  ): Boolean = database.transaction(dbOverride) { unitOfWork ->
    val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, workflowId)
      ?: return@transaction false
    val artifacts = decodeArtifacts(record.artifactsJson)
    val prior = (artifacts[IMPLEMENTATION_RECEIPT_ATTEMPT_HISTORY_ARTIFACT_KEY] as? List<*>)
      .orEmpty()
      .mapNotNull(JsonSupport::anyToStringAnyMap)
    val phaseRecord = phaseRecordsFrom(artifacts)[FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT]
    val createdAt = phaseRecord?.startedAt ?: Instant.now().toString()
    val attempt = phaseRecord?.attemptCount ?: prior.size + 1
    convergenceImplementationAttemptRecords(workflowId, envelope, attempt, createdAt).forEach { convergenceRecord ->
      when (val result = unitOfWork.convergenceStates.append(convergenceRecord)) {
        is ReplayResult.Conflict -> throw ConvergenceReplayConflictException(result.proposed.recordId)
        else -> Unit
      }
    }
    val updated = (prior + envelope).takeLast(IMPLEMENTATION_RECEIPT_ATTEMPT_HISTORY_LIMIT)
    persistPatch(
      unitOfWork.workflowStates,
      record,
      mapOf(IMPLEMENTATION_RECEIPT_ATTEMPT_HISTORY_ARTIFACT_KEY to updated),
      WorkflowRowAdvance(record.currentStepId, record.workflowStatus),
    )
    true
  }

  @OpenBoundaryMap("Bounded immutable implementation receipt attempt history")
  private fun loadImplementationReceiptAttemptHistory(
    workflowId: String,
    dbOverride: String?,
  ): List<Map<String, Any?>> = database.read(dbOverride) { unitOfWork ->
    val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, workflowId)
      ?: return@read emptyList()
    val raw = decodeArtifacts(record.artifactsJson)[IMPLEMENTATION_RECEIPT_ATTEMPT_HISTORY_ARTIFACT_KEY]
      as? List<*> ?: return@read emptyList()
    raw.mapIndexed { index, item ->
      JsonSupport.anyToStringAnyMap(item)
        ?: throw InvalidWorkflowStateSchemaError(
          "Implementation receipt attempt history entry $index must be an object.",
        )
    }
  }

  fun existingWorkflowMode(workflowId: String, dbOverride: String? = null): FeatureTaskWorkflowMode? =
    database.read(dbOverride) { unitOfWork ->
      unitOfWork.workflowStates.getFeatureTaskWorkflow(workflowId)?.mode
    }

  fun workerOwnership(workflowId: String, dbOverride: String? = null): FeatureTaskRuntimeWorkerOwnership? =
    database.read(dbOverride) { unitOfWork ->
      unitOfWork.workflowStates.getFeatureTaskRuntimeWorkerOwnership(workflowId)
    }

  fun ensureWorkflowOpen(
    workflowId: String,
    sessionId: String,
    dbOverride: String? = null,
    issueKey: String? = null,
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

  private fun persistPatch(
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

  private fun phaseRecordFor(
    request: FeatureTaskRuntimePhaseStateRequest,
    previous: FeatureTaskRuntimePhaseRecord?,
    now: String,
  ): FeatureTaskRuntimePhaseRecord {
    val firstStartedAt = previous?.firstStartedAt ?: now
    val startedAt = if (request.status == STATUS_RUNNING || previous == null) now else previous.startedAt
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
    )
  }

  private fun convergenceRecordsForCompletion(
    request: FeatureTaskRuntimePhaseStateRequest,
    createdAt: String,
  ): List<ConvergenceRecord> {
    if (request.phaseId !in setOf("implement", "audit", "review")) return emptyList()
    val generation = request.edgeIteration ?: request.attemptCount
    val evidenceDigest = convergenceEvidenceDigest(request)
    val checkpoint = convergenceCheckpoint(request, generation, evidenceDigest, createdAt)
    if (request.phaseId != "implement") return listOf(checkpoint)
    return listOf(
      convergenceImplementationOutcome(request, generation, evidenceDigest, createdAt),
      checkpoint,
    ) + convergenceImplementationReceiptRecords(request, generation, createdAt)
  }

  private fun reconcileLegacyArtifacts(
    unitOfWork: UnitOfWork,
    workflowId: String,
    phaseRecords: Map<String, FeatureTaskRuntimePhaseRecord>,
  ) {
    val records = mapLegacyArtifactRecords(workflowId, phaseRecords)
    val encoded = skillbill.workflow.taskruntime.ConvergenceStateCodec.encodeLegacySource(records)
    unitOfWork.convergenceStates.reconcileLegacy(
      workflowId,
      ConvergenceIdentities.digest(encoded),
      encoded,
    )
  }

  private companion object {
    const val STATUS_RUNNING = "running"
  }
}

private fun convergenceEvidenceDigest(request: FeatureTaskRuntimePhaseStateRequest): String =
  ConvergenceIdentities.digest(
    listOf(
      request.workflowId,
      request.phaseId,
      request.attemptCount.toString(),
      request.loopId.orEmpty(),
      request.edgeIteration?.toString().orEmpty(),
      request.reviewPassNumber?.toString().orEmpty(),
      request.repositoryFingerprint.orEmpty(),
      request.outputArtifact.orEmpty(),
    ).joinToString("|"),
  )

private fun convergenceCheckpoint(
  request: FeatureTaskRuntimePhaseStateRequest,
  generation: Int,
  evidenceDigest: String,
  createdAt: String,
): ConvergenceRecord {
  val logicalId = ConvergenceIdentities.logical(
    request.workflowId,
    ConvergenceRecordKind.REPOSITORY_CHECKPOINT,
    "${request.phaseId}:${request.attemptCount}:${request.loopId.orEmpty()}:${request.edgeIteration ?: 0}",
  )
  return ConvergenceRecord(
    recordId = ConvergenceIdentities.record(
      request.workflowId,
      ConvergenceRecordKind.REPOSITORY_CHECKPOINT,
      logicalId,
      generation,
    ),
    logicalId = logicalId,
    kind = ConvergenceRecordKind.REPOSITORY_CHECKPOINT,
    provenance = ConvergenceProvenance(request.workflowId, generation, request.phaseId),
    evidenceDigest = evidenceDigest,
    createdAt = createdAt,
    status = ConvergenceStatus.COMPLETED,
    summary = "${request.phaseId} phase repository checkpoint",
    evidenceRef = (request.repositoryFingerprint ?: "workflow:${request.workflowId}")
      .take(skillbill.workflow.taskruntime.model.CONVERGENCE_REFERENCE_MAX_LENGTH),
  )
}

private fun convergenceImplementationOutcome(
  request: FeatureTaskRuntimePhaseStateRequest,
  generation: Int,
  evidenceDigest: String,
  createdAt: String,
): ConvergenceRecord {
  val logicalId = ConvergenceIdentities.logical(
    request.workflowId,
    ConvergenceRecordKind.IMPLEMENTATION_OUTCOME,
    "attempt:${request.attemptCount}:${request.loopId.orEmpty()}:${request.edgeIteration ?: 0}",
  )
  return ConvergenceRecord(
    recordId = ConvergenceIdentities.record(
      request.workflowId,
      ConvergenceRecordKind.IMPLEMENTATION_OUTCOME,
      logicalId,
      generation,
    ),
    logicalId = logicalId,
    kind = ConvergenceRecordKind.IMPLEMENTATION_OUTCOME,
    provenance = ConvergenceProvenance(
      request.workflowId,
      generation,
      request.phaseId,
      attempt = request.attemptCount,
    ),
    evidenceDigest = evidenceDigest,
    createdAt = createdAt,
    status = ConvergenceStatus.COMPLETED,
    summary = "implementation attempt ${request.attemptCount} completed",
    evidenceRef = null,
  )
}

private fun convergenceImplementationReceiptRecords(
  request: FeatureTaskRuntimePhaseStateRequest,
  generation: Int,
  createdAt: String,
): List<ConvergenceRecord> {
  val produced = request.normalizedOutput?.envelope?.get("produced_outputs")
    ?.let(JsonSupport::anyToStringAnyMap)
    ?: return emptyList()
  val completedTasks = (produced["completed_task_ids"] as? List<*>)
    .orEmpty()
    .mapNotNull { it as? String }
    .distinct()
    .flatMap { taskId ->
      listOf(
        convergenceImplementationReceiptRecord(
          request = request,
          generation = generation,
          createdAt = createdAt,
          kind = ConvergenceRecordKind.IMPLEMENTATION_OUTCOME,
          stableKey = "task:$taskId",
          status = ConvergenceStatus.COMPLETED,
          classification = "completed_task",
          summary = "completed implementation task $taskId",
        ),
        convergenceImplementationReceiptRecord(
          request = request,
          generation = generation,
          createdAt = createdAt,
          kind = ConvergenceRecordKind.IMPLEMENTATION_OBLIGATION,
          stableKey = "task:$taskId",
          status = ConvergenceStatus.COMPLETED,
          classification = "resolved_item",
          summary = "resolved implementation obligation $taskId",
        ),
      )
    }
  val unresolved = (produced["unresolved_items"] as? List<*>)
    .orEmpty()
    .mapNotNull { value ->
      when (value) {
        is String -> value
        is Map<*, *> -> value["ref"] as? String ?: value["note"] as? String
        else -> null
      }
    }
    .distinct()
    .map { item ->
      convergenceImplementationReceiptRecord(
        request = request,
        generation = generation,
        createdAt = createdAt,
        kind = ConvergenceRecordKind.IMPLEMENTATION_OBLIGATION,
        stableKey = "task:$item",
        status = ConvergenceStatus.OPEN,
        classification = "unresolved_item",
        summary = item,
      )
    }
  val deviations = (produced["deviations"] as? List<*>)
    .orEmpty()
    .mapNotNull { value -> (value as? Map<*, *>)?.let(JsonSupport::anyToStringAnyMap) }
    .mapNotNull { deviation ->
      val ref = deviation["ref"] as? String ?: return@mapNotNull null
      val note = deviation["note"] as? String ?: return@mapNotNull null
      ref to note
    }
    .distinct()
    .map { (ref, note) ->
      convergenceImplementationReceiptRecord(
        request = request,
        generation = generation,
        createdAt = createdAt,
        kind = ConvergenceRecordKind.IMPLEMENTATION_OBLIGATION,
        stableKey = "task:$ref",
        status = ConvergenceStatus.OPEN,
        classification = "deviation",
        summary = "$ref: $note",
      )
    }
  return completedTasks + unresolved + deviations
}

private fun convergenceImplementationReceiptRecord(
  request: FeatureTaskRuntimePhaseStateRequest,
  generation: Int,
  createdAt: String,
  kind: ConvergenceRecordKind,
  stableKey: String,
  status: ConvergenceStatus,
  classification: String,
  summary: String,
): ConvergenceRecord {
  val logicalId = ConvergenceIdentities.logical(request.workflowId, kind, stableKey)
  return ConvergenceRecord(
    recordId = ConvergenceIdentities.record(request.workflowId, kind, logicalId, generation),
    logicalId = logicalId,
    kind = kind,
    provenance = ConvergenceProvenance(
      request.workflowId,
      generation,
      "implement",
      attempt = request.attemptCount,
    ),
    evidenceDigest = ConvergenceIdentities.digest("$stableKey|$summary"),
    createdAt = createdAt,
    status = status,
    classification = classification,
    summary = summary.take(skillbill.workflow.taskruntime.model.CONVERGENCE_SUMMARY_MAX_LENGTH),
  )
}

private fun convergenceImplementationAttemptRecords(
  workflowId: String,
  envelope: Map<String, Any?>,
  attempt: Int,
  createdAt: String,
): List<ConvergenceRecord> {
  val produced = envelope["produced_outputs"]?.let(JsonSupport::anyToStringAnyMap) ?: return emptyList()
  val completed = (produced["completed_task_ids"] as? List<*>).orEmpty()
    .mapNotNull { it as? String }
    .distinct()
  val unresolved = (produced["unresolved_items"] as? List<*>).orEmpty().mapNotNull { value ->
    when (value) {
      is String -> value
      is Map<*, *> -> value["ref"] as? String ?: value["note"] as? String
      else -> null
    }
  }.distinct()
  val deviations = (produced["deviations"] as? List<*>).orEmpty().mapNotNull { value ->
    (value as? Map<*, *>)?.get("ref") as? String
  }.distinct()
  val openRecords = (unresolved + deviations).distinct().map { ref ->
    val logicalId = ConvergenceIdentities.logical(
      workflowId,
      ConvergenceRecordKind.IMPLEMENTATION_OBLIGATION,
      "task:$ref",
    )
    ConvergenceRecord(
      recordId = ConvergenceIdentities.record(
        workflowId,
        ConvergenceRecordKind.IMPLEMENTATION_OBLIGATION,
        logicalId,
        attempt,
      ),
      logicalId = logicalId,
      kind = ConvergenceRecordKind.IMPLEMENTATION_OBLIGATION,
      provenance = ConvergenceProvenance(workflowId, attempt, "implement", attempt = attempt),
      evidenceDigest = ConvergenceIdentities.digest("$ref|${envelope["summary"]}"),
      createdAt = createdAt,
      status = ConvergenceStatus.OPEN,
      classification = "unresolved_item",
      summary = ref.take(skillbill.workflow.taskruntime.model.CONVERGENCE_SUMMARY_MAX_LENGTH),
    )
  }
  val completedRecords = completed.flatMap { ref ->
    listOf(
      convergenceImplementationAttemptRecord(
        workflowId,
        attempt,
        createdAt,
        ConvergenceRecordKind.IMPLEMENTATION_OUTCOME,
        ref,
        ConvergenceStatus.COMPLETED,
        "completed_task",
      ),
      convergenceImplementationAttemptRecord(
        workflowId,
        attempt,
        createdAt,
        ConvergenceRecordKind.IMPLEMENTATION_OBLIGATION,
        ref,
        ConvergenceStatus.RESOLVED,
        "resolved_item",
      ),
    )
  }
  return completedRecords + openRecords
}

private fun convergenceImplementationAttemptRecord(
  workflowId: String,
  attempt: Int,
  createdAt: String,
  kind: ConvergenceRecordKind,
  ref: String,
  status: ConvergenceStatus,
  classification: String,
): ConvergenceRecord {
  val logicalId = ConvergenceIdentities.logical(workflowId, kind, "task:$ref")
  return ConvergenceRecord(
    recordId = ConvergenceIdentities.record(workflowId, kind, logicalId, attempt),
    logicalId = logicalId,
    kind = kind,
    provenance = ConvergenceProvenance(workflowId, attempt, "implement", attempt = attempt),
    evidenceDigest = ConvergenceIdentities.digest("$ref|$status|$classification"),
    createdAt = createdAt,
    status = status,
    classification = classification,
    summary = ref.take(skillbill.workflow.taskruntime.model.CONVERGENCE_SUMMARY_MAX_LENGTH),
  )
}

private fun auditConvergenceRecords(
  request: FeatureTaskRuntimePhaseStateRequest,
  state: FeatureTaskRuntimeAuditRepairState,
  createdAt: String,
): List<ConvergenceRecord> {
  val isRepair = request.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT
  val generation = request.attemptCount * 2 + if (isRepair) 1 else 0
  val latestPlan = state.acceptedPlans.last()
  val gaps = latestPlan.gaps.flatMap { gap ->
    val gapLogicalId = ConvergenceIdentities.logical(
      request.workflowId,
      ConvergenceRecordKind.AUDIT_GAP,
      gap.gapId,
    )
    val gapRecord = ConvergenceRecord(
      recordId = ConvergenceIdentities.record(
        request.workflowId,
        ConvergenceRecordKind.AUDIT_GAP,
        gapLogicalId,
        generation,
      ),
      logicalId = gapLogicalId,
      kind = ConvergenceRecordKind.AUDIT_GAP,
      provenance = ConvergenceProvenance(request.workflowId, generation, "audit"),
      evidenceDigest = ConvergenceIdentities.digest(
        "${gap.gapId}|${gap.failureEvidence.artifactRef}|${gap.failureEvidence.checkRef}",
      ),
      createdAt = createdAt,
      status = ConvergenceStatus.OPEN,
      classification = gap.acceptanceCriterionRef.lowercase().replace('-', '_'),
      summary = gap.diagnosis.take(skillbill.workflow.taskruntime.model.CONVERGENCE_SUMMARY_MAX_LENGTH),
      path = gap.affectedBoundary.take(skillbill.workflow.taskruntime.model.CONVERGENCE_REFERENCE_MAX_LENGTH),
    )
    listOf(gapRecord) + gap.repairItems.map { item ->
      val logicalId = ConvergenceIdentities.logical(
        request.workflowId,
        ConvergenceRecordKind.AUDIT_REPAIR,
        item.repairItemId,
      )
      ConvergenceRecord(
        recordId = ConvergenceIdentities.record(
          request.workflowId,
          ConvergenceRecordKind.AUDIT_REPAIR,
          logicalId,
          generation,
        ),
        logicalId = logicalId,
        kind = ConvergenceRecordKind.AUDIT_REPAIR,
        provenance = ConvergenceProvenance(request.workflowId, generation, "audit"),
        evidenceDigest = ConvergenceIdentities.digest("${item.repairItemId}|${item.intendedOutcome}"),
        createdAt = createdAt,
        status = ConvergenceStatus.OPEN,
        classification = "repair_item",
        summary = item.intendedOutcome.take(skillbill.workflow.taskruntime.model.CONVERGENCE_SUMMARY_MAX_LENGTH),
        parentLogicalId = gapLogicalId,
        path = item.affectedPathsOrSymbols.first()
          .take(skillbill.workflow.taskruntime.model.CONVERGENCE_REFERENCE_MAX_LENGTH),
      )
    }
  }
  if (!isRepair) return emptyList()
  val terminal = state.repairItemResults.mapNotNull { result ->
    val gap = state.acceptedPlans.asReversed().asSequence().flatMap { it.gaps.asSequence() }
      .firstOrNull { candidate -> candidate.repairItems.any { it.repairItemId == result.repairItemId } }
      ?: return@mapNotNull null
    val logicalId = ConvergenceIdentities.logical(
      request.workflowId,
      ConvergenceRecordKind.AUDIT_REPAIR,
      result.repairItemId,
    )
    ConvergenceRecord(
      recordId = ConvergenceIdentities.record(
        request.workflowId,
        ConvergenceRecordKind.AUDIT_REPAIR,
        logicalId,
        generation,
      ),
      logicalId = logicalId,
      kind = ConvergenceRecordKind.AUDIT_REPAIR,
      provenance = ConvergenceProvenance(request.workflowId, generation, "audit"),
      evidenceDigest = ConvergenceIdentities.digest(
        "${result.repairItemId}|${result.outcome.name}|${result.resultEvidence.artifactRef}",
      ),
      createdAt = createdAt,
      status = ConvergenceStatus.RESOLVED,
      classification = result.outcome.name.lowercase(),
      summary = "audit repair ${result.repairItemId} ${result.outcome.name.lowercase()}",
      parentLogicalId = ConvergenceIdentities.logical(
        request.workflowId,
        ConvergenceRecordKind.AUDIT_GAP,
        gap.gapId,
      ),
      evidenceRef = result.resultEvidence.artifactRef,
    )
  }
  return terminal
}

private fun persistNormalizedAuditState(
  unitOfWork: UnitOfWork,
  request: FeatureTaskRuntimePhaseStateRequest,
  state: FeatureTaskRuntimeAuditRepairState,
  createdAt: String,
) {
  if (request.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT) {
    val decision = state.decisionGenerations.last()
    val generationId = AuditGenerationIdentities.generationId(request.workflowId, decision.generation)
    val unresolved = state.unresolvedGapLedger.unresolvedGaps.associateBy { it.gapId }
    val dispositions = state.priorGapDispositions.associateBy { it.gapId }
    val gaps = decision.plan.gaps.map { gap ->
      val unresolvedGap = unresolved[gap.gapId]
      val status = when (dispositions[gap.gapId]?.status) {
        FeatureTaskRuntimePriorGapDisposition.Status.RESOLVED -> AuditGapStatus.RESOLVED
        FeatureTaskRuntimePriorGapDisposition.Status.RECURRING -> AuditGapStatus.RECURRING
        FeatureTaskRuntimePriorGapDisposition.Status.SUPERSEDED -> AuditGapStatus.SUPERSEDED
        null -> if (unresolvedGap?.recurrence?.let { it > 0 } == true) {
          AuditGapStatus.RECURRING
        } else {
          AuditGapStatus.NEW
        }
      }
      AuditGap(
        gapId = gap.gapId,
        acceptanceCriterionRef = gap.acceptanceCriterionRef,
        acceptanceCriterionText = gap.acceptanceCriterionText,
        failureEvidence = gap.failureEvidence,
        diagnosis = gap.diagnosis,
        affectedBoundary = gap.affectedBoundary,
        status = status,
        recurrence = unresolvedGap?.recurrence ?: 0,
        firstSeenGeneration = gap.gapId.substringAfterLast("-gap-").toInt(),
      )
    }
    val repairItems = decision.plan.gaps.flatMap { gap ->
      gap.repairItems.map { item ->
        AuditRepairItem(
          itemId = item.repairItemId,
          gapId = gap.gapId,
          intendedOutcome = item.intendedOutcome,
          implementationActions = item.implementationActions,
          affectedPathsOrSymbols = item.affectedPathsOrSymbols,
          requiredVerification = item.requiredVerification,
          dependencies = item.dependsOn,
        )
      }
    }
    val batch = repairItems.takeIf { it.isNotEmpty() }?.let {
      AuditRepairBatch(
        batchId = ConvergenceIdentities.logical(
          request.workflowId,
          ConvergenceRecordKind.AUDIT_REPAIR,
          "batch:$generationId",
        ),
        generationId = generationId,
        repairItems = it,
        dependencies = it.associate { item -> item.itemId to item.dependencies },
        isActive = true,
      )
    }
    unitOfWork.auditGenerations.persist(
      AuditGeneration(
        generationId = generationId,
        workflowId = request.workflowId,
        generation = decision.generation,
        repositoryCheckpoint = RepositoryCheckpoint(
          fingerprint = requireNotNull(decision.repositoryFingerprint ?: request.repositoryFingerprint),
          evidenceRef = "audit:$generationId",
        ),
        satisfiedCriterionRefs = state.satisfiedCriterionRefs,
        gaps = gaps,
        repairBatch = batch,
        createdAt = createdAt,
      ),
    )
    state.priorGapDispositions.forEach { disposition ->
      unitOfWork.auditRepairs.appendDisposition(
        request.workflowId,
        AuditGapDisposition(
          gapId = disposition.gapId,
          status = when (disposition.status) {
            FeatureTaskRuntimePriorGapDisposition.Status.RESOLVED -> AuditGapStatus.RESOLVED
            FeatureTaskRuntimePriorGapDisposition.Status.RECURRING -> AuditGapStatus.RECURRING
            FeatureTaskRuntimePriorGapDisposition.Status.SUPERSEDED -> AuditGapStatus.SUPERSEDED
          },
          evidence = disposition.evidence,
          dispositionGeneration = decision.generation,
          supersededByGeneration = (decision.generation + 1)
            .takeIf { disposition.status == FeatureTaskRuntimePriorGapDisposition.Status.SUPERSEDED },
        ),
      )
    }
  }
  if (request.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT) {
    val generation = state.decisionGenerations.last().generation
    state.repairItemResults.forEach { result ->
      unitOfWork.auditRepairs.appendResult(
        request.workflowId,
        PersistedAuditRepairItemResult(
          itemId = result.repairItemId,
          outcome = PersistedAuditRepairItemResult.Outcome.valueOf(result.outcome.name),
          evidenceRef = result.resultEvidence.artifactRef,
          verificationRef = result.resultEvidence.checkRef,
          dispositionGeneration = generation,
        ),
      )
    }
  }
}

internal fun reconcileLatestRepairResults(
  priorResults: List<FeatureTaskRuntimeRepairItemResult>,
  currentResults: List<FeatureTaskRuntimeRepairItemResult>,
): List<FeatureTaskRuntimeRepairItemResult> = priorResults + currentResults

internal data class GoalReviewPhaseCompletionRequest(
  val phaseState: FeatureTaskRuntimePhaseStateRequest,
  val verdict: FeatureTaskRuntimeVerdict,
  val unresolvedFindingCount: Int,
  val findings: List<GoalSubtaskReviewCompactFinding>,
  val rawReviewResult: String,
  val blockerDispositions: List<GoalSubtaskBlockerDisposition> = emptyList(),
)

private data class CompletedGoalReviewPhaseRecords(
  val records: Map<String, FeatureTaskRuntimePhaseRecord>,
  val ledger: List<Map<String, Any?>>,
)
private data class WorkflowRowAdvance(
  val currentStepId: String,
  val workflowStatus: String,
  val stepUpdates: List<Map<String, Any?>>? = null,
) {
  companion object {
    fun keepFrom(record: WorkflowStateSnapshot): WorkflowRowAdvance =
      WorkflowRowAdvance(currentStepId = record.currentStepId, workflowStatus = record.workflowStatus)
  }
}
private fun stepUpdatesFrom(records: Map<String, FeatureTaskRuntimePhaseRecord>): List<Map<String, Any?>> {
  fun stepStatusFor(record: FeatureTaskRuntimePhaseRecord): String = when {
    record.status == FEATURE_TASK_RUNTIME_PHASE_STATUS_BLOCKED -> FEATURE_TASK_RUNTIME_PHASE_STATUS_BLOCKED
    record.status == FEATURE_TASK_RUNTIME_PHASE_STATUS_PAUSED -> FEATURE_TASK_RUNTIME_PHASE_STATUS_PAUSED
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

@Inject
class FeatureTaskRuntimeDecomposeTerminalRecorder(
  private val database: DatabaseSessionFactory,
  private val workflowSnapshotValidator: WorkflowSnapshotValidator,
) {
  private val engine: WorkflowEngine = WorkflowEngine(workflowSnapshotValidator)

  fun recordDecomposeTerminal(
    workflowId: String,
    terminal: FeatureTaskRuntimeDecomposeTerminal,
    dbOverride: String? = null,
  ): Boolean = database.transaction(dbOverride) { unitOfWork ->
    val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, workflowId)
      ?: return@transaction false
    val updated = engine.updateRecord(
      WorkflowFamily.TASK_RUNTIME.definition,
      record,
      WorkflowUpdateInput(
        workflowStatus = "completed",
        currentStepId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN,
        stepUpdates = null,
        artifactsPatch = mapOf(FEATURE_TASK_RUNTIME_DECOMPOSE_TERMINAL_ARTIFACT_KEY to terminal.toArtifactMap()),
        sessionId = record.sessionId.orEmpty(),
      ),
    )
    WorkflowFamily.TASK_RUNTIME.save(unitOfWork.workflowStates, updated)
    true
  }

  fun loadDecomposeTerminal(workflowId: String, dbOverride: String? = null): FeatureTaskRuntimeDecomposeTerminal? =
    database.read(dbOverride) { unitOfWork ->
      val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, workflowId)
        ?: return@read null
      decomposeTerminalFrom(decodeArtifacts(record.artifactsJson))
    }
}

private const val IMPLEMENTATION_RECEIPT_ATTEMPT_HISTORY_ARTIFACT_KEY =
  "feature_task_runtime_implementation_receipt_attempt_history"
private const val IMPLEMENTATION_RECEIPT_ATTEMPT_HISTORY_LIMIT = 32
private fun workflowStatusFor(request: FeatureTaskRuntimePhaseStateRequest): String = when {
  request.status == FEATURE_TASK_RUNTIME_PHASE_STATUS_PAUSED -> "paused"
  request.status == FEATURE_TASK_RUNTIME_PHASE_STATUS_BLOCKED -> "blocked"
  request.finished && request.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.definition.stepIds.last() ->
    "completed"
  else -> "running"
}

private fun durationMillis(startedAt: String, finishedAt: String): Long =
  Duration.between(Instant.parse(startedAt), Instant.parse(finishedAt)).toMillis().coerceAtLeast(0)
