package skillbill.application.featuretask

import me.tatarka.inject.annotations.Inject
import skillbill.application.decomposition.decodeArtifacts
import skillbill.application.featuretask.model.FeatureTaskRuntimeRejectedOutputWrite
import skillbill.application.goalrunner.GoalSubtaskReviewSummaryReducer
import skillbill.application.goalrunner.UnaddressedFindingLedgerScope
import skillbill.application.model.FeatureTaskRuntimePhaseLaunchBriefing
import skillbill.application.model.FeatureTaskRuntimePhaseLedgerRequest
import skillbill.application.model.FeatureTaskRuntimePhaseStateRequest
import skillbill.application.normalizeIssueKey
import skillbill.application.workflow.WorkflowFamily
import skillbill.application.workflow.toRecord
import skillbill.contracts.JsonSupport
import skillbill.error.InvalidFeatureTaskRuntimeCheckpointIdentityVersionError
import skillbill.error.InvalidFeatureTaskRuntimeHandoffProjectionError
import skillbill.error.InvalidProducerOutputEvidenceSchemaError
import skillbill.error.InvalidRejectedOutputDiagnosticSchemaError
import skillbill.error.InvalidWorkflowStateSchemaError
import skillbill.error.WorkflowIssueKeyConflictError
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.persistence.DatabaseSessionFactory
import skillbill.ports.persistence.ProducerOutputEvidence
import skillbill.ports.persistence.ProducerOutputEvidenceValidator
import skillbill.ports.persistence.RejectedOutputDiagnosticMetadataValidator
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.persistence.WorkflowStateRepository
import skillbill.ports.persistence.model.FeatureTaskRuntimeWorkerOwnership
import skillbill.ports.persistence.model.FeatureTaskWorkflowMode
import skillbill.ports.persistence.model.RejectedOutputDiagnosticError
import skillbill.ports.persistence.model.evidenceKey
import skillbill.review.model.ReviewFindingVerdict
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
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_AUDIT_GAP_PAUSE_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_AUDIT_GAP_PROGRESS_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_BUILD_GATE_PROGRESS_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_CHECKPOINT_IDENTITIES_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_DECOMPOSE_TERMINAL_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_DELIVERED_PROJECTIONS_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_DIAGNOSTIC_SIGNALS_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_FINDING_VERIFICATION_BOUNDARY_SELECTION_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_FINDING_VERIFICATION_CHECKPOINT_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_FINDING_VERIFICATION_DISPOSITIONS_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_IMPLEMENTATION_ATTEMPTS_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_BRIEFINGS_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_LEDGER_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_LEDGER_LIMIT
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_STATUS_BLOCKED
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_STATUS_PAUSED
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_STATUS_PENDING
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_QUARANTINED_RECORDS_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_RESOLVED_BRANCH_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_REVIEW_GENERATION_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_VALIDATION_GATE_PROGRESS_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditGapPause
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditGapProgress
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeCheckpointIdentity
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeDecomposeTerminal
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeDeliveredProjectionRecord
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeDiagnosticDegradationMeasurement
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeDiagnosticFailureClass
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeDiagnosticSignal
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFindingVerificationDisposition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeGoalContinuationArtifact
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeImplementationAttempt
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeImplementationAttemptStatus
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeOperatorBlockRetry
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerEntry
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeProducerIteration
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeProjectionFailureClassification
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeProjectionMeasurement
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeQualityGateSelection
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeQuarantineEntry
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRejectionMeasurement
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeResolvedBranch
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeSharedEvidenceMeasurement
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeValidationGateProgress
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerificationBoundaryHeadingProvenance
import skillbill.workflow.taskruntime.model.GOAL_SUBTASK_REVIEW_RESULTS_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.GoalSubtaskBlockerDisposition
import skillbill.workflow.taskruntime.model.GoalSubtaskCommitFocusedAccounting
import skillbill.workflow.taskruntime.model.GoalSubtaskReviewArtifactDecoder
import skillbill.workflow.taskruntime.model.GoalSubtaskReviewCompactFinding
import skillbill.workflow.taskruntime.model.GoalSubtaskReviewState
import skillbill.workflow.taskruntime.model.PhaseHandoffProjectionDeclaration
import skillbill.workflow.taskruntime.model.QUARANTINE_REJECTION_CLASS_CHECKPOINT_IDENTITY_VERSION
import skillbill.workflow.taskruntime.model.featureTaskRuntimeAppendCheckpointIdentity
import skillbill.workflow.taskruntime.model.featureTaskRuntimeAppendDiagnosticSignal
import skillbill.workflow.taskruntime.model.featureTaskRuntimeAppendImplementationAttempt
import skillbill.workflow.taskruntime.model.featureTaskRuntimeCheckpointIdentitiesFromArtifact
import skillbill.workflow.taskruntime.model.featureTaskRuntimeCheckpointIdentitiesToArtifact
import skillbill.workflow.taskruntime.model.featureTaskRuntimeCheckpointRefName
import skillbill.workflow.taskruntime.model.featureTaskRuntimeDiagnosticSignalsFromWire
import skillbill.workflow.taskruntime.model.featureTaskRuntimeImplementationAttemptRecordToWire
import skillbill.workflow.taskruntime.model.featureTaskRuntimeImplementationAttemptsFromWire
import skillbill.workflow.taskruntime.model.featureTaskRuntimeOwnedPathDigest
import skillbill.workflow.taskruntime.model.featureTaskRuntimeQuarantineEntriesFromWire
import skillbill.workflow.taskruntime.model.featureTaskRuntimeQuarantineRecordToWire
import skillbill.workflow.taskruntime.model.featureTaskRuntimeRejectionCapOf
import skillbill.workflow.taskruntime.model.featureTaskRuntimeRejectionViolationClassOf
import skillbill.workflow.taskruntime.model.unionRefutedBlockerDispositions
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant

