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
import skillbill.contracts.JsonSupport
import skillbill.error.InvalidFeatureTaskRuntimeHandoffProjectionError
import skillbill.error.InvalidWorkflowStateSchemaError
import skillbill.error.WorkflowIssueKeyConflictError
import skillbill.ports.persistence.DatabaseSessionFactory
import skillbill.ports.persistence.ProducerOutputEvidence
import skillbill.ports.persistence.ProducerOutputEvidenceValidator
import skillbill.ports.persistence.RejectedOutputDiagnosticMetadataValidator
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.persistence.WorkflowStateRepository
import skillbill.ports.persistence.model.FeatureTaskRuntimeWorkerOwnership
import skillbill.ports.persistence.model.FeatureTaskWorkflowMode
import skillbill.ports.persistence.model.RejectedOutputDiagnosticError
import skillbill.workflow.FeatureTaskRuntimeHandoffEnvelopeValidator
import skillbill.workflow.FeatureTaskRuntimeHandoffFoundationValidator
import skillbill.workflow.FeatureTaskRuntimeImplementationAttemptValidator
import skillbill.workflow.FeatureTaskRuntimeQuarantineValidator
import skillbill.workflow.NoopFeatureTaskRuntimeImplementationAttemptValidator
import skillbill.workflow.NoopFeatureTaskRuntimeQuarantineValidator
import skillbill.workflow.WorkflowEngine
import skillbill.workflow.WorkflowSnapshotValidator
import skillbill.workflow.model.WorkflowStateSnapshot
import skillbill.workflow.model.WorkflowUpdateInput
import skillbill.workflow.model.appendBoundedHistoryBySequence
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_AUDIT_REPAIR_STATE_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_DECOMPOSE_TERMINAL_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_DELIVERED_PROJECTIONS_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_IMPLEMENTATION_ATTEMPTS_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_BRIEFINGS_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_LEDGER_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_LEDGER_LIMIT
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_STATUS_BLOCKED
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_STATUS_PAUSED
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_QUARANTINED_RECORDS_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_RESOLVED_BRANCH_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_REVIEW_GENERATION_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditRepairState
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeDecomposeTerminal
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeDeliveredProjectionRecord
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeEvidence
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeGoalContinuationArtifact
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeImplementationAttempt
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeImplementationAttemptStatus
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeOperatorBlockRetry
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerEntry
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePriorGapDisposition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeProducerIteration
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeProjectionFailureClassification
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeProjectionKind
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
import skillbill.workflow.taskruntime.model.featureTaskRuntimeAppendImplementationAttempt
import skillbill.workflow.taskruntime.model.featureTaskRuntimeImplementationAttemptRecordToWire
import skillbill.workflow.taskruntime.model.featureTaskRuntimeImplementationAttemptsFromWire
import skillbill.workflow.taskruntime.model.featureTaskRuntimeQuarantineEntriesFromWire
import skillbill.workflow.taskruntime.model.featureTaskRuntimeQuarantineRecordToWire
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

/**
 * Application-layer write/read seam for feature-task-runtime per-phase records and the
 * append-only phase ledger. Timestamps and durations are always minted here from the runtime
 * clock, never taken from agent-reported values.
 */
