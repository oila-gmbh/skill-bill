package skillbill.application.featuretask

import me.tatarka.inject.annotations.Inject
import skillbill.application.diagnostics.model.RejectedOutputDiagnosticRequest
import skillbill.application.diagnostics.model.FeatureTaskRuntimeRejectedOutputWrite
import skillbill.application.featuretask.model.FeatureTaskRuntimePhaseLaunchBriefing
import skillbill.application.featuretask.model.FeatureTaskRuntimePhaseLedgerRequest
import skillbill.application.featuretask.model.FeatureTaskRuntimePhaseStateRequest
import skillbill.goalrunner.model.UnaddressedFinding
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.diagnostics.ProducerOutputEvidenceValidator
import skillbill.ports.diagnostics.RejectedOutputDiagnosticMetadataValidator
import skillbill.ports.diagnostics.model.ProducerOutputEvidence
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerOwnership
import skillbill.ports.workflow.model.FeatureTaskWorkflowMode
import skillbill.review.model.ReviewFindingVerdict
import skillbill.workflow.engine.WorkflowSnapshotValidator
import skillbill.workflow.taskruntime.FeatureTaskRuntimeHandoffEnvelopeValidator
import skillbill.workflow.taskruntime.FeatureTaskRuntimeHandoffFoundationValidator
import skillbill.workflow.taskruntime.FeatureTaskRuntimeImplementationAttemptValidator
import skillbill.workflow.taskruntime.FeatureTaskRuntimeQuarantineValidator
import skillbill.workflow.taskruntime.NoopFeatureTaskRuntimeImplementationAttemptValidator
import skillbill.workflow.taskruntime.NoopFeatureTaskRuntimeQuarantineValidator
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditGapPause
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditGapProgress
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeCheckpointIdentity
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeDeliveredProjectionRecord
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeDiagnosticSignal
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFindingVerificationDisposition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeImplementationAttempt
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeOperatorBlockRetry
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerEntry
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeQualityGateSelection
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeQuarantineEntry
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeResolvedBranch
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeSharedEvidenceMeasurement
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeValidationGateProgress
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerificationBoundaryHeadingProvenance
import skillbill.workflow.taskruntime.model.PhaseHandoffProjectionDeclaration
import skillbill.error.InvalidFeatureTaskRuntimeHandoffProjectionError

