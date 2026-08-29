package skillbill.application.featuretask

import skillbill.application.evidence.FeatureTaskRuntimeSharedReviewEvidenceResolved
import skillbill.application.evidence.FeatureTaskRuntimeSharedReviewEvidenceResolver
import skillbill.application.diagnostics.RejectedOutputDiagnosticService
import skillbill.application.diagnostics.model.RejectedOutputDiagnosticRequest
import skillbill.application.featuretask.model.FeatureTaskRuntimeCheckpointDecision
import skillbill.application.featuretask.model.FeatureTaskRuntimeCheckpointScopeInput
import skillbill.application.featuretask.model.FeatureTaskRuntimeFindingBoundaryMemoryRequest
import skillbill.application.featuretask.model.FeatureTaskRuntimeFindingBoundaryMemorySection
import skillbill.application.diagnostics.model.FeatureTaskRuntimeRejectedOutputWrite
import skillbill.application.featuretask.validation.FeatureTaskRuntimeBuildGateCoordinator
import skillbill.application.featuretask.validation.model.ValidationFindingSetProjection
import skillbill.application.featuretask.validation.model.ValidationGateAgentRepairLauncher
import skillbill.application.featuretask.validation.model.ValidationGateAgentRepairResult
import skillbill.application.featuretask.validation.model.ValidationGateAgentTriageLauncher
import skillbill.application.featuretask.validation.model.ValidationGateCycleRequest
import skillbill.application.featuretask.validation.model.ValidationGateCycleResult
import skillbill.application.featuretask.validation.model.ValidationGateCycleTerminalOutcome
import skillbill.application.featuretask.validation.model.ValidationGateResolution
import skillbill.application.featuretask.validation.model.ValidationGateTriageResult
import skillbill.application.goalrunner.GoalSubtaskReviewSummaryReducer
import skillbill.application.goalrunner.UnaddressedFindingLedgerScope
import skillbill.application.featuretask.model.FeatureTaskRuntimeImplementationContinuation
import skillbill.application.featuretask.model.FeatureTaskRuntimePhaseLaunchBriefing
import skillbill.application.featuretask.model.FeatureTaskRuntimePhaseStateRequest
import skillbill.application.featuretask.model.FeatureTaskRuntimePlanningStopDecision
import skillbill.application.featuretask.model.FeatureTaskRuntimeResolvedPhaseAgent
import skillbill.application.featuretask.model.FeatureTaskRuntimeRunReport
import skillbill.application.featuretask.model.FeatureTaskRuntimeRunRequest
import skillbill.application.review.model.ParallelCodeReviewRequest
import skillbill.application.review.model.ParallelCodeReviewResult
import skillbill.application.review.toProjectionPayload
import skillbill.application.workflow.repoRoot
import skillbill.config.model.PhaseCompactionDirective
import skillbill.config.model.PhaseModelDirective
import skillbill.contracts.JsonSupport
import skillbill.contracts.workflow.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import skillbill.error.FeatureTaskRuntimeHandoffProjectionFailureKind
import skillbill.error.FeatureTaskRuntimePhaseOrderViolationError
import skillbill.error.FeatureTaskRuntimePhaseOutputFailureKind
import skillbill.error.InvalidFeatureTaskRuntimeHandoffProjectionError
import skillbill.error.InvalidFeatureTaskRuntimePhaseBriefingFramingError
import skillbill.error.InvalidFeatureTaskRuntimePhaseOutputSchemaError
import skillbill.error.InvalidFeatureTaskRuntimePlanningProjectionSchemaError
import skillbill.error.InvalidWorkflowStateSchemaError
import skillbill.goalrunner.model.UNADDRESSED_FINDING_REJECTED_DISPOSITION
import skillbill.install.model.InstallAgent
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.agentrun.model.AgentRunLaunchOutcome
import skillbill.ports.agentrun.model.SkillRunRequest
import skillbill.ports.agentrun.model.UnsupportedAgentRunLaunch
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.goalrunner.runner.GoalRunnerSubtaskLauncher
import skillbill.ports.goalrunner.runner.model.GoalRunnerSubtaskLaunchRequest
import skillbill.ports.diagnostics.model.ProducerOutputEvidence
import skillbill.ports.workflow.gitops.buildGoalSubtaskReviewInput
import skillbill.ports.workflow.gitops.captureIndexState
import skillbill.ports.workflow.gitops.headCommitMessage
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewInput
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.ports.workflow.gitops.pathContentIdentities
import skillbill.ports.workflow.gitops.repositoryCheckpointFingerprint
import skillbill.ports.workflow.gitops.repositoryFingerprint
import skillbill.ports.workflow.gitops.repositoryOwnedPaths
import skillbill.ports.workflow.gitops.restoreIndexState
import skillbill.ports.workflow.gitops.runtimePhaseChangedPathsBetweenCommits
import skillbill.ports.workflow.gitops.runtimePhaseHeadCommit
import skillbill.ports.workflow.gitops.stagePaths
import skillbill.ports.workflow.gitops.stagedPaths
import skillbill.review.context.model.ReviewContextBudgetPolicy
import skillbill.review.context.model.SpecIntentProjectionResolveRequest
import skillbill.review.context.model.SpecIntentResolution
import skillbill.review.model.ReviewFindingVerdict
import skillbill.telemetry.estimation.estimateTokens
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseOutputValidator
import skillbill.workflow.decomposition.model.SpecSource
import skillbill.workflow.goal.model.ValidationDepth
import skillbill.workflow.taskruntime.FeatureTaskRuntimeHandoffContract
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.FeatureTaskRuntimeQualityGateRouting
import skillbill.workflow.taskruntime.FeatureTaskRuntimeTransitionFunction
import skillbill.workflow.taskruntime.model.AUDIT_GAP_PAUSE_DECISION_ABANDON_SUBTASK
import skillbill.workflow.taskruntime.model.AUDIT_GAP_PAUSE_DECISION_RETRY_FIX
import skillbill.workflow.taskruntime.model.AUDIT_GAP_PAUSE_KIND_NO_PROGRESS
import skillbill.workflow.taskruntime.model.AUDIT_GAP_PAUSE_KIND_WARN_THRESHOLD
import skillbill.workflow.taskruntime.model.AcceptedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.CorrectiveRepairCapturedResponse
import skillbill.workflow.taskruntime.model.CorrectiveRepairDiagnosticLocator
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_STANDALONE_SUBTASK_ID
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditGapPause
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditGapProgress
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditRepairProgressDecision
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeBackwardEdge
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeCapExhaustionBehavior
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeCheckpointIdentity
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeCorrectiveRepairContext
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFailureDisposition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffSourceRef
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeNextPhase
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeOperatorBlockRetry
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseDeclaration
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePriorGapMemory
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeProducerIteration
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeProjectionFailureClassification
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeQualityGateSelection
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeQuarantineEntry
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairReceipt
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepositoryCheckpoint
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepositoryCheckpointPolicy
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeResolvedBranch
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeReviewFinding
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeReviewPassSequence
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeTransitionContext
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeTransitionDeclaration
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict
import skillbill.workflow.goal.model.GOAL_SUBTASK_REVIEW_BLOCKER_SEVERITY
import skillbill.workflow.goal.model.GoalSubtaskBlockerDisposition
import skillbill.workflow.goal.model.GoalSubtaskOperatorDecision
import skillbill.workflow.goal.model.GoalSubtaskReviewState
import skillbill.workflow.taskruntime.model.NormalizedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.PhaseHandoffProjectionDeclaration
import skillbill.workflow.taskruntime.model.QUARANTINE_REJECTION_CLASS_PLANNING_PROJECTION
import skillbill.workflow.taskruntime.model.ReviewPassResolution
import skillbill.workflow.taskruntime.model.acceptanceCriterionRefsFor
import skillbill.workflow.taskruntime.model.boundPriorGapNotes
import skillbill.workflow.taskruntime.model.detectAuditRepairNonProgress
import skillbill.workflow.taskruntime.model.requireAcceptedOutput
import skillbill.workflow.taskruntime.model.upsertRepairReceipt
import skillbill.workflow.taskruntime.model.validateDispositionCoverage
import java.nio.file.Path
import kotlin.time.Duration.Companion.minutes
import skillbill.workflow.goal.model.CodeReviewExecutionMode
import skillbill.application.review.model.DiffResolutionException
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputFormat
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputRepairEvidence
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputRepairOperation
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputSourceLocation
import java.time.Instant
import skillbill.error.InvalidReviewContextSchemaError
import skillbill.review.context.model.ReviewContextBudgetExceededException
import skillbill.application.review.RuntimeOwnedReviewMode
import skillbill.application.review.model.StackDetectionException
import skillbill.application.goalrunner.StructuredGoalReviewFinding
import skillbill.workflow.taskruntime.model.UNPROVEN_REPOSITORY_FINGERPRINT
import skillbill.error.UnreadableSpecIntentProjectionError
import skillbill.application.review.model.UsageValidationException
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeImplementationAttemptStatus


