package skillbill.application.featuretask
import skillbill.application.diagnostics.model.FeatureTaskRuntimeRejectedOutputWrite
import skillbill.application.diagnostics.model.RejectedOutputDiagnosticRequest
import skillbill.application.featuretask.model.AppendCheckpointIdentityArgs
import skillbill.application.featuretask.model.FeatureTaskRuntimePhaseLaunchBriefing
import skillbill.application.featuretask.model.FeatureTaskRuntimePhaseLedgerRequest
import skillbill.application.featuretask.model.FeatureTaskRuntimePhaseStateRequest
import skillbill.application.featuretask.model.FeatureTaskRuntimeProducerOutputRead
import skillbill.application.featuretask.model.FeatureTaskRuntimeProjectionRejection
import skillbill.application.featuretask.model.GoalReviewPhaseCompletionRequest
import skillbill.application.featuretask.model.ProducerOutputQueryArgs
import skillbill.error.InvalidFeatureTaskRuntimeHandoffProjectionError
import skillbill.goalrunner.model.UnaddressedFinding
import skillbill.ports.diagnostics.model.ProducerOutputEvidence
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerOwnership
import skillbill.ports.workflow.model.FeatureTaskWorkflowMode
import skillbill.review.model.ReviewFindingVerdict
import skillbill.workflow.decomposition.model.IssueKey
import skillbill.workflow.engine.model.SessionId
import skillbill.workflow.engine.model.WorkflowId
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

interface FeatureTaskRuntimePhaseWorkflowApi {
  fun existingWorkflowMode(workflowId: WorkflowId): FeatureTaskWorkflowMode?
  fun workerOwnership(workflowId: WorkflowId): FeatureTaskRuntimeWorkerOwnership?
  fun ensureWorkflowOpen(workflowId: WorkflowId, sessionId: SessionId, issueKey: IssueKey? = null): Boolean
}

interface FeatureTaskRuntimePhaseRejectedApi {
  fun recordRejectedOutput(
    request: RejectedOutputDiagnosticRequest,
    producerGeneration: Int = 0,
  ): FeatureTaskRuntimeRejectedOutputWrite
  fun retainProducerOutput(evidence: ProducerOutputEvidence)
  fun producerOutput(args: ProducerOutputQueryArgs): FeatureTaskRuntimeProducerOutputRead
  fun loadDiagnosticSignals(workflowId: WorkflowId): List<FeatureTaskRuntimeDiagnosticSignal>
}

interface FeatureTaskRuntimePhaseStateApi {
  fun recordPhaseState(request: FeatureTaskRuntimePhaseStateRequest): Boolean
  fun recordCompletedPhase(request: FeatureTaskRuntimePhaseStateRequest): Boolean
  fun recordIncompleteImplementationAttempt(request: FeatureTaskRuntimePhaseStateRequest): Boolean
  fun loadImplementationAttempts(workflowId: WorkflowId): List<FeatureTaskRuntimeImplementationAttempt>?
  fun clearBackwardEdgeContext(workflowId: WorkflowId, phaseIds: Collection<String>): Boolean
  fun loadPhaseRecords(workflowId: WorkflowId): Map<String, FeatureTaskRuntimePhaseRecord>?
  fun loadOperatorBlockRetry(workflowId: WorkflowId): FeatureTaskRuntimeOperatorBlockRetry?
  fun loadPhaseLedger(workflowId: WorkflowId): List<FeatureTaskRuntimePhaseLedgerEntry>?
}

interface FeatureTaskRuntimePhaseReviewApi {
  fun completeGoalReviewPhase(completion: GoalReviewPhaseCompletionRequest): Boolean
}

interface FeatureTaskRuntimePhaseReviewGenerationApi {
  fun persistReviewGenerationInvalidation(workflowId: WorkflowId): Int?
  fun reconcileReviewGeneration(workflowId: WorkflowId): Int
  fun invalidateQuarantinedProducerRecord(
    workflowId: WorkflowId,
    producerPhaseId: String,
    loopId: String,
    edgeIteration: Int,
  ): Boolean
  fun recordedFindingVerdicts(output: Map<String, Any?>): List<ReviewFindingVerdict>
  fun fetchUnaddressedLedger(workflowId: WorkflowId): List<UnaddressedFinding>
  fun appendRejectedVerificationFindings(workflowId: WorkflowId, passNumber: Int, rejected: List<UnaddressedFinding>)
}