@Inject
class FeatureTaskRuntimePhaseRecorder(
  database: DatabaseSessionFactory,
  workflowSnapshotValidator: WorkflowSnapshotValidator,
  handoffEnvelopeValidator: FeatureTaskRuntimeHandoffEnvelopeValidator,
  handoffFoundationValidator: FeatureTaskRuntimeHandoffFoundationValidator,
  quarantineValidator: FeatureTaskRuntimeQuarantineValidator = NoopFeatureTaskRuntimeQuarantineValidator,
  implementationAttemptValidator: FeatureTaskRuntimeImplementationAttemptValidator =
    NoopFeatureTaskRuntimeImplementationAttemptValidator,
  rejectedOutputDiagnosticMetadataValidator: RejectedOutputDiagnosticMetadataValidator = { },
  producerOutputEvidenceValidator: ProducerOutputEvidenceValidator = { },
  diagnostics: RuntimeDiagnostics = NoopRuntimeDiagnostics,
) {
  private val workflowPersistence = FeatureTaskRuntimeWorkflowPersistence(database, workflowSnapshotValidator)
  private val runtimeOwnedPersistence = RuntimeOwnedPersistenceBoundary(database, diagnostics)
  private val rejectedOutput = FeatureTaskRuntimeRejectedOutputRecorder(
    database,
    workflowPersistence,
    rejectedOutputDiagnosticMetadataValidator,
    producerOutputEvidenceValidator,
  )
  private val phaseState = FeatureTaskRuntimePhaseStateRecorder(
    database,
    workflowPersistence,
    runtimeOwnedPersistence,
    implementationAttemptValidator,
  )
  private val reviewCheckpoint = FeatureTaskRuntimeReviewCheckpointRecorder(
    database,
    workflowPersistence,
    runtimeOwnedPersistence,
  )
  private val goalReviewCompletion = FeatureTaskRuntimeGoalReviewCompletionRecorder(database, workflowPersistence)
  private val briefingRecorder = FeatureTaskRuntimePhaseBriefingRecorder(
    database,
    workflowPersistence,
    handoffEnvelopeValidator,
    handoffFoundationValidator,
  )
  private val gateProgress = FeatureTaskRuntimeGateProgressRecorder(database, workflowPersistence)
  private val evidence = FeatureTaskRuntimePhaseEvidenceRecorder(database, workflowPersistence, quarantineValidator)

  fun recordRejectedOutput(
    request: RejectedOutputDiagnosticRequest,
    dbOverride: String? = null,
    producerGeneration: Int = 0,
  ): FeatureTaskRuntimeRejectedOutputWrite = rejectedOutput.recordRejectedOutput(request, dbOverride, producerGeneration)

  fun retainProducerOutput(evidence: ProducerOutputEvidence, dbOverride: String? = null) =
    rejectedOutput.retainProducerOutput(evidence, dbOverride)

  internal fun producerOutput(
    workflowId: String,
    phaseId: String,
    attempt: Int,
    agentId: String,
    dbOverride: String? = null,
    generation: Int = 0,
  ): FeatureTaskRuntimeProducerOutputRead =
    rejectedOutput.producerOutput(workflowId, phaseId, attempt, agentId, dbOverride, generation)

  fun loadDiagnosticSignals(workflowId: String, dbOverride: String? = null): List<FeatureTaskRuntimeDiagnosticSignal> =
    rejectedOutput.loadDiagnosticSignals(workflowId, dbOverride)

  fun recordPhaseState(request: FeatureTaskRuntimePhaseStateRequest, dbOverride: String? = null): Boolean =
    phaseState.recordPhaseState(request, dbOverride)

  fun recordCompletedPhase(request: FeatureTaskRuntimePhaseStateRequest, dbOverride: String? = null): Boolean =
    phaseState.recordCompletedPhase(request, dbOverride)

  fun recordIncompleteImplementationAttempt(
    request: FeatureTaskRuntimePhaseStateRequest,
    dbOverride: String? = null,
  ): Boolean = phaseState.recordIncompleteImplementationAttempt(request, dbOverride)

  fun loadImplementationAttempts(
    workflowId: String,
    dbOverride: String? = null,
  ): List<FeatureTaskRuntimeImplementationAttempt>? = phaseState.loadImplementationAttempts(workflowId, dbOverride)

  internal fun persistReviewGenerationInvalidation(workflowId: String, dbOverride: String? = null): Int? =
    reviewCheckpoint.persistReviewGenerationInvalidation(workflowId, dbOverride)

  internal fun reconcileReviewGeneration(workflowId: String, dbOverride: String? = null): Int =
    reviewCheckpoint.reconcileReviewGeneration(workflowId, dbOverride)

  internal fun invalidateQuarantinedProducerRecord(
    workflowId: String,
    producerPhaseId: String,
    loopId: String,
    edgeIteration: Int,
    dbOverride: String? = null,
  ): Boolean = reviewCheckpoint.invalidateQuarantinedProducerRecord(
    workflowId,
    producerPhaseId,
    loopId,
    edgeIteration,
    dbOverride,
  )

  internal fun recordedFindingVerdicts(
    output: Map<String, Any?>,
    dbOverride: String? = null,
  ): List<ReviewFindingVerdict> = reviewCheckpoint.recordedFindingVerdicts(output, dbOverride)

  internal fun fetchUnaddressedLedger(workflowId: String, dbOverride: String? = null): List<UnaddressedFinding> =
    reviewCheckpoint.fetchUnaddressedLedger(workflowId, dbOverride)

  internal fun appendRejectedVerificationFindings(
    workflowId: String,
    passNumber: Int,
    rejected: List<UnaddressedFinding>,
    dbOverride: String? = null,
  ) = reviewCheckpoint.appendRejectedVerificationFindings(workflowId, passNumber, rejected, dbOverride)

  internal fun loadFindingVerificationCheckpoint(
    workflowId: String,
    dbOverride: String? = null,
  ): List<FeatureTaskRuntimeFindingVerificationDisposition>? =
    reviewCheckpoint.loadFindingVerificationCheckpoint(workflowId, dbOverride)

  internal fun loadFindingVerificationBoundarySelection(
    workflowId: String,
    dbOverride: String? = null,
  ): Map<String, List<FeatureTaskRuntimeVerificationBoundaryHeadingProvenance>>? =
    reviewCheckpoint.loadFindingVerificationBoundarySelection(workflowId, dbOverride)

  internal fun persistFindingVerificationBoundarySelection(
    workflowId: String,
    selections: Map<String, List<FeatureTaskRuntimeVerificationBoundaryHeadingProvenance>>,
    dbOverride: String? = null,
  ): Boolean = reviewCheckpoint.persistFindingVerificationBoundarySelection(workflowId, selections, dbOverride)

  internal fun loadFindingVerificationDispositions(
    workflowId: String,
    dbOverride: String? = null,
  ): List<FeatureTaskRuntimeFindingVerificationDisposition>? =
    reviewCheckpoint.loadFindingVerificationDispositions(workflowId, dbOverride)

  internal fun persistFindingVerificationCheckpoint(
    workflowId: String,
    dispositions: List<FeatureTaskRuntimeFindingVerificationDisposition>,
    dbOverride: String? = null,
  ): Boolean = reviewCheckpoint.persistFindingVerificationCheckpoint(workflowId, dispositions, dbOverride)

  internal fun clearFindingVerificationCheckpoint(workflowId: String, dbOverride: String? = null): Boolean =
    reviewCheckpoint.clearFindingVerificationCheckpoint(workflowId, dbOverride)

  internal fun completeGoalReviewPhase(
    completion: GoalReviewPhaseCompletionRequest,
    dbOverride: String? = null,
  ): Boolean = goalReviewCompletion.completeGoalReviewPhase(completion, dbOverride)

  fun clearBackwardEdgeContext(workflowId: String, phaseIds: Collection<String>, dbOverride: String? = null): Boolean =
    phaseState.clearBackwardEdgeContext(workflowId, phaseIds, dbOverride)

  fun recordPhaseBriefing(
    workflowId: String,
    briefing: FeatureTaskRuntimePhaseLaunchBriefing,
    dbOverride: String? = null,
    sharedEvidenceMeasurement: FeatureTaskRuntimeSharedEvidenceMeasurement? = null,
  ): Boolean = briefingRecorder.recordPhaseBriefing(workflowId, briefing, dbOverride, sharedEvidenceMeasurement)

  fun recordProjectionRejection(
    workflowId: String,
    consumerPhaseId: String,
    error: InvalidFeatureTaskRuntimeHandoffProjectionError,
    repositoryCheckpointFingerprint: String?,
    dbOverride: String? = null,
  ): Boolean = briefingRecorder.recordProjectionRejection(
    workflowId,
    consumerPhaseId,
    error,
    repositoryCheckpointFingerprint,
    dbOverride,
  )

  internal fun recordProjectionRejection(
    rejection: FeatureTaskRuntimeProjectionRejection,
    dbOverride: String? = null,
  ): Boolean = briefingRecorder.recordProjectionRejection(rejection, dbOverride)

  fun validateHandoffDeclarations(declarations: List<PhaseHandoffProjectionDeclaration>) =
    briefingRecorder.validateHandoffDeclarations(declarations)

  fun loadPhaseBriefings(
    workflowId: String,
    dbOverride: String? = null,
  ): Map<String, FeatureTaskRuntimePhaseLaunchBriefing>? = briefingRecorder.loadPhaseBriefings(workflowId, dbOverride)

  fun loadDeliveredProjections(
    workflowId: String,
    dbOverride: String? = null,
  ): Map<String, FeatureTaskRuntimeDeliveredProjectionRecord>? =
    briefingRecorder.loadDeliveredProjections(workflowId, dbOverride)

  fun loadValidationGateProgress(
    workflowId: String,
    dbOverride: String? = null,
  ): FeatureTaskRuntimeValidationGateProgress? = gateProgress.loadValidationGateProgress(workflowId, dbOverride)

  fun persistValidationGateProgress(
    workflowId: String,
    progress: FeatureTaskRuntimeValidationGateProgress,
    dbOverride: String? = null,
  ) = gateProgress.persistValidationGateProgress(workflowId, progress, dbOverride)

  fun loadAuditGapProgress(workflowId: String, dbOverride: String? = null): FeatureTaskRuntimeAuditGapProgress? =
    gateProgress.loadAuditGapProgress(workflowId, dbOverride)

  fun persistAuditGapProgress(
    workflowId: String,
    progress: FeatureTaskRuntimeAuditGapProgress,
    dbOverride: String? = null,
  ) = gateProgress.persistAuditGapProgress(workflowId, progress, dbOverride)

  fun loadAuditGapPause(workflowId: String, dbOverride: String? = null): FeatureTaskRuntimeAuditGapPause? =
    gateProgress.loadAuditGapPause(workflowId, dbOverride)

  fun persistAuditGapPause(workflowId: String, pause: FeatureTaskRuntimeAuditGapPause, dbOverride: String? = null) =
    gateProgress.persistAuditGapPause(workflowId, pause, dbOverride)

  fun loadBuildGateProgress(
    workflowId: String,
    dbOverride: String? = null,
  ): FeatureTaskRuntimeValidationGateProgress? = gateProgress.loadBuildGateProgress(workflowId, dbOverride)

  fun loadGoalContinuationQualityGateSelection(
    workflowId: String,
    dbOverride: String? = null,
  ): FeatureTaskRuntimeQualityGateSelection? =
    gateProgress.loadGoalContinuationQualityGateSelection(workflowId, dbOverride)

  fun persistBuildGateProgress(
    workflowId: String,
    progress: FeatureTaskRuntimeValidationGateProgress,
    dbOverride: String? = null,
  ) = gateProgress.persistBuildGateProgress(workflowId, progress, dbOverride)

  fun appendLedgerEntry(request: FeatureTaskRuntimePhaseLedgerRequest, dbOverride: String? = null): Boolean =
    evidence.appendLedgerEntry(request, dbOverride)

  fun appendQuarantineEntry(
    workflowId: String,
    entry: FeatureTaskRuntimeQuarantineEntry,
    dbOverride: String? = null,
  ): Boolean = evidence.appendQuarantineEntry(workflowId, entry, dbOverride)

  fun loadQuarantinedRecords(
    workflowId: String,
    dbOverride: String? = null,
  ): List<FeatureTaskRuntimeQuarantineEntry>? = evidence.loadQuarantinedRecords(workflowId, dbOverride)

  fun recordResolvedBranch(
    workflowId: String,
    resolvedBranch: FeatureTaskRuntimeResolvedBranch,
    dbOverride: String? = null,
  ): Boolean = evidence.recordResolvedBranch(workflowId, resolvedBranch, dbOverride)

  fun loadResolvedBranch(workflowId: String, dbOverride: String? = null): FeatureTaskRuntimeResolvedBranch? =
    evidence.loadResolvedBranch(workflowId, dbOverride)

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
  ): Boolean = evidence.appendCheckpointIdentity(
    workflowId,
    issueKey,
    subtaskId,
    branch,
    phaseId,
    loopId,
    generation,
    parentSha,
    ownedPaths,
    commitSha,
    dbOverride,
  )

  fun loadCheckpointIdentities(
    workflowId: String,
    dbOverride: String? = null,
  ): List<FeatureTaskRuntimeCheckpointIdentity>? = evidence.loadCheckpointIdentities(workflowId, dbOverride)

  fun quarantineCheckpointIdentities(workflowId: String, dbOverride: String? = null): Boolean =
    evidence.quarantineCheckpointIdentities(workflowId, dbOverride)

  fun recordWorkflowOwnedPaths(workflowId: String, ownedPaths: List<String>, dbOverride: String? = null): Boolean =
    evidence.recordWorkflowOwnedPaths(workflowId, ownedPaths, dbOverride)

  fun loadPhaseRecords(workflowId: String, dbOverride: String? = null): Map<String, FeatureTaskRuntimePhaseRecord>? =
    phaseState.loadPhaseRecords(workflowId, dbOverride)

  fun loadOperatorBlockRetry(workflowId: String, dbOverride: String? = null): FeatureTaskRuntimeOperatorBlockRetry? =
    phaseState.loadOperatorBlockRetry(workflowId, dbOverride)

  fun loadPhaseLedger(workflowId: String, dbOverride: String? = null): List<FeatureTaskRuntimePhaseLedgerEntry>? =
    phaseState.loadPhaseLedger(workflowId, dbOverride)

  fun existingWorkflowMode(workflowId: String, dbOverride: String? = null): FeatureTaskWorkflowMode? =
    workflowPersistence.existingWorkflowMode(workflowId, dbOverride)

  fun workerOwnership(workflowId: String, dbOverride: String? = null): FeatureTaskRuntimeWorkerOwnership? =
    workflowPersistence.workerOwnership(workflowId, dbOverride)

  fun ensureWorkflowOpen(
    workflowId: String,
    sessionId: String,
    dbOverride: String? = null,
    issueKey: String? = null,
  ): Boolean = workflowPersistence.ensureWorkflowOpen(workflowId, sessionId, dbOverride, issueKey)
}