/**
 * The degradable classes are the environmental ones: the store rejected, could not be reached, or
 * handed back an unusable row. `InvalidRequest` and `InvalidConfiguration` are deliberately absent —
 * those name a caller-construction defect or a wiring fault, which must keep failing loudly rather
 * than becoming a signal the run walks past.
 */
private fun RejectedOutputDiagnosticError.degradableFailureClass(): FeatureTaskRuntimeDiagnosticFailureClass? =
  when (this) {
    is RejectedOutputDiagnosticError.Conflict -> FeatureTaskRuntimeDiagnosticFailureClass.CONFLICT
    is RejectedOutputDiagnosticError.Permission -> FeatureTaskRuntimeDiagnosticFailureClass.PERMISSION
    is RejectedOutputDiagnosticError.Corrupt -> FeatureTaskRuntimeDiagnosticFailureClass.CORRUPT
    is RejectedOutputDiagnosticError.Persistence,
    is RejectedOutputDiagnosticError.Retrieval,
    is RejectedOutputDiagnosticError.Expired,
    is RejectedOutputDiagnosticError.Oversized,
    is RejectedOutputDiagnosticError.Absent,
    -> FeatureTaskRuntimeDiagnosticFailureClass.PERSISTENCE
    is RejectedOutputDiagnosticError.InvalidRequest,
    is RejectedOutputDiagnosticError.InvalidConfiguration,
    -> null
  }

