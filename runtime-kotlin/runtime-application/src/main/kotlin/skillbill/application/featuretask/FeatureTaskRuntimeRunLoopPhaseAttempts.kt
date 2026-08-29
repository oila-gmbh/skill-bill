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


internal fun FeatureTaskRuntimeRunLoop.settleIncompleteWork(context: FixLoopBranchContext): PhaseOutcome? {
    val (run, attempt, loop, observability, agentId) = context
    loop.continuationSegmentCount += 1
    if (!recordIncompleteAttempt(run, loop.iteration, attempt)) {
      return blockAndPersistInPhase(
        run,
        loop.iteration,
        "Feature-task-runtime phase '${run.phaseId}' could not durably append its incomplete " +
          "implementation attempt (segment ${loop.continuationSegmentCount}). Continuing would lose the " +
          "continuation projection, so the run stops here rather than retrying against state that was " +
          "never persisted.",
        observability,
        failureDisposition = FeatureTaskRuntimeFailureDisposition.PROCESS_FAILURE,
        fileManifest = attempt.fileManifest,
      )
    }
    loop.iteration += 1
    // This attempt was schema-VALID and merely incomplete, so any correction carried from an
    // earlier malformed attempt is now stale. Leaving it set would hand the next segment both the
    // continuation directive and a schema-rejection directive naming a reason from two attempts
    // ago, telling the agent its valid output was rejected by the schema gate.
    loop.priorCorrection = null
    observability.continuation(
      run.phaseId,
      agentId,
      loop.iteration,
      loop.continuationSegmentCount,
      FeatureTaskRuntimeContinuationKind.IMPLEMENTATION_CONTINUATION,
    )
    return null
  }

  /**
   * Continues verify_findings after a schema-valid heading-selection pass.
   *
   * The output-gate budget is for agent schema/repair failures (including audit-repair receipts), not
   * for this internal handshake. Charging it here blocked the required body-delivery turn under
   * cap=1. Resolved bodies ride the durable selection into the next briefing; no schema-correction
   * directive is appropriate.
   */
internal fun FeatureTaskRuntimeRunLoop.settleBoundaryBodyDelivery(context: FixLoopBranchContext): PhaseOutcome? {
    val (run, _, loop, observability, agentId) = context
    loop.continuationSegmentCount += 1
    loop.iteration += 1
    loop.priorCorrection = null
    observability.continuation(
      run.phaseId,
      agentId,
      loop.iteration,
      loop.continuationSegmentCount,
      FeatureTaskRuntimeContinuationKind.VERIFICATION_BODY_DELIVERY,
    )
    return null
  }

  /**
   * Sends the round back for the findings it still owes, or blocks when the owed set stopped moving.
   *
   * Both budgets are counted in finding references rather than attempts, which is what keeps a round
   * from being blocked while it still has real repair work left. An omitted finding must be accounted
   * for on the next attempt; a finding reported unresolved gets one more fix attempt and then belongs
   * to an operator.
   */
internal fun FeatureTaskRuntimeRunLoop.settleFindingsOwed(context: FixLoopBranchContext): PhaseOutcome? {
    val (run, attempt, loop, observability, agentId) = context
    val refs = requireNotNull(attempt.findingsOwedRefs)
    val blockReason = when (requireNotNull(attempt.findingsOwedKind)) {
      FindingsOwedKind.OMITTED -> FeatureTaskRuntimeAttemptBudgets.findingCoverageBlockReason(
        run.phaseId,
        refs,
        loop.priorUnaccountedFindings,
      )
      FindingsOwedKind.UNRESOLVED -> FeatureTaskRuntimeAttemptBudgets.unresolvedFindingBlockReason(
        run.phaseId,
        refs,
        loop.priorUnresolvedFindings,
        requireNotNull(attempt.findingsOwedDetail),
      )
    }
    blockReason?.let { reason ->
      return blockAndPersistInPhase(
        run,
        loop.iteration,
        reason,
        observability,
        failureDisposition = FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION,
        fileManifest = attempt.fileManifest,
      )
    }
    when (attempt.findingsOwedKind) {
      FindingsOwedKind.OMITTED -> loop.priorUnaccountedFindings = refs
      FindingsOwedKind.UNRESOLVED -> loop.priorUnresolvedFindings = loop.priorUnresolvedFindings + refs
      null -> Unit
    }
    loop.itemCoverageSegmentCount += 1
    loop.iteration += 1
    loop.priorCorrection = PriorAttemptCorrection.unaccountedFindings(
      requireNotNull(attempt.findingsOwedRetryReason),
    )
    observability.continuation(
      run.phaseId,
      agentId,
      loop.iteration,
      loop.itemCoverageSegmentCount,
      FeatureTaskRuntimeContinuationKind.ITEM_COVERAGE,
    )
    return null
  }