internal fun FeatureTaskRuntimeRunLoop.persistImplementFixRepairReceipt(receipt: FeatureTaskRuntimeRepairReceipt): String? = runCatching {
    goalContinuationRecorder.updateReviewState(request.workflowId, request.dbPathOverride) { state ->
      state.upsertRepairReceipt(receipt)
    }
  }.fold(
    onSuccess = { recorded ->
      if (recorded != null) null else "the review state could not be updated with the repair receipt."
    },
    onFailure = { error ->
      recordRepairReceiptWriteFailure(error)
      "the review state could not be updated with the repair receipt."
    },
  )

internal fun FeatureTaskRuntimeRunLoop.recordRepairReceiptWriteFailure(error: Throwable) {
    diagnostics.warning(
      "Feature-task-runtime could not persist the implement_fix repair receipt for issue " +
        "${request.issueKey}, workflow ${request.workflowId}.",
      error,
    )
  }

internal fun FeatureTaskRuntimeRunLoop.settleAndPersistImplementFixRepairReceipt(
    run: PhaseRun,
    outputMap: Map<String, Any?>,
    reject: (String, String) -> AttemptResult,
    iteration: Int,
    observability: FeatureTaskRuntimeRunObservability,
    fileManifest: FeatureTaskRuntimePhaseFileManifest,
  ): AttemptResult? {
    val settlement = implementFixRepairReceiptSettlement(run, outputMap)
    settlement.rejectionDetail?.let { detail -> return reject("repair-receipt", detail) }
    val writeFailure = settlement.writeFailureReason ?: return null
    return AttemptResult.settled(
      blockAndPersistInPhase(
        run,
        iteration,
        writeFailure,
        observability,
        failureDisposition = FeatureTaskRuntimeFailureDisposition.PROCESS_FAILURE,
        fileManifest = fileManifest,
      ),
    )
  }

