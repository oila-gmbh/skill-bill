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
  fun existingWorkflowMode(workflowId: String): FeatureTaskWorkflowMode?
  fun workerOwnership(workflowId: String): FeatureTaskRuntimeWorkerOwnership?
  fun ensureWorkflowOpen(workflowId: String, sessionId: String, issueKey: String? = null): Boolean
}

interface FeatureTaskRuntimePhaseRejectedApi {
  fun recordRejectedOutput(
    request: RejectedOutputDiagnosticRequest,
    producerGeneration: Int = 0,
  ): FeatureTaskRuntimeRejectedOutputWrite
  fun retainProducerOutput(evidence: ProducerOutputEvidence)
  fun producerOutput(args: ProducerOutputQueryArgs): FeatureTaskRuntimeProducerOutputRead
  fun loadDiagnosticSignals(workflowId: String): List<FeatureTaskRuntimeDiagnosticSignal>
}

interface FeatureTaskRuntimePhaseStateApi {
  fun recordPhaseState(request: FeatureTaskRuntimePhaseStateRequest): Boolean
  fun recordCompletedPhase(request: FeatureTaskRuntimePhaseStateRequest): Boolean
  fun recordIncompleteImplementationAttempt(request: FeatureTaskRuntimePhaseStateRequest): Boolean
  fun loadImplementationAttempts(workflowId: String): List<FeatureTaskRuntimeImplementationAttempt>?
  fun clearBackwardEdgeContext(workflowId: String, phaseIds: Collection<String>): Boolean
  fun loadPhaseRecords(workflowId: String): Map<String, FeatureTaskRuntimePhaseRecord>?
  fun loadOperatorBlockRetry(workflowId: String): FeatureTaskRuntimeOperatorBlockRetry?
  fun loadPhaseLedger(workflowId: String): List<FeatureTaskRuntimePhaseLedgerEntry>?
}

interface FeatureTaskRuntimePhaseReviewApi {
  fun completeGoalReviewPhase(completion: GoalReviewPhaseCompletionRequest): Boolean
}

interface FeatureTaskRuntimePhaseReviewGenerationApi {
  fun persistReviewGenerationInvalidation(workflowId: String): Int?
  fun reconcileReviewGeneration(workflowId: String): Int
  fun invalidateQuarantinedProducerRecord(
    workflowId: String,
    producerPhaseId: String,
    loopId: String,
    edgeIteration: Int,
  ): Boolean
  fun recordedFindingVerdicts(output: Map<String, Any?>): List<ReviewFindingVerdict>
  fun fetchUnaddressedLedger(workflowId: String): List<UnaddressedFinding>
  fun appendRejectedVerificationFindings(workflowId: String, passNumber: Int, rejected: List<UnaddressedFinding>)
}

interface FeatureTaskRuntimePhaseFindingVerificationApi {
  fun loadFindingVerificationCheckpoint(workflowId: String): List<FeatureTaskRuntimeFindingVerificationDisposition>?
  fun loadFindingVerificationBoundarySelection(
    workflowId: String,
  ): Map<String, List<FeatureTaskRuntimeVerificationBoundaryHeadingProvenance>>?
  fun persistFindingVerificationBoundarySelection(
    workflowId: String,
    selections: Map<String, List<FeatureTaskRuntimeVerificationBoundaryHeadingProvenance>>,
  ): Boolean
  fun loadFindingVerificationDispositions(workflowId: String): List<FeatureTaskRuntimeFindingVerificationDisposition>?
  fun persistFindingVerificationCheckpoint(
    workflowId: String,
    dispositions: List<FeatureTaskRuntimeFindingVerificationDisposition>,
  ): Boolean
  fun clearFindingVerificationCheckpoint(workflowId: String): Boolean
}

interface FeatureTaskRuntimePhaseReviewCheckpointApi :
  FeatureTaskRuntimePhaseReviewGenerationApi,
  FeatureTaskRuntimePhaseFindingVerificationApi

interface FeatureTaskRuntimePhaseBriefingApi {
  fun recordPhaseBriefing(
    workflowId: String,
    briefing: FeatureTaskRuntimePhaseLaunchBriefing,
    sharedEvidenceMeasurement: FeatureTaskRuntimeSharedEvidenceMeasurement? = null,
  ): Boolean
  fun recordProjectionRejection(
    workflowId: String,
    consumerPhaseId: String,
    error: InvalidFeatureTaskRuntimeHandoffProjectionError,
    repositoryCheckpointFingerprint: String?,
  ): Boolean
  fun recordProjectionRejection(rejection: FeatureTaskRuntimeProjectionRejection): Boolean
  fun validateHandoffDeclarations(declarations: List<PhaseHandoffProjectionDeclaration>)
  fun loadPhaseBriefings(workflowId: String): Map<String, FeatureTaskRuntimePhaseLaunchBriefing>?
  fun loadDeliveredProjections(workflowId: String): Map<String, FeatureTaskRuntimeDeliveredProjectionRecord>?
}

interface FeatureTaskRuntimePhaseGateApi {
  fun loadValidationGateProgress(workflowId: String): FeatureTaskRuntimeValidationGateProgress?
  fun persistValidationGateProgress(workflowId: String, progress: FeatureTaskRuntimeValidationGateProgress)
  fun loadAuditGapProgress(workflowId: String): FeatureTaskRuntimeAuditGapProgress?
  fun persistAuditGapProgress(workflowId: String, progress: FeatureTaskRuntimeAuditGapProgress)
  fun loadAuditGapPause(workflowId: String): FeatureTaskRuntimeAuditGapPause?
  fun persistAuditGapPause(workflowId: String, pause: FeatureTaskRuntimeAuditGapPause)
  fun loadBuildGateProgress(workflowId: String): FeatureTaskRuntimeValidationGateProgress?
  fun loadGoalContinuationQualityGateSelection(workflowId: String): FeatureTaskRuntimeQualityGateSelection?
  fun persistBuildGateProgress(workflowId: String, progress: FeatureTaskRuntimeValidationGateProgress)
}

interface FeatureTaskRuntimePhaseEvidenceApi {
  fun appendLedgerEntry(request: FeatureTaskRuntimePhaseLedgerRequest): Boolean
  fun appendQuarantineEntry(workflowId: String, entry: FeatureTaskRuntimeQuarantineEntry): Boolean
  fun loadQuarantinedRecords(workflowId: String): List<FeatureTaskRuntimeQuarantineEntry>?
  fun recordResolvedBranch(workflowId: String, resolvedBranch: FeatureTaskRuntimeResolvedBranch): Boolean
  fun loadResolvedBranch(workflowId: String): FeatureTaskRuntimeResolvedBranch?
  fun appendCheckpointIdentity(args: AppendCheckpointIdentityArgs): Boolean
  fun loadCheckpointIdentities(workflowId: String): List<FeatureTaskRuntimeCheckpointIdentity>?
  fun quarantineCheckpointIdentities(workflowId: String): Boolean
  fun recordWorkflowOwnedPaths(workflowId: String, ownedPaths: List<String>): Boolean}