internal sealed class FeatureTaskRuntimeProducerOutputRead {
  internal data class Found(val evidence: ProducerOutputEvidence) : FeatureTaskRuntimeProducerOutputRead()
  internal data object Absent : FeatureTaskRuntimeProducerOutputRead()
  internal data class Unreadable(
    val failureClass: FeatureTaskRuntimeDiagnosticFailureClass,
  ) : FeatureTaskRuntimeProducerOutputRead()
}

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
  private val diagnostics: RuntimeDiagnostics = NoopRuntimeDiagnostics,
) {
  private sealed class DiagnosticWriteOutcome<out T> {
    class Written<T>(val value: T) : DiagnosticWriteOutcome<T>()
    class Degraded(
      val failureClass: FeatureTaskRuntimeDiagnosticFailureClass,
    ) : DiagnosticWriteOutcome<Nothing>()
  }

  private val engine: WorkflowEngine = WorkflowEngine(workflowSnapshotValidator)
  private val runtimeOwnedPersistence = RuntimeOwnedPersistenceBoundary(database, diagnostics)

  fun recordRejectedOutput(
    request: RejectedOutputDiagnosticRequest,
    dbOverride: String? = null,
    producerGeneration: Int = 0,
  ): FeatureTaskRuntimeRejectedOutputWrite {
    val evidence = ProducerOutputEvidence(
      workflowId = request.workflowId,
      phaseId = request.phaseId,
      attempt = request.attempt,
      agentId = request.agentId,
      model = request.model,
      recordedAt = Instant.now(),
      byteSize = request.observedByteSize,
      sha256 = request.observedSha256,
      payload = request.rawResponse.takeUnless { request.truncated },
      generation = producerGeneration,
      repairTurn = request.repairTurn,
    )
    return when (
      val outcome = degradeDiagnosticFailure(
        workflowId = request.workflowId,
        operation = "record-rejected-output",
        conflictingKey = evidence.evidenceKey(),
        phaseId = request.phaseId,
        attempt = request.attempt,
        repairTurn = request.repairTurn,
        generation = producerGeneration,
        dbOverride = dbOverride,
      ) {
        database.transaction(dbOverride) { unitOfWork ->
          val service = diagnosticService(unitOfWork)
          service.retainProducerOutput(evidence)
          service.record(request)
          recordRejectionMeasurement(unitOfWork, request)
        }
      }
    ) {
      is DiagnosticWriteOutcome.Written<*> -> FeatureTaskRuntimeRejectedOutputWrite.Written(
        RejectedOutputDiagnosticService.stableIdentity(
          request.workflowId,
          request.phaseId,
          request.attempt,
          request.repairTurn,
        ),
      )
      is DiagnosticWriteOutcome.Degraded -> FeatureTaskRuntimeRejectedOutputWrite.Degraded(outcome.failureClass)
    }
  }

  /**
   * Counts the rejection itself. The projection measurement seam records only projections that PASSED,
   * so an attempt rejected by the schema gate — and therefore a fix loop about to exhaust — leaves no
   * trace outside the operator's block reason and the private diagnostic row. This is the one event that
   * makes the failure class countable.
   *
   * Payload-free by construction: the pointer and the classification are derived from the validator's
   * own constraint text, never from the rejected response. Telemetry must never fail the run, so a
   * malformed reason degrades to an unclassified row rather than throwing.
   */
  private fun recordRejectionMeasurement(unitOfWork: UnitOfWork, request: RejectedOutputDiagnosticRequest) {
    try {
      unitOfWork.lifecycleTelemetry.featureTaskRuntimeRejection(
        FeatureTaskRuntimeRejectionMeasurement(
          workflowId = request.workflowId,
          phaseId = request.phaseId,
          iteration = request.attempt.coerceAtLeast(1),
          rule = request.rule,
          pointerPath = request.path.ifBlank { "/" },
          violationClass = featureTaskRuntimeRejectionViolationClassOf(request.reason),
          declaredCap = featureTaskRuntimeRejectionCapOf(request.reason),
        ),
      )
    } catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
      // Telemetry must never fail the run or change the rejection outcome.
    }
  }

  fun retainProducerOutput(evidence: ProducerOutputEvidence, dbOverride: String? = null) {
    degradeDiagnosticFailure(
      workflowId = evidence.workflowId,
      operation = "retain-producer-output",
      conflictingKey = evidence.evidenceKey(),
      phaseId = evidence.phaseId,
      attempt = evidence.attempt,
      repairTurn = evidence.repairTurn,
      generation = evidence.generation,
      dbOverride = dbOverride,
    ) {
      database.transaction(dbOverride) { unitOfWork ->
        diagnosticService(unitOfWork).retainProducerOutput(evidence)
      }
    }
  }

  internal fun producerOutput(
    workflowId: String,
    phaseId: String,
    attempt: Int,
    agentId: String,
    dbOverride: String? = null,
    generation: Int = 0,
  ): FeatureTaskRuntimeProducerOutputRead {
    // A newest-turn read is not scoped to one turn, so the turn slot stays a wildcard rather than
    // claiming turn 0; the rest of the key still correlates with the write-path signals by prefix.
    val conflictingKey = "$workflowId:$phaseId:$generation:$attempt:*:$agentId"
    fun unreadable(failureClass: FeatureTaskRuntimeDiagnosticFailureClass): FeatureTaskRuntimeProducerOutputRead {
      persistDegradedDiagnostic(
        workflowId = workflowId,
        operation = "read-producer-output",
        conflictingKey = conflictingKey,
        phaseId = phaseId,
        attempt = attempt,
        repairTurn = null,
        generation = generation,
        dbOverride = dbOverride,
        failureClass = failureClass,
      )
      return FeatureTaskRuntimeProducerOutputRead.Unreadable(failureClass)
    }
    return try {
      val evidence = database.read(dbOverride) { unitOfWork ->
        val repository = unitOfWork.rejectedOutputDiagnostics
          ?: throw RejectedOutputDiagnosticError.Persistence("repository-unavailable")
        repository.readProducerOutput(workflowId, phaseId, attempt, agentId, generation)
      }
      if (evidence == null) {
        FeatureTaskRuntimeProducerOutputRead.Absent
      } else {
        FeatureTaskRuntimeProducerOutputRead.Found(evidence)
      }
    } catch (error: RejectedOutputDiagnosticError) {
      unreadable(error.degradableFailureClass() ?: throw error)
    } catch (_: InvalidProducerOutputEvidenceSchemaError) {
      unreadable(FeatureTaskRuntimeDiagnosticFailureClass.SCHEMA)
    } catch (_: InvalidRejectedOutputDiagnosticSchemaError) {
      unreadable(FeatureTaskRuntimeDiagnosticFailureClass.SCHEMA)
    }
  }

  /**
   * Runs a diagnostic-evidence write and degrades every typed diagnostic failure — conflict,
   * permission, corruption, schema, plain persistence — into a durable payload-free operator signal.
   *
   * This is the same rule [recordRejectionMeasurement] already applies to telemetry, applied to the
   * evidence store itself. A diagnostic is private evidence *about* a run; a failure to write one is
   * never a reason to kill a run that has otherwise progressed. The degraded signal lands in its own
   * transaction, because the failing write's transaction has already rolled back by the time it runs.
   */
  @Suppress("LongParameterList")
  private fun <T> degradeDiagnosticFailure(
    workflowId: String,
    operation: String,
    conflictingKey: String,
    phaseId: String,
    attempt: Int,
    repairTurn: Int?,
    generation: Int,
    dbOverride: String?,
    block: () -> T,
  ): DiagnosticWriteOutcome<T> {
    fun degrade(failureClass: FeatureTaskRuntimeDiagnosticFailureClass): DiagnosticWriteOutcome<T> {
      persistDegradedDiagnostic(
        workflowId = workflowId,
        operation = operation,
        conflictingKey = conflictingKey,
        phaseId = phaseId,
        attempt = attempt,
        repairTurn = repairTurn,
        generation = generation,
        dbOverride = dbOverride,
        failureClass = failureClass,
      )
      return DiagnosticWriteOutcome.Degraded(failureClass)
    }
    return try {
      DiagnosticWriteOutcome.Written(block())
    } catch (error: RejectedOutputDiagnosticError) {
      degrade(error.degradableFailureClass() ?: throw error)
    } catch (_: InvalidProducerOutputEvidenceSchemaError) {
      degrade(FeatureTaskRuntimeDiagnosticFailureClass.SCHEMA)
    } catch (_: InvalidRejectedOutputDiagnosticSchemaError) {
      degrade(FeatureTaskRuntimeDiagnosticFailureClass.SCHEMA)
    }
  }

  /**
   * Appends the durable signal, then emits the countable measurement in a separate transaction so a
   * throwing telemetry sink cannot roll back the signal and a failed signal append cannot swallow
   * the event.
   */
  @Suppress("LongParameterList")
  private fun persistDegradedDiagnostic(
    workflowId: String,
    operation: String,
    conflictingKey: String,
    phaseId: String,
    attempt: Int,
    repairTurn: Int?,
    generation: Int,
    dbOverride: String?,
    failureClass: FeatureTaskRuntimeDiagnosticFailureClass,
  ) {
    val signal = FeatureTaskRuntimeDiagnosticSignal(
      operation = operation,
      failureClass = failureClass,
      conflictingKey = conflictingKey,
      phaseId = phaseId,
      attempt = attempt.coerceAtLeast(0),
      repairTurn = repairTurn?.coerceAtLeast(0),
      generation = generation.coerceAtLeast(0),
      recordedAt = Instant.now().toString(),
    )
    persistDiagnosticSignal(workflowId, signal, dbOverride)
    recordDegradationMeasurement(workflowId, signal, dbOverride)
  }

  /**
   * Counts the degraded diagnostic-persistence failure. Telemetry must never change the degradation
   * outcome, so a throwing sink is swallowed here — the same rule [recordRejectionMeasurement]
   * already applies. The enqueue lives in its own transaction, never inside the evidence write that
   * already rolled back and never inside [persistDiagnosticSignal]'s best-effort catch.
   */
  private fun recordDegradationMeasurement(
    workflowId: String,
    signal: FeatureTaskRuntimeDiagnosticSignal,
    dbOverride: String?,
  ) {
    try {
      database.transaction(dbOverride) { unitOfWork ->
        unitOfWork.lifecycleTelemetry.featureTaskRuntimeDiagnosticDegradation(
          FeatureTaskRuntimeDiagnosticDegradationMeasurement(
            workflowId = workflowId,
            phaseId = signal.phaseId,
            attempt = signal.attempt,
            repairTurn = signal.repairTurn,
            generation = signal.generation,
            operation = signal.operation,
            failureClass = signal.failureClass,
            conflictingKey = signal.conflictingKey,
          ),
        )
      }
    } catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
      // Telemetry must never fail the run or change the degradation outcome.
    }
  }

  /**
   * Appends the degraded-failure signal to the workflow row. Best-effort by design: if the store that
   * just rejected the evidence also rejects this append, the run still proceeds — the alternative is
   * exactly the crash this seam exists to prevent.
   */
  private fun persistDiagnosticSignal(
    workflowId: String,
    signal: FeatureTaskRuntimeDiagnosticSignal,
    dbOverride: String?,
  ) {
    try {
      database.transaction(dbOverride) { unitOfWork ->
        val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, workflowId)
          ?: return@transaction
        val existing = featureTaskRuntimeDiagnosticSignalsFromWire(
          decodeArtifacts(record.artifactsJson)[FEATURE_TASK_RUNTIME_DIAGNOSTIC_SIGNALS_ARTIFACT_KEY],
        )
        persistPatch(
          unitOfWork.workflowStates,
          record,
          mapOf(
            FEATURE_TASK_RUNTIME_DIAGNOSTIC_SIGNALS_ARTIFACT_KEY to
              featureTaskRuntimeAppendDiagnosticSignal(existing, signal).map { it.toArtifactMap() },
          ),
        )
      }
    } catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
      // The signal is a diagnosability aid, not a run invariant.
    }
  }

  /** Strict read of the durable degraded-diagnostic signals; an absent key yields none. */
  fun loadDiagnosticSignals(workflowId: String, dbOverride: String? = null): List<FeatureTaskRuntimeDiagnosticSignal> =
    database.read(dbOverride) { unitOfWork ->
      val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, workflowId)
        ?: return@read emptyList()
      featureTaskRuntimeDiagnosticSignalsFromWire(
        decodeArtifacts(record.artifactsJson)[FEATURE_TASK_RUNTIME_DIAGNOSTIC_SIGNALS_ARTIFACT_KEY],
      )
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
      ) + implementationAttemptPatch(artifacts, request, attemptStatusFor(request)) +
        findingVerificationCheckpointPatch(artifacts, request)
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
      persistPatch(
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
      // The omitted launch pair is deliberate, not an oversight: this tombstone is not a launch, and
      // REVIEW_INVALIDATION_AGENT_ID never ran a child. Carrying the invalidated generation's model
      // forward would attribute it to an agent that could not have launched it; the relaunch's own
      // running write is what restores it.
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
  ): List<skillbill.goalrunner.model.UnaddressedFinding> = database.transaction(dbOverride) { unitOfWork ->
    unitOfWork.unaddressedFindings.fetchWorkflowLedger(workflowId)
  }

  internal fun appendRejectedVerificationFindings(
    workflowId: String,
    passNumber: Int,
    rejected: List<skillbill.goalrunner.model.UnaddressedFinding>,
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
      persistPatch(
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
      persistPatch(
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
      persistPatch(
        unitOfWork.workflowStates,
        record,
        mapOf(FEATURE_TASK_RUNTIME_FINDING_VERIFICATION_CHECKPOINT_ARTIFACT_KEY to null),
        WorkflowRowAdvance.keepFrom(record),
      )
      true
    }

  internal fun completeGoalReviewPhase(
    completion: GoalReviewPhaseCompletionRequest,
    dbOverride: String? = null,
  ): Boolean {
    val request = validatedGoalReviewPhaseState(completion)
    return database.transaction(dbOverride) { unitOfWork ->
      persistCompletedGoalReview(unitOfWork, request, completion)
    }
  }

  private fun persistCompletedGoalReview(
    unitOfWork: UnitOfWork,
    request: FeatureTaskRuntimePhaseStateRequest,
    completion: GoalReviewPhaseCompletionRequest,
  ): Boolean {
    val write = goalReviewCompletionWrite(unitOfWork, request, completion) ?: return false
    persistUnaddressedFindings(
      unitOfWork,
      request,
      write.continuation,
      write.completedState.completedPassCount,
      write.dispositions,
    )
    persistPatch(
      unitOfWork.workflowStates,
      write.record,
      mapOf(
        GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY to write.completedState.toArtifactMap(),
        GOAL_SUBTASK_REVIEW_RESULTS_ARTIFACT_KEY to (
          write.persisted.rawResults +
            (write.completedState.completedPassCount.toString() to completion.rawReviewResult)
          ),
        FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY to
          write.persisted.updatedRecords.mapValues { (_, value) -> value.toArtifactMap() },
        FEATURE_TASK_RUNTIME_PHASE_LEDGER_ARTIFACT_KEY to
          goalReviewCompletionLedger(request, write.persisted.artifacts),
      ),
      WorkflowRowAdvance(
        currentStepId = request.phaseId,
        workflowStatus = workflowStatusFor(request),
        stepUpdates = stepUpdatesFrom(write.persisted.updatedRecords),
      ),
    )
    return true
  }

  private data class GoalReviewCompletionArtifacts(
    val artifacts: Map<String, Any?>,
    val rawResults: Map<String, String>,
    val updatedRecords: Map<String, FeatureTaskRuntimePhaseRecord>,
  )

  private data class GoalReviewCompletionWrite(
    val record: WorkflowStateSnapshot,
    val continuation: FeatureTaskRuntimeGoalContinuationArtifact,
    val completedState: GoalSubtaskReviewState,
    val dispositions: List<GoalSubtaskBlockerDisposition>,
    val persisted: GoalReviewCompletionArtifacts,
  )

  private fun goalReviewCompletionWrite(
    unitOfWork: UnitOfWork,
    request: FeatureTaskRuntimePhaseStateRequest,
    completion: GoalReviewPhaseCompletionRequest,
  ): GoalReviewCompletionWrite? {
    val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, request.workflowId)
      ?: return null
    val artifacts = decodeArtifacts(record.artifactsJson)
    val reviewArtifacts = GoalSubtaskReviewArtifactDecoder.decode(artifacts) ?: return null
    val reservedPass = reviewArtifacts.state.reservedPassNumber ?: 1
    val envelope = requireNotNull(request.normalizedOutput) {
      "Goal review completion requires normalized output to persist the unaddressed-findings ledger."
    }.envelope
    val recordedVerdicts = GoalSubtaskReviewSummaryReducer.recordedVerdicts(unitOfWork, envelope)
    val currentFindings = GoalSubtaskReviewSummaryReducer.unaddressedFindings(
      output = envelope,
      scope = UnaddressedFindingLedgerScope(
        issueKey = reviewArtifacts.continuation.issueKey,
        subtaskId = reviewArtifacts.continuation.subtaskId,
        workflowId = request.workflowId,
        reviewPassNumber = reservedPass,
      ),
      recordedVerdicts = recordedVerdicts,
    )
    val dispositions = goalReviewCompletionDispositions(
      reservedPass,
      completion.blockerDispositions,
      unitOfWork.unaddressedFindings.fetchWorkflowLedger(request.workflowId),
      currentFindings,
      recordedVerdicts,
    )
    val existingRecords = phaseRecordsFrom(artifacts)
    return GoalReviewCompletionWrite(
      record = record,
      continuation = reviewArtifacts.continuation,
      completedState = reviewArtifacts.state.completeReservedPass(
        verdict = completion.verdict,
        unresolvedFindingCount = completion.unresolvedFindingCount,
        findings = completion.findings,
        blockerDispositions = dispositions,
        commitFocusedAccounting = completion.commitFocusedAccounting,
      ),
      dispositions = dispositions,
      persisted = GoalReviewCompletionArtifacts(
        artifacts = artifacts,
        rawResults = reviewArtifacts.rawResults,
        updatedRecords = LinkedHashMap(existingRecords).apply {
          put(request.phaseId, phaseRecordFor(request, existingRecords[request.phaseId], Instant.now().toString()))
        },
      ),
    )
  }

  private fun goalReviewCompletionDispositions(
    reservedPass: Int,
    requested: List<GoalSubtaskBlockerDisposition>,
    priorFindings: List<skillbill.goalrunner.model.UnaddressedFinding>,
    currentFindings: List<skillbill.goalrunner.model.UnaddressedFinding>,
    recordedVerdicts: List<ReviewFindingVerdict>,
  ): List<GoalSubtaskBlockerDisposition> {
    if (reservedPass <= 1) return requested
    return unionRefutedBlockerDispositions(
      requested,
      GoalSubtaskReviewSummaryReducer.refutedBlockerSupersedes(priorFindings, currentFindings, recordedVerdicts),
    )
  }

  private fun goalReviewCompletionLedger(
    request: FeatureTaskRuntimePhaseStateRequest,
    artifacts: Map<String, Any?>,
  ): List<Map<String, Any?>> {
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
    return appendBoundedHistoryBySequence(
      ledger.map { it.toArtifactMap() },
      completionEntry.toArtifactMap(),
      FEATURE_TASK_RUNTIME_PHASE_LEDGER_LIMIT,
    )
  }

  private fun persistUnaddressedFindings(
    unitOfWork: UnitOfWork,
    request: FeatureTaskRuntimePhaseStateRequest,
    continuation: FeatureTaskRuntimeGoalContinuationArtifact,
    passNumber: Int,
    blockerDispositions: List<GoalSubtaskBlockerDisposition>,
  ) {
    val output = requireNotNull(request.normalizedOutput) {
      "Goal review completion requires normalized output to persist the unaddressed-findings ledger."
    }.envelope
    val recordedVerdicts = GoalSubtaskReviewSummaryReducer.recordedVerdicts(unitOfWork, output)
    val findings = GoalSubtaskReviewSummaryReducer.unaddressedFindings(
      output = output,
      scope = UnaddressedFindingLedgerScope(
        issueKey = continuation.issueKey,
        subtaskId = continuation.subtaskId,
        workflowId = request.workflowId,
        reviewPassNumber = passNumber,
      ),
      recordedVerdicts = recordedVerdicts,
    )
    val superseded = unitOfWork.unaddressedFindings.fetchWorkflowLedger(request.workflowId)
    unitOfWork.unaddressedFindings.replaceLedgerForPass(request.workflowId, passNumber, findings)
    unitOfWork.unaddressedFindings.recordOutcomes(
      GoalSubtaskReviewSummaryReducer.reviewFindingOutcomes(
        supersededFindings = superseded,
        currentFindings = findings,
        blockerDispositions = blockerDispositions,
      ),
    )
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
    sharedEvidenceMeasurement: FeatureTaskRuntimeSharedEvidenceMeasurement? = null,
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
    recordSharedEvidenceMeasurement(unitOfWork, sharedEvidenceMeasurement)
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

  /**
   * Enqueues the shared-evidence derivation/reuse record when a consumer resolved evidence for this
   * briefing. Telemetry failure must never alter evidence resolution or fail the run.
   */
  private fun recordSharedEvidenceMeasurement(
    unitOfWork: UnitOfWork,
    measurement: FeatureTaskRuntimeSharedEvidenceMeasurement?,
  ) {
    if (measurement == null) return
    try {
      unitOfWork.lifecycleTelemetry.featureTaskRuntimeSharedEvidence(measurement)
    } catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
      // Telemetry must never fail the run or change evidence resolution outcomes.
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

  @Suppress("UNCHECKED_CAST")
  fun loadValidationGateProgress(
    workflowId: String,
    dbOverride: String? = null,
  ): FeatureTaskRuntimeValidationGateProgress? = database.read(dbOverride) { unitOfWork ->
    val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, workflowId) ?: return@read null
    val raw = decodeArtifacts(record.artifactsJson)[FEATURE_TASK_RUNTIME_VALIDATION_GATE_PROGRESS_ARTIFACT_KEY]
    val artifact = JsonSupport.anyToStringAnyMap(raw) ?: return@read null
    FeatureTaskRuntimeValidationGateProgress.fromArtifactMap(artifact)
  }

  fun persistValidationGateProgress(
    workflowId: String,
    progress: FeatureTaskRuntimeValidationGateProgress,
    dbOverride: String? = null,
  ) {
    database.transaction(dbOverride) { unitOfWork ->
      val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, workflowId)
        ?: throw InvalidWorkflowStateSchemaError(
          "Cannot persist validation gate progress: workflow '$workflowId' is missing.",
        )
      persistPatch(
        unitOfWork.workflowStates,
        record,
        mapOf(FEATURE_TASK_RUNTIME_VALIDATION_GATE_PROGRESS_ARTIFACT_KEY to progress.toArtifactMap()),
      )
    }
  }

  fun loadAuditGapProgress(workflowId: String, dbOverride: String? = null): FeatureTaskRuntimeAuditGapProgress? =
    database.read(dbOverride) { unitOfWork ->
      val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, workflowId) ?: return@read null
      val raw = decodeArtifacts(record.artifactsJson)[FEATURE_TASK_RUNTIME_AUDIT_GAP_PROGRESS_ARTIFACT_KEY]
      val artifact = JsonSupport.anyToStringAnyMap(raw) ?: return@read null
      FeatureTaskRuntimeAuditGapProgress.fromArtifactMap(artifact)
    }

  fun persistAuditGapProgress(
    workflowId: String,
    progress: FeatureTaskRuntimeAuditGapProgress,
    dbOverride: String? = null,
  ) {
    database.transaction(dbOverride) { unitOfWork ->
      val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, workflowId)
        ?: throw InvalidWorkflowStateSchemaError(
          "Cannot persist audit gap progress: workflow '$workflowId' is missing.",
        )
      persistPatch(
        unitOfWork.workflowStates,
        record,
        mapOf(FEATURE_TASK_RUNTIME_AUDIT_GAP_PROGRESS_ARTIFACT_KEY to progress.toArtifactMap()),
      )
    }
  }

  fun loadAuditGapPause(workflowId: String, dbOverride: String? = null): FeatureTaskRuntimeAuditGapPause? =
    database.read(dbOverride) { unitOfWork ->
      val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, workflowId) ?: return@read null
      val raw = decodeArtifacts(record.artifactsJson)[FEATURE_TASK_RUNTIME_AUDIT_GAP_PAUSE_ARTIFACT_KEY]
      val artifact = JsonSupport.anyToStringAnyMap(raw) ?: return@read null
      FeatureTaskRuntimeAuditGapPause.fromArtifactMap(artifact)
    }

  fun persistAuditGapPause(workflowId: String, pause: FeatureTaskRuntimeAuditGapPause, dbOverride: String? = null) {
    database.transaction(dbOverride) { unitOfWork ->
      val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, workflowId)
        ?: throw InvalidWorkflowStateSchemaError(
          "Cannot persist audit gap pause: workflow '$workflowId' is missing.",
        )
      persistPatch(
        unitOfWork.workflowStates,
        record,
        mapOf(FEATURE_TASK_RUNTIME_AUDIT_GAP_PAUSE_ARTIFACT_KEY to pause.toArtifactMap()),
      )
    }
  }

  fun loadBuildGateProgress(
    workflowId: String,
    dbOverride: String? = null,
  ): FeatureTaskRuntimeValidationGateProgress? = database.read(dbOverride) { unitOfWork ->
    val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, workflowId) ?: return@read null
    val raw = decodeArtifacts(record.artifactsJson)[FEATURE_TASK_RUNTIME_BUILD_GATE_PROGRESS_ARTIFACT_KEY]
    val artifact = JsonSupport.anyToStringAnyMap(raw) ?: return@read null
    FeatureTaskRuntimeValidationGateProgress.fromArtifactMap(artifact)
  }

  fun loadGoalContinuationQualityGateSelection(
    workflowId: String,
    dbOverride: String? = null,
  ): FeatureTaskRuntimeQualityGateSelection? = database.read(dbOverride) { unitOfWork ->
    val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, workflowId) ?: return@read null
    GoalSubtaskReviewArtifactDecoder.decodeContinuationOnly(decodeArtifacts(record.artifactsJson))
      ?.qualityGateSelection
  }

  fun persistBuildGateProgress(
    workflowId: String,
    progress: FeatureTaskRuntimeValidationGateProgress,
    dbOverride: String? = null,
  ) {
    database.transaction(dbOverride) { unitOfWork ->
      val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, workflowId)
        ?: throw InvalidWorkflowStateSchemaError(
          "Cannot persist build gate progress: workflow '$workflowId' is missing.",
        )
      persistPatch(
        unitOfWork.workflowStates,
        record,
        mapOf(FEATURE_TASK_RUNTIME_BUILD_GATE_PROGRESS_ARTIFACT_KEY to progress.toArtifactMap()),
      )
    }
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
   * empty list; a malformed or schema-invalid record loud-fails rather than loading and later being
   * rewritten without unknown evidence. Returns null only when the workflow row is absent.
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
    val map = JsonSupport.anyToStringAnyMap(raw)
      ?: throw InvalidWorkflowStateSchemaError("Feature-task-runtime quarantine record must be an object.")
    // Validate the persisted bytes before decode so an undeclared field cannot load and later be
    // rewritten away by an append. The domain decoder also rejects unknown keys; this is the
    // canonical schema gate the recorder's read seam is documented to call.
    quarantineValidator.validateQuarantineRecord(map, FEATURE_TASK_RUNTIME_QUARANTINED_RECORDS_ARTIFACT_KEY)
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

  /**
   * Appends one checkpoint identity in the same transaction that advanced the checkpoint, so a crash
   * cannot leave a commit whose authority boundary was never recorded. The append is idempotent on
   * the checkpoint ref: a resume that re-reaches this seam converges on the single existing record
   * instead of duplicating it.
   *
   * A store at an incompatible contract version is quarantined and regenerated rather than
   * reinterpreted — the same edge every other feature-task-runtime durable artifact takes. Returns
   * true when the workflow row exists and was updated.
   */
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
    persistPatch(
      unitOfWork.workflowStates,
      record,
      mapOf(
        FEATURE_TASK_RUNTIME_CHECKPOINT_IDENTITIES_ARTIFACT_KEY to
          featureTaskRuntimeCheckpointIdentitiesToArtifact(updated),
      ),
    )
    true
  }

  /**
   * Strict read of the durable checkpoint-identity history. Returns null only when the workflow row
   * is absent; an empty history is a real answer meaning no checkpoint has been committed yet.
   */
  fun loadCheckpointIdentities(
    workflowId: String,
    dbOverride: String? = null,
  ): List<FeatureTaskRuntimeCheckpointIdentity>? = database.read(dbOverride) { unitOfWork ->
    val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, workflowId)
      ?: return@read null
    checkpointIdentitiesFrom(decodeArtifacts(record.artifactsJson))
  }

  /**
   * Drops a checkpoint-identity store this runtime cannot read. A legacy workflow predating the
   * contract, or a record at an incompatible version, regenerates from the next checkpoint forward
   * instead of failing every later read on a store no phase can repair.
   */
  fun quarantineCheckpointIdentities(workflowId: String, dbOverride: String? = null): Boolean =
    database.transaction(dbOverride) { unitOfWork ->
      val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, workflowId)
        ?: return@transaction false
      persistPatch(
        unitOfWork.workflowStates,
        record,
        mapOf(
          FEATURE_TASK_RUNTIME_CHECKPOINT_IDENTITIES_ARTIFACT_KEY to
            featureTaskRuntimeCheckpointIdentitiesToArtifact(emptyList()),
        ),
      )
      true
    }

  /**
   * Recovery edge for the checkpoint-identity contract bump, run before the append transaction so no
   * nested transaction is opened. Scoped to the typed version error alone: a malformed record at the
   * current version propagates, because quarantine is a version-drift repair and must never become a
   * silent swallow for real corruption. The reset and its evidence are separate transactions from the
   * append, so a crash between them leaves a clean current-version store the next append can extend.
   */
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
        // There is no rejected-payload diagnostic capture for a version bump; the contract's xor
        // makes this the only way to record the honest absence of one.
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

  private fun phaseRecordFor(
    request: FeatureTaskRuntimePhaseStateRequest,
    previous: FeatureTaskRuntimePhaseRecord?,
    now: String,
  ): FeatureTaskRuntimePhaseRecord {
    val firstStartedAt = previous?.firstStartedAt ?: now
    val startedAt = if (request.status == STATUS_RUNNING || previous == null) now else previous.startedAt
    // The launch pair moves as a unit: a write that knows the launch outcome replaces both fields,
    // any other write carries both forward. Resolving them independently would let a Cursor-merged
    // model (effort folded into the model string, so effort null) land over a prior record's effort
    // and produce the self-contradictory pair LaunchedModelDirective exists to prevent.
    //
    // Carry-forward is bounded to one attempt by one agent. A write that advances attempt_count or
    // swaps resolved_agent_id settles work the prior pair never described — a pre-launch cap block,
    // an audit settled from durable criterion closure, a branch-setup block under a non-agent id —
    // so inheriting it would assert a model that attempt provably never launched.
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

  private companion object {
    const val STATUS_RUNNING = "running"
  }
}