internal fun FeatureTaskRuntimeRunLoop.implementFixRepairReceiptSettlement(
    run: PhaseRun,
    outputMap: Map<String, Any?>,
  ): RepairReceiptSettlement {
    val produced = completedImplementFixProducedOutputs(run, outputMap) ?: return RepairReceiptSettlement.None
    val reviewState = goalReviewStateOrNull() ?: return repairReceiptShapeSettlement(produced)
    val anchor = repairReceiptAnchor(reviewState) ?: return repairReceiptShapeSettlement(produced)
    return when (
      val parsed = featureTaskRuntimeParseRepairReceipt(
        produced,
        anchor.baseSha,
        anchor.roundNumber,
        recordTruncation = { record -> runCatching { diagnostics.warning(record) } },
      )
    ) {
      FeatureTaskRuntimeRepairReceiptMissing -> RepairReceiptSettlement.None
      is FeatureTaskRuntimeRepairReceiptRejected -> RepairReceiptSettlement.rejected(parsed.rejectionDetail)
      is FeatureTaskRuntimeRepairReceiptValid -> settledRepairReceipt(parsed.receipt, reviewState)
    }
  }

internal fun FeatureTaskRuntimeRunLoop.settledRepairReceipt(
    receipt: FeatureTaskRuntimeRepairReceipt,
    reviewState: GoalSubtaskReviewState,
  ): RepairReceiptSettlement = featureTaskRuntimeRepairReceiptSettleRejection(
    receipt,
    reviewState,
    refutedCarriedFindingIds(reviewState),
  )
    ?.let { detail -> RepairReceiptSettlement.rejected(detail) }
    ?: persistImplementFixRepairReceipt(receipt)?.let { reason -> RepairReceiptSettlement.writeFailed(reason) }
    ?: RepairReceiptSettlement.None

  /**
   * The refs verification refuted in the pass this round is repairing. Read from the durable ledger
   * rather than the review output so the runtime's own recorded verdict decides, and scoped to that
   * one pass because every pass renumbers from `F-001`: an unscoped read would let a refutation from
   * an earlier pass waive whichever finding inherited its ordinal.
   *
   * A ledger that cannot be read waives nothing. Coverage then behaves exactly as it did before this
   * set existed, which is the safe direction: the round is sent back rather than advanced on a guess.
   */
internal fun FeatureTaskRuntimeRunLoop.refutedCarriedFindingIds(reviewState: GoalSubtaskReviewState): Set<String> {
    val passNumber = reviewState.passResults.lastOrNull()?.passNumber ?: return emptySet()
    return runCatching {
      recorder.fetchUnaddressedLedger(request.workflowId, request.dbPathOverride)
        .asSequence()
        .filter { finding -> finding.reviewPassNumber == passNumber }
        .filter { finding -> finding.verificationDisposition == UNADDRESSED_FINDING_REJECTED_DISPOSITION }
        .mapNotNull { finding -> finding.findingId?.takeIf(String::isNotBlank) }
        .toSet()
    }.getOrElse { error ->
      diagnostics.warning(
        "Feature-task-runtime could not read the unaddressed-findings ledger for issue " +
          "${request.issueKey}, workflow ${request.workflowId}; repair-receipt coverage waives no " +
          "refuted finding for this round.",
        error,
      )
      emptySet()
    }
  }

