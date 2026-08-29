package skillbill.application.featuretask

import skillbill.application.diagnostics.model.FeatureTaskRuntimeRejectedOutputWrite
import skillbill.application.diagnostics.model.RejectedOutputDiagnosticRequest
import skillbill.application.featuretask.model.FeatureTaskRuntimePhaseLaunchBriefing
import skillbill.application.featuretask.model.FeatureTaskRuntimePhaseLedgerRequest
import skillbill.application.featuretask.model.FeatureTaskRuntimePhaseStateRequest
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

internal interface FeatureTaskRuntimePhaseWorkflowApi {
  fun existingWorkflowMode(workflowId: String, dbOverride: String? = null): FeatureTaskWorkflowMode?
  fun workerOwnership(workflowId: String, dbOverride: String? = null): FeatureTaskRuntimeWorkerOwnership?
  fun ensureWorkflowOpen(
    workflowId: String,
    sessionId: String,
    dbOverride: String? = null,
    issueKey: String? = null,
  ): Boolean
}

internal interface FeatureTaskRuntimePhaseRejectedApi {
  fun recordRejectedOutput(
    request: RejectedOutputDiagnosticRequest,
    dbOverride: String? = null,
    producerGeneration: Int = 0,
  ): FeatureTaskRuntimeRejectedOutputWrite
  fun retainProducerOutput(evidence: ProducerOutputEvidence, dbOverride: String? = null)
  fun producerOutput(args: ProducerOutputQueryArgs): FeatureTaskRuntimeProducerOutputRead
  fun loadDiagnosticSignals(workflowId: String, dbOverride: String? = null): List<FeatureTaskRuntimeDiagnosticSignal>
}

internal interface FeatureTaskRuntimePhaseStateApi {
  fun recordPhaseState(request: FeatureTaskRuntimePhaseStateRequest, dbOverride: String? = null): Boolean
  fun recordCompletedPhase(request: FeatureTaskRuntimePhaseStateRequest, dbOverride: String? = null): Boolean
  fun recordIncompleteImplementationAttempt(
    request: FeatureTaskRuntimePhaseStateRequest,
    dbOverride: String? = null,
  ): Boolean
  fun loadImplementationAttempts(
    workflowId: String,
    dbOverride: String? = null,
  ): List<FeatureTaskRuntimeImplementationAttempt>?
  fun clearBackwardEdgeContext(workflowId: String, phaseIds: Collection<String>, dbOverride: String? = null): Boolean
  fun loadPhaseRecords(workflowId: String, dbOverride: String? = null): Map<String, FeatureTaskRuntimePhaseRecord>?
  fun loadOperatorBlockRetry(workflowId: String, dbOverride: String? = null): FeatureTaskRuntimeOperatorBlockRetry?
  fun loadPhaseLedger(workflowId: String, dbOverride: String? = null): List<FeatureTaskRuntimePhaseLedgerEntry>?
}

internal interface FeatureTaskRuntimePhaseReviewApi {
  fun completeGoalReviewPhase(completion: GoalReviewPhaseCompletionRequest, dbOverride: String? = null): Boolean
}

internal interface FeatureTaskRuntimePhaseReviewGenerationApi {
  fun persistReviewGenerationInvalidation(workflowId: String, dbOverride: String? = null): Int?
  fun reconcileReviewGeneration(workflowId: String, dbOverride: String? = null): Int
  fun invalidateQuarantinedProducerRecord(
    workflowId: String,
    producerPhaseId: String,
    loopId: String,
    edgeIteration: Int,
    dbOverride: String? = null,
  ): Boolean
  fun recordedFindingVerdicts(output: Map<String, Any?>, dbOverride: String? = null): List<ReviewFindingVerdict>
  fun fetchUnaddressedLedger(workflowId: String, dbOverride: String? = null): List<UnaddressedFinding>
  fun appendRejectedVerificationFindings(
    workflowId: String,
    passNumber: Int,
    rejected: List<UnaddressedFinding>,
    dbOverride: String? = null,
  )
}

internal interface FeatureTaskRuntimePhaseFindingVerificationApi {
  fun loadFindingVerificationCheckpoint(
    workflowId: String,
    dbOverride: String? = null,
  ): List<FeatureTaskRuntimeFindingVerificationDisposition>?
  fun loadFindingVerificationBoundarySelection(
    workflowId: String,
    dbOverride: String? = null,
  ): Map<String, List<FeatureTaskRuntimeVerificationBoundaryHeadingProvenance>>?
  fun persistFindingVerificationBoundarySelection(
    workflowId: String,
    selections: Map<String, List<FeatureTaskRuntimeVerificationBoundaryHeadingProvenance>>,
    dbOverride: String? = null,
  ): Boolean
  fun loadFindingVerificationDispositions(
    workflowId: String,
    dbOverride: String? = null,
  ): List<FeatureTaskRuntimeFindingVerificationDisposition>?
  fun persistFindingVerificationCheckpoint(
    workflowId: String,
    dispositions: List<FeatureTaskRuntimeFindingVerificationDisposition>,
    dbOverride: String? = null,
  ): Boolean
  fun clearFindingVerificationCheckpoint(workflowId: String, dbOverride: String? = null): Boolean
}