@Inject
// cohesive durable read/write seam for per-phase records, briefings, ledger, and quarantine evidence
@Suppress("TooManyFunctions", "LargeClass", "LongParameterList")
class FeatureTaskRuntimePhaseRecorder(
  private val database: DatabaseSessionFactory,
  private val workflowSnapshotValidator: WorkflowSnapshotValidator,
  private val handoffEnvelopeValidator: FeatureTaskRuntimeHandoffEnvelopeValidator,
  private val handoffFoundationValidator: FeatureTaskRuntimeHandoffFoundationValidator,
  private val quarantineValidator: FeatureTaskRuntimeQuarantineValidator = NoopFeatureTaskRuntimeQuarantineValidator,
  private val implementationAttemptValidator: FeatureTaskRuntimeImplementationAttemptValidator =
    NoopFeatureTaskRuntimeImplementationAttemptValidator,
  private val rejectedOutputDiagnosticMetadataValidator: RejectedOutputDiagnosticMetadataValidator = { },
  private val producerOutputEvidenceValidator: ProducerOutputEvidenceValidator = { },
) {
  private val engine: WorkflowEngine = WorkflowEngine(workflowSnapshotValidator)

  fun recordRejectedOutput(
    request: RejectedOutputDiagnosticRequest,
    dbOverride: String? = null,
    producerGeneration: Int = 0,
  ) = database.transaction(dbOverride) { unitOfWork ->
    val service = diagnosticService(unitOfWork)
    service.retainProducerOutput(
      ProducerOutputEvidence(
        workflowId = request.workflowId,
        phaseId = request.phaseId,
        attempt = request.attempt,
        agentId = request.agentId,
        model = request.model,
        recordedAt = java.time.Instant.now(),
        byteSize = request.observedByteSize,
        sha256 = request.observedSha256,
        payload = request.rawResponse.takeUnless { request.truncated },
        generation = producerGeneration,
      ),
    )
    service.record(request)
  }

  fun retainProducerOutput(evidence: ProducerOutputEvidence, dbOverride: String? = null) =
    database.transaction(dbOverride) { unitOfWork ->
      diagnosticService(unitOfWork).retainProducerOutput(evidence)
    }

  fun producerOutput(
    workflowId: String,
    phaseId: String,
    attempt: Int,
    dbOverride: String? = null,
    generation: Int = 0,
  ) = database.read(dbOverride) {
    it.rejectedOutputDiagnostics?.readProducerOutput(workflowId, phaseId, attempt, generation)
  }

  private fun diagnosticService(unitOfWork: UnitOfWork): RejectedOutputDiagnosticService {
    val repository = unitOfWork.rejectedOutputDiagnostics
      ?: throw RejectedOutputDiagnosticError.Persistence("repository-unavailable")
    val permissions = unitOfWork.rejectedOutputDiagnosticPermissions
      ?: throw RejectedOutputDiagnosticError.Permission("permissions-unavailable")
    return RejectedOutputDiagnosticService(
      repository = repository,
      permissions = permissions,
      metadataValidator = rejectedOutputDiagnosticMetadataValidator,
      producerEvidenceValidator = producerOutputEvidenceValidator,
    )
  }

  /**
   * Persists one per-phase record. A `running` transition for a new attempt re-mints
   * `started_at` so `duration_millis` measures only the current run (never spanning a
   * resume gap), while `first_started_at` preserves the original first-started timestamp.
   * A finishing call mints `finished_at` and derives `duration_millis` from the re-minted
   * `started_at`. A `blocked` status persists a durable terminal record (with the blocked
   * reason) so blocked-ness survives ledger pruning. The coarse workflow row is advanced to
   * the active phase and the matching workflow status. Returns true when the workflow row
   * exists and was updated.
   */
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
      ) + implementationAttemptPatch(artifacts, request, attemptStatusFor(request))
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

  @Suppress("LongMethod", "CyclomaticComplexMethod", "ComplexCondition")
  fun recordCompletedPhase(request: FeatureTaskRuntimePhaseStateRequest, dbOverride: String? = null): Boolean {
    require(request.status == "completed" && request.finished)
    return database.transaction(dbOverride) { unitOfWork ->
      val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, request.workflowId)
        ?: return@transaction false
      val artifacts = decodeArtifacts(record.artifactsJson)
      val existingRecords = phaseRecordsFrom(artifacts)
      val updatedRecords = LinkedHashMap(existingRecords).apply {
        put(request.phaseId, phaseRecordFor(request, existingRecords[request.phaseId], Instant.now().toString()))
      }
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
      )
      val updatedLedger = appendBoundedHistoryBySequence(
        ledger.map { it.toArtifactMap() },
        completion.toArtifactMap(),
        FEATURE_TASK_RUNTIME_PHASE_LEDGER_LIMIT,
      )
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
      val auditRepairPatch = if (latestPlan != null || repairResults != null || effectiveDispositions != null ||
        reconcilesAuditState
      ) {
        mapOf(
          FEATURE_TASK_RUNTIME_AUDIT_REPAIR_STATE_ARTIFACT_KEY to auditRepairStateToWire(
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
            ),
          ),
        )
      } else {
        emptyMap()
      }
      persistPatch(
        unitOfWork.workflowStates,
        record,
        mapOf(
          FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY to
            updatedRecords.mapValues { (_, value) -> value.toArtifactMap() },
          FEATURE_TASK_RUNTIME_PHASE_LEDGER_ARTIFACT_KEY to updatedLedger,
        ) + auditRepairPatch +
          implementationAttemptPatch(artifacts, request, FeatureTaskRuntimeImplementationAttemptStatus.COMPLETED),
        WorkflowRowAdvance(request.phaseId, workflowStatusFor(request), stepUpdatesFrom(updatedRecords)),
      )
      true
    }
  }

  /**
   * The durable implementation-attempt append for one phase write, as an artifact patch to be merged
   * into the SAME `persistPatch` call that advances the workflow row.
   *
   * Returning a patch rather than performing a second write is the point: a crash between the receipt
   * landing and the workflow advancing is then not a reachable state, so resume always finds exactly
   * one resumable attempt with no lost obligations. Every appended record is validated against the
   * canonical schema before it is handed back, so a malformed attempt is rejected before any write.
   *
   * Empty for every non-mutating phase and for any write that carries no implementation receipt.
   */
  private fun implementationAttemptPatch(
    artifacts: Map<String, Any?>,
    request: FeatureTaskRuntimePhaseStateRequest,
    attemptStatus: FeatureTaskRuntimeImplementationAttemptStatus,
  ): Map<String, Any?> {
    if (!FeatureTaskRuntimePhaseWorkflowDefinition.isMutatingPhase(request.phaseId)) return emptyMap()
    val envelope = request.normalizedOutput?.envelope ?: return emptyMap()
    val carriesReceipt = JsonSupport.anyToStringAnyMap(envelope["produced_outputs"])
      ?.get("projection_kind") == FeatureTaskRuntimeProjectionKind.IMPLEMENTATION_RECEIPT.wireValue
    if (!carriesReceipt) return emptyMap()
    val existing = implementationAttemptsFrom(artifacts)
    val claim = featureTaskRuntimeImplementationClaimFrom(
      envelope,
      FeatureTaskRuntimeImplementationObligations(emptyList(), emptyList(), request.loopId),
    )
    val appended = featureTaskRuntimeAppendImplementationAttempt(
      existing = existing,
      entry = FeatureTaskRuntimeImplementationAttempt(
        sequenceNumber = (existing.maxOfOrNull { it.sequenceNumber } ?: -1) + 1,
        phaseId = request.phaseId,
        attemptNumber = request.attemptCount,
        agentId = request.resolvedAgentId,
        status = attemptStatus,
        recordedAt = Instant.now().toString(),
        completedTaskIds = claim.completedTaskIds.distinct(),
        changedPaths = claim.changedPaths.distinct(),
        loopId = request.loopId,
        edgeIteration = request.edgeIteration,
        failureDisposition = request.failureDisposition,
        deviations = claim.deviations,
        unresolvedItems = claim.unresolvedItems,
        reconciliationEvidence = claim.reconciliationEvidence,
        repositoryCheckpoint = claim.repositoryCheckpoint,
      ),
    )
    val wire = featureTaskRuntimeImplementationAttemptRecordToWire(appended)
    implementationAttemptValidator.validateImplementationAttemptRecord(
      wire,
      FEATURE_TASK_RUNTIME_IMPLEMENTATION_ATTEMPTS_ARTIFACT_KEY,
    )
    return mapOf(FEATURE_TASK_RUNTIME_IMPLEMENTATION_ATTEMPTS_ARTIFACT_KEY to wire)
  }

  /**
   * Records a semantically incomplete implementation attempt. This path has no workflow advance to
   * ride along with — the phase is being continued, not settled — so it is the one attempt write that
   * is legitimately its own transaction.
   */
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
    persistPatch(unitOfWork.workflowStates, record, patch)
    true
  }

  /**
   * Strict read of the durable implementation-attempt history in append order. An absent key yields
   * an empty list; a malformed record loud-fails. Returns null only when the workflow row is absent.
   */
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

  /**
   * Returns the review-generation ordinal in force after the invalidation, or null when the workflow
   * row is absent. The ordinal keys retained producer evidence, so it must advance in the same
   * transaction that rewinds the attempt watermarks.
   */
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
      nextGeneration
    }

  /**
   * Brings a workflow tombstoned before the review-generation ordinal existed up to generation 1 on
   * its next run. Such a workflow never re-enters the invalidation seam, so without this it would
   * keep writing fresh review evidence at the prior generation's key. The ordinal is durable and only
   * ever advances, so it stays at 1 once the fresh review overwrites the tombstone.
   */
  internal fun reconcileReviewGeneration(workflowId: String, dbOverride: String? = null): Int =
    database.transaction(dbOverride) { unitOfWork ->
      val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, workflowId)
        ?: return@transaction 0
      val artifacts = decodeArtifacts(record.artifactsJson)
      val storedGeneration = reviewGenerationFrom(artifacts)
      val tombstoned = phaseRecordsFrom(artifacts)[FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW]
        ?.resolvedAgentId == REVIEW_INVALIDATION_AGENT_ID
      if (!tombstoned || storedGeneration > 0) return@transaction storedGeneration
      persistPatch(
        unitOfWork.workflowStates,
        record,
        mapOf(FEATURE_TASK_RUNTIME_REVIEW_GENERATION_ARTIFACT_KEY to 1),
      )
      1
    }

  /**
   * SKILL-140: durably invalidates a quarantined producer's settled `completed` status so its rejected
   * record is no longer selected by the handoff contract on this or any resumed run. The rejected
   * payload is moved from `output_artifact` to `rejected_output` (retained as evidence but no longer a
   * usable output) and the status returns to `running`, so hydration relaunches the producer and the
   * regenerated higher-iteration output supersedes it. Only the settled status is touched; the rejected
   * record's fields are never rewritten or migrated. The regeneration loop context (`loopId`,
   * `edgeIteration`) is stamped onto the invalidated running record in this same transaction, so the
   * per-edge cap watermark survives a crash that lands after this commit but before the LOOP_EDGE ledger
   * write; resume reseeds the cap from the record rather than resetting it to zero (AC-003). Returns true
   * when the workflow row exists.
   */
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
      val completedState = reviewArtifacts.state.completeReservedPass(
        verdict = completion.verdict,
        unresolvedFindingCount = completion.unresolvedFindingCount,
        findings = completion.findings,
        blockerDispositions = completion.blockerDispositions,
      )
      val passNumber = completedState.completedPassCount.toString()
      val existingRecords = phaseRecordsFrom(artifacts)
      val phaseRecord = phaseRecordFor(request, existingRecords[request.phaseId], Instant.now().toString())
      val updatedRecords = LinkedHashMap(existingRecords).apply { put(request.phaseId, phaseRecord) }
      val ledger = phaseLedgerFrom(artifacts)
      val completionEntry = FeatureTaskRuntimePhaseLedgerEntry(
        action = skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerAction.COMPLETE,
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
        completionEntry.toArtifactMap(),
        FEATURE_TASK_RUNTIME_PHASE_LEDGER_LIMIT,
      )
      val continuation = reviewArtifacts.continuation
      persistUnaddressedFindings(unitOfWork, request, continuation, passNumber.toInt())
      persistPatch(
        unitOfWork.workflowStates,
        record,
        mapOf(
          GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY to completedState.toArtifactMap(),
          GOAL_SUBTASK_REVIEW_RESULTS_ARTIFACT_KEY to
            (reviewArtifacts.rawResults + (passNumber to completion.rawReviewResult)),
          FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY to
            updatedRecords.mapValues { (_, value) -> value.toArtifactMap() },
          FEATURE_TASK_RUNTIME_PHASE_LEDGER_ARTIFACT_KEY to updatedLedger,
        ),
        WorkflowRowAdvance(
          currentStepId = request.phaseId,
          workflowStatus = workflowStatusFor(request),
          stepUpdates = stepUpdatesFrom(updatedRecords),
        ),
      )
      true
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

  /**
   * Durably drops the backward-edge context (loop_id + edge_iteration) from the named phase records
   * without otherwise mutating them. Used when a wider backward edge restarts a nested loop: the
   * nested loop's per-phase watermark is stale for the new outer iteration, so it must be cleared at
   * the durable source of truth or resume reconstruction would re-import the pre-reset count and deny
   * the fresh per-iteration budget. Phases without a record (or already context-free) are skipped.
   * Returns true when the workflow row exists.
   */
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

  /**
   * Persists the assembled per-phase launch briefing keyed by phase id, plus the delivered
   * projection for that launch under its own artifact key; the latest entry per phase replaces the
   * prior one. The envelope is schema-validated before it is written, so a violating envelope never
   * becomes durable state. Returns true when the workflow row exists and was updated.
   */
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

  /**
   * Records a content-free measurement when projection construction rejects a launch before a
   * briefing exists. Zero sizes mean no projection crossed the launch boundary.
   */
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

  /**
   * Strict read of the per-phase briefings keyed by phase id; an absent key yields an empty
   * map and a malformed entry loud-fails. Returns null only when the workflow row is absent.
   */
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

  /**
   * Strict read of the delivered-projection tier keyed by consumer phase id. Separate from
   * [loadPhaseBriefings] on purpose: this store holds only envelopes, so no read of it can return
   * private phase evidence.
   */
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

  /**
   * Appends one phase ledger entry, minting the timestamp and assigning the next monotonic
   * sequence from the persisted max. Returns true when the workflow row exists and was updated.
   */
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
      persistPatch(
        unitOfWork.workflowStates,
        record,
        mapOf(FEATURE_TASK_RUNTIME_PHASE_LEDGER_ARTIFACT_KEY to updatedLedger),
      )
      true
    }

  /**
   * Appends one quarantined durable record to the private, append-only evidence store. The runtime
   * only ever grows this list; a prior entry is never mutated or removed by any runtime path (only
   * out-of-band operator action may). The full wire record is validated against the canonical
   * quarantine schema before persistence, so a malformed store fails loudly at the write seam.
   * Returns true when the workflow row exists and was updated.
   */
  fun appendQuarantineEntry(
    workflowId: String,
    entry: FeatureTaskRuntimeQuarantineEntry,
    dbOverride: String? = null,
  ): Boolean = database.transaction(dbOverride) { unitOfWork ->
    val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, workflowId)
      ?: return@transaction false
    val artifacts = decodeArtifacts(record.artifactsJson)
    val existing = quarantineEntriesFrom(artifacts)
    // Crash-replay idempotency: a resume that re-rejects the same producer record at the same
    // regeneration attempt must not append a duplicate evidence entry. Append only genuinely new
    // evidence; never mutate or remove a prior entry.
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

  /**
   * Strict read of the private quarantine evidence store in insertion order. An absent key yields an
   * empty list; a malformed record loud-fails. Returns null only when the workflow row is absent.
   */
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

  /**
   * Persists the run-scoped resolved feature branch exactly once. Idempotent and non-divergent:
   * when a branch is already persisted this is a no-op (returns true) and never overwrites it, so a
   * resume/re-run can never force a second or divergent branch for the same run. Returns true when
   * the workflow row exists; false only when the row is absent.
   */
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

  /**
   * Strict read of the run-scoped resolved feature branch. Returns null when the workflow row is
   * absent or no branch has been resolved yet; a malformed entry loud-fails.
   */
  fun loadResolvedBranch(workflowId: String, dbOverride: String? = null): FeatureTaskRuntimeResolvedBranch? =
    database.read(dbOverride) { unitOfWork ->
      val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, workflowId)
        ?: return@read null
      resolvedBranchFrom(decodeArtifacts(record.artifactsJson))
    }

  fun recordWorkflowOwnedPaths(workflowId: String, ownedPaths: List<String>, dbOverride: String? = null): Boolean =
    database.transaction(dbOverride) { unitOfWork ->
      val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, workflowId)
        ?: return@transaction false
      val resolved = resolvedBranchFrom(decodeArtifacts(record.artifactsJson)) ?: return@transaction false
      val updated = resolved.copy(workflowOwnedPaths = ownedPaths.distinct().sorted())
      persistPatch(
        unitOfWork.workflowStates,
        record,
        mapOf(FEATURE_TASK_RUNTIME_RESOLVED_BRANCH_ARTIFACT_KEY to updated.toArtifactMap()),
      )
      true
    }

  /**
   * Strict read of the per-phase records keyed by phase id; an absent key yields an empty map
   * and a malformed record loud-fails. Returns null only when the workflow row is absent.
   */
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

  /**
   * Strict read of the append-only phase ledger. A block is recorded both as a durable terminal
   * per-phase record (so blocked-ness survives ledger pruning) and as a ledger entry; this read
   * supplies the supplementary per-attempt detail. Absent key yields an empty list; a malformed
   * entry loud-fails. Returns null only when the workflow row is absent.
   */
  fun loadPhaseLedger(workflowId: String, dbOverride: String? = null): List<FeatureTaskRuntimePhaseLedgerEntry>? =
    database.read(dbOverride) { unitOfWork ->
      val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, workflowId)
        ?: return@read null
      phaseLedgerFrom(decodeArtifacts(record.artifactsJson))
    }

  /** Reads a workflow row's mode without throwing on a foreign mode, unlike [WorkflowFamily.TASK_RUNTIME.get]. */
  fun existingWorkflowMode(workflowId: String, dbOverride: String? = null): FeatureTaskWorkflowMode? =
    database.read(dbOverride) { unitOfWork ->
      unitOfWork.workflowStates.getFeatureTaskWorkflow(workflowId)?.mode
    }

  fun workerOwnership(workflowId: String, dbOverride: String? = null): FeatureTaskRuntimeWorkerOwnership? =
    database.read(dbOverride) { unitOfWork ->
      unitOfWork.workflowStates.getFeatureTaskRuntimeWorkerOwnership(workflowId)
    }

  /**
   * Ensures a runtime workflow row exists, opening one at the definition's initial step when
   * absent. Idempotent: a no-op when a row already exists.
   */
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
    // The per-phase records map is the detailed source of truth; the coarse workflow row AND the
    // shared per-step steps[] are advanced to agree with it so the generic workflow
    // get/list/latest and the resume gate do not disagree with FeatureTaskRuntimeStatusService.
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
      repairEvidence = request.repairEvidence,
    )
  }

  private companion object {
    const val STATUS_RUNNING = "running"
  }
}