interface FeatureTaskRuntimePhaseFindingVerificationApi {
  fun loadFindingVerificationCheckpoint(workflowId: WorkflowId): List<FeatureTaskRuntimeFindingVerificationDisposition>?
  fun loadFindingVerificationBoundarySelection(
    workflowId: WorkflowId,
  ): Map<String, List<FeatureTaskRuntimeVerificationBoundaryHeadingProvenance>>?
  fun persistFindingVerificationBoundarySelection(
    workflowId: WorkflowId,
    selections: Map<String, List<FeatureTaskRuntimeVerificationBoundaryHeadingProvenance>>,
  ): Boolean
  fun loadFindingVerificationDispositions(
    workflowId: WorkflowId,
  ): List<FeatureTaskRuntimeFindingVerificationDisposition>?
  fun persistFindingVerificationCheckpoint(
    workflowId: WorkflowId,
    dispositions: List<FeatureTaskRuntimeFindingVerificationDisposition>,
  ): Boolean
  fun clearFindingVerificationCheckpoint(workflowId: WorkflowId): Boolean
}

interface FeatureTaskRuntimePhaseReviewCheckpointApi :
  FeatureTaskRuntimePhaseReviewGenerationApi,
  FeatureTaskRuntimePhaseFindingVerificationApi

interface FeatureTaskRuntimePhaseBriefingApi {
  fun recordPhaseBriefing(
    workflowId: WorkflowId,
    briefing: FeatureTaskRuntimePhaseLaunchBriefing,
    sharedEvidenceMeasurement: FeatureTaskRuntimeSharedEvidenceMeasurement? = null,
  ): Boolean
  fun recordProjectionRejection(
    workflowId: WorkflowId,
    consumerPhaseId: String,
    error: InvalidFeatureTaskRuntimeHandoffProjectionError,
    repositoryCheckpointFingerprint: String?,
  ): Boolean
  fun recordProjectionRejection(rejection: FeatureTaskRuntimeProjectionRejection): Boolean
  fun validateHandoffDeclarations(declarations: List<PhaseHandoffProjectionDeclaration>)
  fun loadPhaseBriefings(workflowId: WorkflowId): Map<String, FeatureTaskRuntimePhaseLaunchBriefing>?
  fun loadDeliveredProjections(workflowId: WorkflowId): Map<String, FeatureTaskRuntimeDeliveredProjectionRecord>?
}

interface FeatureTaskRuntimePhaseGateApi {
  fun loadValidationGateProgress(workflowId: WorkflowId): FeatureTaskRuntimeValidationGateProgress?
  fun persistValidationGateProgress(workflowId: WorkflowId, progress: FeatureTaskRuntimeValidationGateProgress)
  fun loadAuditGapProgress(workflowId: WorkflowId): FeatureTaskRuntimeAuditGapProgress?
  fun persistAuditGapProgress(workflowId: WorkflowId, progress: FeatureTaskRuntimeAuditGapProgress)
  fun loadAuditGapPause(workflowId: WorkflowId): FeatureTaskRuntimeAuditGapPause?
  fun persistAuditGapPause(workflowId: WorkflowId, pause: FeatureTaskRuntimeAuditGapPause)
  fun loadBuildGateProgress(workflowId: WorkflowId): FeatureTaskRuntimeValidationGateProgress?
  fun loadGoalContinuationQualityGateSelection(workflowId: WorkflowId): FeatureTaskRuntimeQualityGateSelection?
  fun persistBuildGateProgress(workflowId: WorkflowId, progress: FeatureTaskRuntimeValidationGateProgress)
}

interface FeatureTaskRuntimePhaseEvidenceApi {
  fun appendLedgerEntry(request: FeatureTaskRuntimePhaseLedgerRequest): Boolean
  fun appendQuarantineEntry(workflowId: WorkflowId, entry: FeatureTaskRuntimeQuarantineEntry): Boolean
  fun loadQuarantinedRecords(workflowId: WorkflowId): List<FeatureTaskRuntimeQuarantineEntry>?
  fun recordResolvedBranch(workflowId: WorkflowId, resolvedBranch: FeatureTaskRuntimeResolvedBranch): Boolean
  fun loadResolvedBranch(workflowId: WorkflowId): FeatureTaskRuntimeResolvedBranch?
  fun appendCheckpointIdentity(args: AppendCheckpointIdentityArgs): Boolean
  fun loadCheckpointIdentities(workflowId: WorkflowId): List<FeatureTaskRuntimeCheckpointIdentity>?
  fun quarantineCheckpointIdentities(workflowId: WorkflowId): Boolean
  fun recordWorkflowOwnedPaths(workflowId: WorkflowId, ownedPaths: List<String>): Boolean
}