internal fun FeatureTaskRuntimeRunLoop.settleMalformedOutput(context: FixLoopBranchContext): PhaseOutcome? {
    val (run, attempt, loop, observability, agentId) = context
    loop.outputGateFailures += 1
    loop.malformedAttemptCount += 1
    val formatBlock = FeatureTaskRuntimeAttemptBudgets.outputGateBlockReason(
      run.phaseId,
      loop.outputGateFailures,
    )
    if (formatBlock == null) {
      loop.iteration += 1
      loop.priorCorrection = PriorAttemptCorrection.schemaGate(
        requireNotNull(attempt.schemaInvalidRetryReason),
        correctiveRepairContext = attempt.correctiveRepairContext,
      )
      observability.fixLoopIteration(run.phaseId, agentId, loop.iteration, loop.malformedAttemptCount)
      return null
    }
    return blockAndPersistInPhase(
      run,
      loop.iteration,
      withSchemaGateDetail(formatBlock, requireNotNull(attempt.schemaInvalidOperatorReason)),
      observability,
      failureDisposition = FeatureTaskRuntimeFailureDisposition.INVALID_OUTPUT,
      fileManifest = attempt.fileManifest,
      rejectedOutput = attempt.rejectedOutput,
    )
  }

  /**
   * A retryable `blocked` or `failed` envelope re-entering the loop as itself.
   *
   * It shares the semantic budget with schema-invalid retries but nothing else: the prompt gets the
   * terminal-retry directive rather than the schema-correction one, the block reason is not wrapped in
   * the schema-gate preamble, the block carries the envelope's own disposition instead of
   * INVALID_OUTPUT, and the re-entry is stamped PROCESS_RETRY so the AC-009 status and telemetry
   * surfaces do not report a schema correction that never happened.
   */
internal fun FeatureTaskRuntimeRunLoop.settleRetryableTerminal(context: FixLoopBranchContext): PhaseOutcome? {
    val (run, attempt, loop, observability, agentId) = context
    if (!FeatureTaskRuntimePhaseWorkflowDefinition.retriesOnInvalidOutput(run.phaseId)) {
      return blockAndPersistInPhase(
        run,
        loop.iteration,
        "${nonRetryingPhaseSchemaBlockReason(run.phaseId)} ${requireNotNull(attempt.retryableOperatorReason)}",
        observability,
        failureDisposition = requireNotNull(attempt.retryableTerminalDisposition),
        fileManifest = attempt.fileManifest,
      )
    }
    val failedIteration = loop.semanticIteration
    loop.iteration += 1
    loop.semanticIteration += 1
    loop.priorCorrection =
      PriorAttemptCorrection.retryableTerminal(requireNotNull(attempt.retryableTerminalRetryReason))
    observability.continuation(
      run.phaseId,
      agentId,
      loop.iteration,
      failedIteration,
      FeatureTaskRuntimeContinuationKind.PROCESS_RETRY,
    )
    return null
  }