internal interface FeatureTaskRuntimePhaseReviewCheckpointApi :
  FeatureTaskRuntimePhaseReviewGenerationApi,
  FeatureTaskRuntimePhaseFindingVerificationApi

internal interface FeatureTaskRuntimePhaseBriefingApi {
  fun recordPhaseBriefing(
    workflowId: String,
    briefing: FeatureTaskRuntimePhaseLaunchBriefing,
    dbOverride: String? = null,
    sharedEvidenceMeasurement: FeatureTaskRuntimeSharedEvidenceMeasurement? = null,
  ): Boolean
  fun recordProjectionRejection(
    workflowId: String,
    consumerPhaseId: String,
    error: InvalidFeatureTaskRuntimeHandoffProjectionError,
    repositoryCheckpointFingerprint: String?,
    dbOverride: String? = null,
  ): Boolean
  fun recordProjectionRejection(rejection: FeatureTaskRuntimeProjectionRejection, dbOverride: String? = null): Boolean
  fun validateHandoffDeclarations(declarations: List<PhaseHandoffProjectionDeclaration>)
  fun loadPhaseBriefings(
    workflowId: String,
    dbOverride: String? = null,
  ): Map<String, FeatureTaskRuntimePhaseLaunchBriefing>?
  fun loadDeliveredProjections(
    workflowId: String,
    dbOverride: String? = null,
  ): Map<String, FeatureTaskRuntimeDeliveredProjectionRecord>?
}

internal interface FeatureTaskRuntimePhaseGateApi {
  fun loadValidationGateProgress(
    workflowId: String,
    dbOverride: String? = null,
  ): FeatureTaskRuntimeValidationGateProgress?
  fun persistValidationGateProgress(
    workflowId: String,
    progress: FeatureTaskRuntimeValidationGateProgress,
    dbOverride: String? = null,
  )
  fun loadAuditGapProgress(workflowId: String, dbOverride: String? = null): FeatureTaskRuntimeAuditGapProgress?
  fun persistAuditGapProgress(
    workflowId: String,
    progress: FeatureTaskRuntimeAuditGapProgress,
    dbOverride: String? = null,
  )
  fun loadAuditGapPause(workflowId: String, dbOverride: String? = null): FeatureTaskRuntimeAuditGapPause?
  fun persistAuditGapPause(workflowId: String, pause: FeatureTaskRuntimeAuditGapPause, dbOverride: String? = null)
  fun loadBuildGateProgress(workflowId: String, dbOverride: String? = null): FeatureTaskRuntimeValidationGateProgress?
  fun loadGoalContinuationQualityGateSelection(
    workflowId: String,
    dbOverride: String? = null,
  ): FeatureTaskRuntimeQualityGateSelection?
  fun persistBuildGateProgress(
    workflowId: String,
    progress: FeatureTaskRuntimeValidationGateProgress,
    dbOverride: String? = null,
  )
}

internal interface FeatureTaskRuntimePhaseEvidenceApi {
  fun appendLedgerEntry(request: FeatureTaskRuntimePhaseLedgerRequest, dbOverride: String? = null): Boolean
  fun appendQuarantineEntry(
    workflowId: String,
    entry: FeatureTaskRuntimeQuarantineEntry,
    dbOverride: String? = null,
  ): Boolean
  fun loadQuarantinedRecords(workflowId: String, dbOverride: String? = null): List<FeatureTaskRuntimeQuarantineEntry>?
  fun recordResolvedBranch(
    workflowId: String,
    resolvedBranch: FeatureTaskRuntimeResolvedBranch,
    dbOverride: String? = null,
  ): Boolean
  fun loadResolvedBranch(workflowId: String, dbOverride: String? = null): FeatureTaskRuntimeResolvedBranch?
  fun appendCheckpointIdentity(args: AppendCheckpointIdentityArgs): Boolean
  fun loadCheckpointIdentities(
    workflowId: String,
    dbOverride: String? = null,
  ): List<FeatureTaskRuntimeCheckpointIdentity>?
  fun quarantineCheckpointIdentities(workflowId: String, dbOverride: String? = null): Boolean
  fun recordWorkflowOwnedPaths(workflowId: String, ownedPaths: List<String>, dbOverride: String? = null): Boolean
}