internal fun reconcileLatestRepairResults(
  priorResults: List<FeatureTaskRuntimeRepairItemResult>,
  currentResults: List<FeatureTaskRuntimeRepairItemResult>,
  latestPlanItemIds: Set<String>,
): List<FeatureTaskRuntimeRepairItemResult> = linkedMapOf<String, FeatureTaskRuntimeRepairItemResult>().apply {
  priorResults.forEach { put(it.repairItemId, it) }
  currentResults.forEach { put(it.repairItemId, it) }
  keys.retainAll(latestPlanItemIds)
}.values.toList()

internal data class GoalReviewPhaseCompletionRequest(
  val phaseState: FeatureTaskRuntimePhaseStateRequest,
  val verdict: FeatureTaskRuntimeVerdict,
  val unresolvedFindingCount: Int,
  val findings: List<GoalSubtaskReviewCompactFinding>,
  val rawReviewResult: String,
  val blockerDispositions: List<GoalSubtaskBlockerDisposition> = emptyList(),
)

// How the coarse workflow row + shared steps[] advance alongside a per-phase record write. Grouping
// these together keeps persistPatch a three-argument seam; the default keeps the row untouched for
// writes (briefings, ledger, resolved branch) that only patch artifacts.
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

// Projects the per-phase records map onto shared per-step step_updates so steps[] tracks records
// in lockstep: each record's runtime status maps to its step status and carries its attempt count.
// The engine's mergeStepUpdates preserves definition order and leaves unmentioned steps untouched,
// so prior completed phases keep their completed step and only the touched phases are rewritten.
//
// Phase statuses share the step-status vocabulary (running/completed/blocked): a blocked record
// stays blocked even when it also carries a finished timestamp; otherwise a finished record is
// completed. An unrecognized status loud-fails rather than silently producing an out-of-vocabulary
// step status the engine would reject.
private fun stepUpdatesFrom(records: Map<String, FeatureTaskRuntimePhaseRecord>): List<Map<String, Any?>> {
  fun stepStatusFor(record: FeatureTaskRuntimePhaseRecord): String = when {
    record.status == FEATURE_TASK_RUNTIME_PHASE_STATUS_BLOCKED -> FEATURE_TASK_RUNTIME_PHASE_STATUS_BLOCKED
    // A paused record is resumable, not finished: it keeps its paused step status even though the
    // pause is recorded with a finished timestamp.
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

// Coarse workflow-row status mirrors the phase transition: a paused phase leaves the row on the
// non-terminal resumable status, a blocked phase blocks the row, the final phase completing completes
// it, every other transition keeps it running. The per-phase records map remains the detailed source
// of truth.
private fun workflowStatusFor(request: FeatureTaskRuntimePhaseStateRequest): String = when {
  request.status == FEATURE_TASK_RUNTIME_PHASE_STATUS_PAUSED -> "paused"
  request.status == FEATURE_TASK_RUNTIME_PHASE_STATUS_BLOCKED -> "blocked"
  request.finished && request.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.definition.stepIds.last() ->
    "completed"
  else -> "running"
}

// The attempt status a non-completing phase write records. A blocked write carries the receipt the
// block was decided on; anything else that reaches this seam is a still-running transition, which is
// recorded as incomplete rather than claiming an outcome the phase has not reached.
private fun attemptStatusFor(
  request: FeatureTaskRuntimePhaseStateRequest,
): FeatureTaskRuntimeImplementationAttemptStatus = when (request.status) {
  "completed" -> FeatureTaskRuntimeImplementationAttemptStatus.COMPLETED
  FEATURE_TASK_RUNTIME_PHASE_STATUS_BLOCKED -> FeatureTaskRuntimeImplementationAttemptStatus.BLOCKED
  else -> FeatureTaskRuntimeImplementationAttemptStatus.INCOMPLETE
}

private fun durationMillis(startedAt: String, finishedAt: String): Long =
  Duration.between(Instant.parse(startedAt), Instant.parse(finishedAt)).toMillis().coerceAtLeast(0)