internal fun FeatureTaskRuntimeRunLoop.repairReceiptShapeSettlement(produced: Map<String, Any?>): RepairReceiptSettlement =
    featureTaskRuntimeRepairReceiptShapeRejection(produced)
      ?.let { detail -> RepairReceiptSettlement.rejected(detail) }
      ?: RepairReceiptSettlement.None

internal fun FeatureTaskRuntimeRunLoop.repairReceiptAnchor(reviewState: GoalSubtaskReviewState): RepairReceiptAnchor? {
    val baseSha = reviewState.remediationBaseSha
    val roundNumber = featureTaskRuntimeRemediationRoundNumberOrNull(reviewState)
    if (baseSha == null || roundNumber == null) {
      recordRepairReceiptDegradation(
        if (baseSha == null) {
          "no durable remediation base sha was recorded for this round"
        } else {
          "the durable remediation round number is not yet established"
        },
      )
      return null
    }
    return RepairReceiptAnchor(baseSha = baseSha, roundNumber = roundNumber)
  }

internal fun FeatureTaskRuntimeRunLoop.recordRepairReceiptDegradation(reason: String) {
    runCatching {
      diagnostics.warning(
        "Feature-task-runtime did not record the implement_fix repair receipt for issue " +
          "${request.issueKey}, workflow ${request.workflowId}: $reason. The remediation repair " +
          "ledger loses this round.",
      )
    }
  }

internal fun FeatureTaskRuntimeRunLoop.settleCompletedImplementationOutput(
    run: PhaseRun,
    outputMap: Map<String, Any?>,
    reject: (String, String) -> AttemptResult,
    iteration: Int,
    observability: FeatureTaskRuntimeRunObservability,
    fileManifest: FeatureTaskRuntimePhaseFileManifest,
  ): AttemptResult? = settleAndPersistImplementFixRepairReceipt(
    run,
    outputMap,
    reject,
    iteration,
    observability,
    fileManifest,
  )

internal fun FeatureTaskRuntimeRunLoop.blockRemediationBaseSha(precedingPhaseId: String, error: String): Boolean {
    blockAt(
      precedingPhaseId,
      "Feature-task-runtime could not record the pre-fix remediation base sha before re-entering " +
        "implement_fix" + (if (error.isBlank()) "." else " ($error).") +
        " Without it the reserved remediation pass would silently review the full base-to-current " +
        "delta instead of the remediation delta.",
    )
    return false
  }

  /**
   * Stages exactly [ownedPaths] and commits them. The pre-checkpoint index is snapshotted first, so a
   * staging or commit failure restores the index to what it was rather than leaving a partial
   * mutation that would silently ride along in the user's next commit. The working tree is never
   * touched on any path through here.
   */
internal fun FeatureTaskRuntimeRunLoop.commitCheckpoint(
    precedingPhaseId: String,
    branch: String,
    loopId: String?,
    intent: String,
    ownedPaths: List<String>,
    blockedReason: (String, String) -> String,
  ): Boolean {
    val snapshot = phaseGates.gitOperations.captureIndexState(request.repoRoot, ownedPaths)
    if (!snapshot.ok) {
      return blockCheckpoint(precedingPhaseId, branch, snapshot.error, blockedReason)
    }
    val parentSha = phaseGates.gitOperations.headCommitSha(request.repoRoot)
      .takeIf { it.ok }?.value?.trim()?.takeIf(String::isNotBlank)
    val staged = phaseGates.gitOperations.stagePaths(request.repoRoot, ownedPaths)
    if (!staged.ok) {
      return blockCheckpoint(
        precedingPhaseId,
        branch,
        withIndexRestoreOutcome(staged.error, ownedPaths, snapshot.value.orEmpty()),
        blockedReason,
      )
    }
    val subtaskIdentity = subtaskCommitIdentity()
    val message = checkpointCommitMessage(
      branch = branch,
      phaseId = precedingPhaseId,
      loopId = loopId,
      identity = subtaskIdentity,
      intent = intent,
    )
    val commit = writeSubtaskCommit(branch, message, subtaskIdentity)
    if (!commit.ok) {
      return blockCheckpoint(
        precedingPhaseId,
        branch,
        withIndexRestoreOutcome(commit.error, ownedPaths, snapshot.value.orEmpty()),
        blockedReason,
      )
    }
    return recordCheckpointIdentity(
      precedingPhaseId = precedingPhaseId,
      branch = branch,
      loopId = loopId,
      ownedPaths = ownedPaths,
      parentSha = parentSha,
      commitSha = commit.value.orEmpty().trim(),
      blockedReason = blockedReason,
    )
  }

  /**
   * The subtask every checkpoint of this run belongs to. A standalone feature-task run owns no
   * decomposed subtask; the reserved literal keeps one commit and one ref namespace per run anyway.
   */