internal fun FeatureTaskRuntimeRunLoop.settleSemanticFailure(context: FixLoopBranchContext): PhaseOutcome? {
    val (run, attempt, loop, observability, agentId) = context
    if (!FeatureTaskRuntimePhaseWorkflowDefinition.retriesOnInvalidOutput(run.phaseId)) {
      return blockAndPersistInPhase(
        run,
        loop.iteration,
        withSchemaGateDetail(
          nonRetryingPhaseSchemaBlockReason(run.phaseId),
          requireNotNull(attempt.retryableOperatorReason),
        ),
        observability,
        failureDisposition = FeatureTaskRuntimeFailureDisposition.INVALID_OUTPUT,
        fileManifest = attempt.fileManifest,
        rejectedOutput = attempt.rejectedOutput,
      )
    }
    loop.outputGateFailures += 1
    FeatureTaskRuntimeAttemptBudgets.outputGateBlockReason(run.phaseId, loop.outputGateFailures)?.let { capReason ->
      return blockAndPersistInPhase(
        run,
        loop.iteration,
        withSchemaGateDetail(capReason, requireNotNull(attempt.retryableOperatorReason)),
        observability,
        failureDisposition = FeatureTaskRuntimeFailureDisposition.INVALID_OUTPUT,
        fileManifest = attempt.fileManifest,
        rejectedOutput = attempt.rejectedOutput,
      )
    }
    val failedIteration = loop.semanticIteration
    loop.iteration += 1
    loop.semanticIteration += 1
    loop.priorCorrection = attempt.semanticRetryReason?.let { retryReason ->
      PriorAttemptCorrection.schemaGate(
        retryReason,
        correctiveRepairContext = attempt.correctiveRepairContext,
      )
    }
    observability.fixLoopIteration(run.phaseId, agentId, loop.iteration, failedIteration)
    return null
  }

  /**
   * Continuation segments already spent on this phase, read from the durable attempt history rather
   * than an in-memory counter. Without this a crash resume would silently refill the budget and the
   * bounded continuation loop would not be bounded across process lifetimes.
   *
   * Scoped to this visit — phase, loop AND edge iteration — matching the continuation projection.
   * Counting earlier rounds of the same loop would charge a brand-new repair round for segments spent
   * on work it was never given, and could block it before its first launch.
   */
  /**
   * The attempts this phase has spent in a row without reaching its output gate, read from the
   * durable ledger so the count survives the crash resume that produced it. Without this the outer
   * resume path charges each relaunch to the semantic repair budget, and a phase that never emitted
   * a byte gets blocked for "invalid output".
   */
internal fun FeatureTaskRuntimeRunLoop.durableNonOutputAttempts(run: PhaseRun): List<FeatureTaskRuntimeNonOutputAttempt> =
    state.trailingNonOutputAttempts(run.phaseId) { reason -> isProcessFailureBlockReason(run.phaseId, reason) }

  /**
   * True while an operator-reopened phase has not yet run. An operator who reopened a blocked phase
   * has substituted their own judgment for every automatic budget, so the reopened phase must
   * actually relaunch — re-surfacing the block they just acted on makes the reopen a no-op.
   */
internal fun FeatureTaskRuntimeRunLoop.operatorReopenedPhase(phaseId: String): Boolean =
    operatorBlockRetry?.phaseId == phaseId && !operatorBlockRetryCompleted

internal fun FeatureTaskRuntimeRunLoop.durableContinuationSegmentCount(run: PhaseRun): Int {
    if (!FeatureTaskRuntimePhaseWorkflowDefinition.isMutatingPhase(run.phaseId)) return 0
    val attempts = recorder.loadImplementationAttempts(run.request.workflowId, run.request.dbPathOverride)
      ?: return 0
    return attempts.count {
      it.phaseId == run.phaseId &&
        it.loopId == run.reentry?.loopId &&
        it.edgeIteration == run.reentry?.edgeIteration &&
        it.status == FeatureTaskRuntimeImplementationAttemptStatus.INCOMPLETE
    }
  }

  /**
   * Appends the incomplete attempt to the durable history, reporting whether it actually landed.
   *
   * A false return must never be swallowed. The continuation projection and the durable segment
   * budget are both derived from this history: a silently dropped append leaves the next segment with
   * no prior receipt AND leaves the segment count at zero, so a crash resume would refill the budget
   * from scratch and the bounded continuation loop would stop being bounded across process lifetimes.
   * Blocking is the only safe response. The ordering fix above removed the one reachable trigger
   * (a non-`implementation_receipt` projection_kind reaching this path); this stays as the
   * defense-in-depth guard for any future empty-patch condition.
   */
internal fun FeatureTaskRuntimeRunLoop.recordIncompleteAttempt(run: PhaseRun, iteration: Int, attempt: AttemptResult): Boolean {
    val normalized = attempt.incompleteWorkOutput ?: return false
    return recorder.recordIncompleteImplementationAttempt(
      FeatureTaskRuntimePhaseStateRequest(
        workflowId = run.request.workflowId,
        phaseId = run.phaseId,
        status = STATUS_RUNNING,
        attemptCount = iteration.coerceAtLeast(1),
        resolvedAgentId = run.resolvedAgent.resolvedAgentId,
        finished = false,
        normalizedOutput = normalized,
        loopId = run.reentry?.loopId,
        edgeIteration = run.reentry?.edgeIteration,
      ),
      run.request.dbPathOverride,
    )
  }