internal data class GoalReviewPhaseCompletionRequest(
  val phaseState: FeatureTaskRuntimePhaseStateRequest,
  val verdict: FeatureTaskRuntimeVerdict,
  val unresolvedFindingCount: Int,
  val findings: List<GoalSubtaskReviewCompactFinding>,
  val rawReviewResult: String,
  val blockerDispositions: List<GoalSubtaskBlockerDisposition> = emptyList(),
  /** Present only when the pass ran delegated over a real commit sequence. */
  val commitFocusedAccounting: GoalSubtaskCommitFocusedAccounting? = null,
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
internal fun stepUpdatesFrom(records: Map<String, FeatureTaskRuntimePhaseRecord>): List<Map<String, Any?>> {
  fun stepStatusFor(record: FeatureTaskRuntimePhaseRecord): String = when {
    record.status == FEATURE_TASK_RUNTIME_PHASE_STATUS_BLOCKED -> FEATURE_TASK_RUNTIME_PHASE_STATUS_BLOCKED
    // A paused record is resumable, not finished: it keeps its paused step status even though the
    // pause is recorded with a finished timestamp.
    record.status == FEATURE_TASK_RUNTIME_PHASE_STATUS_PAUSED -> FEATURE_TASK_RUNTIME_PHASE_STATUS_PAUSED
    // A reopened record (asPendingForOperatorResume) is unstarted work, not finished work. It is
    // checked ahead of the finished-timestamp branch so a reopen can never read as completed, and
    // it must map at all: without it every operator reopen makes the next steps[] projection throw
    // and the child exits before it can redo the phase.
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

private fun sha256Hex(value: String): String = MessageDigest.getInstance("SHA-256")
  .digest(value.toByteArray())
  .joinToString("") { "%02x".format(it) }
