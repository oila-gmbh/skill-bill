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


internal fun FeatureTaskRuntimeRunLoop.clearRecoveredBranchSetupBlock(phaseId: String) {
    if (!state.hasBranchSetupBlock(phaseId)) {
      return
    }
    state.clearBranchSetupBlock(phaseId)
  }

internal fun FeatureTaskRuntimeRunLoop.persistBranchSetupBlock(phaseId: String, reason: String) {
    recorder.recordPhaseState(
      FeatureTaskRuntimePhaseStateRequest(
        workflowId = request.workflowId,
        phaseId = phaseId,
        status = STATUS_BLOCKED,
        attemptCount = 1,
        resolvedAgentId = BRANCH_SETUP_AGENT_ID,
        finished = false,
        outputArtifact = null,
        blockedReason = reason,
      ),
      request.dbPathOverride,
    )
    observability.branchSetupBlocked(phaseId, BRANCH_SETUP_AGENT_ID, reason)
  }

internal fun FeatureTaskRuntimeRunLoop.blockAt(phaseId: String, reason: String) {
    blocked = FeatureTaskRuntimeRunReport.Blocked(
      issueKey = request.issueKey,
      workflowId = request.workflowId,
      featureSize = request.runInvariants.featureSize.name,
      lastIncompletePhase = phaseId,
      blockedReason = reason,
      completedPhaseIds = state.completedPhaseIds(),
      resolvedBranch = resolvedBranch,
    )
  }

internal fun FeatureTaskRuntimeRunLoop.blockOnCapExhaustion(phaseId: String, transition: FeatureTaskRuntimeNextPhase.TerminalBlock) {
    val unresolvedFindings = if (transition.loopId == FeatureTaskRuntimePhaseWorkflowDefinition.AUDIT_GAP_LOOP_ID) {
      emptyList()
    } else {
      state.unresolvedReviewFindings(phaseId)
    }
    val reason = capExhaustionReason(
      transition.loopId,
      transition.edgeIteration,
      transition.unresolvedVerdict,
      unresolvedFindings,
    )
    val resolvedAgent = FeatureTaskRuntimeAgentResolver.resolve(
      phaseId = phaseId,
      assignment = request.agentAssignment,
      invokedAgentId = request.invokedAgentId,
    )
    val run = PhaseRun(
      phaseId = phaseId,
      declaration = phaseDeclaration(phaseId, request.runInvariants.featureSize, qualityGateSelection()),
      resolvedAgent = resolvedAgent,
      modelDirective = FeatureTaskRuntimeModelResolver.resolve(
        phaseId,
        resolvedAgent.resolvedAgentId,
        request.modelAssignment,
      ),
      compaction = request.compactionSettings.directiveFor(phaseId),
      request = request,
      specSource = specSource,
    )
    blockAndPersist(
      run,
      state.nextIteration(phaseId),
      reason,
      observability,
      loopId = transition.loopId,
      edgeIteration = transition.edgeIteration,
      outputArtifact = state.outputFor(phaseId)?.payload,
    )
    blockAt(phaseId, reason)
  }

internal fun FeatureTaskRuntimeRunLoop.effectiveEdgeIterationCount(edge: FeatureTaskRuntimeBackwardEdge): Int =
    state.edgeIterationCount(edge.loopId)

internal fun FeatureTaskRuntimeRunLoop.persistResolvedReviewTier(run: PhaseRun, resolution: ReviewPassResolution) {
    if (run.phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW || !isGoalContinuationRun(request)) {
      return
    }
    goalContinuationRecorder.updateReviewState(request.workflowId, request.dbPathOverride) { state ->
      state.copy(
        resolvedTier = RuntimeOwnedReviewMode.execute(resolution.resolvedTier),
        decidingRule = resolution.decidingRule,
      )
    }
  }

  /**
   * Every remediation pass must key one disposition per Blocker its immediately preceding completed
   * pass emitted — including a Blocker that pass introduced itself — so the ids are minted here from
   * that durable pass result rather than invented by the agent. Empty for pass one, which has no
   * prior pass to dispose.
   */
internal fun FeatureTaskRuntimeRunLoop.priorBlockerFindingIds(): List<String> {
    val priorPass = goalReviewStateOrNull()?.passResults?.lastOrNull() ?: return emptyList()
    return priorPass.findings
      .filter { it.severity == GOAL_SUBTASK_REVIEW_BLOCKER_SEVERITY }
      // Prefer the id the prior pass's output actually carried, so the ids the prompt asks the agent
      // to disposition against are the ids it saw. The positional id is only a fallback for records
      // written before the review output's own id was captured.
      .mapIndexed { index, finding -> finding.findingId ?: "pass${priorPass.passNumber}-blocker-${index + 1}" }
  }

internal fun FeatureTaskRuntimeRunLoop.goalReviewStateOrNull(): GoalSubtaskReviewState? = if (!isGoalContinuationRun(request)) {
    null
  } else {
    goalContinuationRecorder.reviewState(request.workflowId, request.dbPathOverride)
  }

internal fun FeatureTaskRuntimeRunLoop.pauseAt(phaseId: String, reason: String, resumableStep: String) {
    paused = FeatureTaskRuntimeRunReport.Paused(
      issueKey = request.issueKey,
      workflowId = request.workflowId,
      featureSize = request.runInvariants.featureSize.name,
      pausedPhase = phaseId,
      pauseReason = reason,
      resumableStep = resumableStep,
      completedPhaseIds = state.completedPhaseIds(),
      resolvedBranch = resolvedBranch,
    )
  }

internal fun FeatureTaskRuntimeRunLoop.mintAuditGapPause(
    pause: FeatureTaskRuntimeAuditGapPause,
    auditPhaseId: String,
    auditOutputArtifact: String?,
  ) {
    recorder.persistAuditGapPause(request.workflowId, pause, request.dbPathOverride)
    if (isGoalContinuationRun(request)) {
      goalContinuationRecorder.recordGoalContinuationState(
        GoalContinuationStateRecordRequest(
          workflowId = request.workflowId,
          workflowStatus = STATUS_PAUSED,
        ),
        dbOverride = request.dbPathOverride,
      )
    }
    val resolvedAgent = FeatureTaskRuntimeAgentResolver.resolve(
      phaseId = auditPhaseId,
      assignment = request.agentAssignment,
      invokedAgentId = request.invokedAgentId,
    )
    recorder.recordPhaseState(
      FeatureTaskRuntimePhaseStateRequest(
        workflowId = request.workflowId,
        phaseId = auditPhaseId,
        status = STATUS_PAUSED,
        attemptCount = state.nextIteration(auditPhaseId),
        resolvedAgentId = resolvedAgent.resolvedAgentId,
        finished = false,
        blockedReason = pause.reason,
        failureDisposition = FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION,
        loopId = FeatureTaskRuntimePhaseWorkflowDefinition.AUDIT_GAP_LOOP_ID,
        edgeIteration = pause.edgeIteration,
        outputArtifact = auditOutputArtifact,
      ),
      dbOverride = request.dbPathOverride,
    )
    pauseAt(auditPhaseId, pause.reason, FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT)
  }

internal fun FeatureTaskRuntimeRunLoop.applyAuditGapPauseDecision(
    pause: FeatureTaskRuntimeAuditGapPause,
    decision: GoalSubtaskOperatorDecision,
  ): String? {
    if (pause.grantConsumed) {
      return "The audit-gap pause's retry grant is already consumed; a new operator decision is required to act."
    }
    return when (decision) {
      GoalSubtaskOperatorDecision.RETRY_FIX -> {
        recorder.persistAuditGapPause(
          request.workflowId,
          pause.copy(operatorDecision = AUDIT_GAP_PAUSE_DECISION_RETRY_FIX),
          request.dbPathOverride,
        )
        null
      }
      GoalSubtaskOperatorDecision.ABANDON_SUBTASK -> {
        recorder.persistAuditGapPause(
          request.workflowId,
          pause.copy(operatorDecision = AUDIT_GAP_PAUSE_DECISION_ABANDON_SUBTASK),
          request.dbPathOverride,
        )
        null
      }
      GoalSubtaskOperatorDecision.ACCEPT_AND_ADVANCE ->
        "An unmet acceptance criterion cannot be accepted-and-advanced; choose retry_fix or abandon_subtask " +
          "for an audit-gap pause."
    }
  }

internal fun FeatureTaskRuntimeRunLoop.remediationCheckpointBlockedReason(branch: String, error: String): String =
    "Feature-task-runtime could not establish a remediation checkpoint on the feature branch '$branch' " +
      "before re-entering a mutating phase" + (if (error.isBlank()) "." else " ($error).") +
      " Refusing to re-enter a mutating phase on a dirty, non-reconcilable tree."

internal fun FeatureTaskRuntimeRunLoop.auditReviewCheckpointBlockedReason(branch: String, error: String): String =
    "Feature-task-runtime could not commit the audited implementation on the feature branch '$branch' " +
      "before review" + (if (error.isBlank()) "." else " ($error).") +
      " Refusing to review an uncommitted final audit iteration."

internal fun FeatureTaskRuntimeRunLoop.capExhaustionReason(
    loopId: String,
    edgeIteration: Int,
    verdict: FeatureTaskRuntimeVerdict,
    unresolvedFindings: List<FeatureTaskRuntimeReviewFinding> = emptyList(),
  ): String {
    if (FeatureTaskRuntimePhaseWorkflowDefinition.isRegenerationLoopId(loopId)) {
      return regenerationCapExhaustionReason(loopId, edgeIteration)
    }
    val findingsSuffix = if (unresolvedFindings.isEmpty()) {
      ""
    } else {
      " Unresolved findings: " +
        unresolvedFindings.joinToString("; ") { "[${it.severity.wireValue}] ${it.message}" } + "."
    }
    return "Backward-edge loop '$loopId' exhausted its per-edge cap after $edgeIteration iteration(s) with the " +
      "verdict '${verdict.wireValue}' still unresolved; the run blocks rather than re-entering past the cap." +
      findingsSuffix
  }

  // SKILL-140: AC-004 cap-exhaustion reason for a regeneration loop, naming the quarantined record, the
  // producing phase, and the attempt count. Shared by the in-run transition block and the pre-launch
  // resume cap guard, so both surface the same actionable message.
internal fun FeatureTaskRuntimeRunLoop.regenerationCapExhaustionReason(loopId: String, edgeIteration: Int): String {
    val producer = FeatureTaskRuntimePhaseWorkflowDefinition.REGENERATION_LOOP_ID_BY_PRODUCER.entries
      .firstOrNull { it.value == loopId }?.key
    val latest = producer?.let { producing ->
      recorder.loadQuarantinedRecords(request.workflowId, request.dbPathOverride)
        .orEmpty()
        .lastOrNull { it.producingPhaseId == producing }
    }
    val recordId = latest?.recordIdentifier() ?: producer?.let { "$it#<unknown-iteration>" } ?: "<unknown>"
    return "Quarantine-and-regenerate loop '$loopId' exhausted its regeneration cap after $edgeIteration " +
      "attempt(s): the quarantined record '$recordId' produced by phase '${producer ?: "<unknown>"}' still " +
      "fails projection validation. The run blocks durably rather than regenerating past the cap; recover the " +
      "record out of band by deleting or migrating the offending row."
  }

internal fun FeatureTaskRuntimeRunLoop.runPhase(
    phaseId: String,
    request: FeatureTaskRuntimeRunRequest,
    state: FeatureTaskRuntimeRunState,
    observability: FeatureTaskRuntimeRunObservability,
    specSource: SpecSource,
    reentry: PendingReentry?,
    phaseTokenAccumulator: MutableMap<String, Pair<Int, Int>>? = null,
  ): PhaseOutcome {
    val resolvedAgent = FeatureTaskRuntimeAgentResolver.resolve(
      phaseId = phaseId,
      assignment = request.agentAssignment,
      invokedAgentId = request.invokedAgentId,
    )
    val declaration = phaseDeclaration(
      phaseId,
      request.runInvariants.featureSize,
      qualityGateSelection(),
    ).let { declaration ->
      if (phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT &&
        reentry?.loopId == FeatureTaskRuntimePhaseWorkflowDefinition.AUDIT_GAP_LOOP_ID
      ) {
        declaration.copy(
          projectionDeclarations = FeatureTaskRuntimePhaseWorkflowDefinition.auditRemediationProjections(),
        )
      } else if (
        phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT &&
        state.edgeIterationCount(FeatureTaskRuntimePhaseWorkflowDefinition.AUDIT_GAP_LOOP_ID) > 0
      ) {
        declaration.copy(
          projectionDeclarations = declaration.projectionDeclarations +
            FeatureTaskRuntimePhaseWorkflowDefinition.priorGapMemoryDeclaration(phaseId),
        )
      } else {
        declaration
      }
    }
    val run = PhaseRun(
      phaseId = phaseId,
      declaration = declaration,
      resolvedAgent = resolvedAgent,
      modelDirective = FeatureTaskRuntimeModelResolver.resolve(
        phaseId,
        resolvedAgent.resolvedAgentId,
        request.modelAssignment,
      ),
      compaction = request.compactionSettings.directiveFor(phaseId),
      request = request,
      specSource = specSource,
      reentry = reentry,
    )
    preLaunchBlock(run, state, observability)?.let { return it }
    return when (val prepared = prepareGoalReviewRun(run, observability)) {
      is GoalReviewRunReady -> when {
        run.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW ->
          runDeclaredReviewDriverCycle(prepared.run, state, observability)
        run.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE ->
          runDeclaredValidationGateCycle(prepared.run, state, observability, phaseTokenAccumulator)
        run.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD ->
          runDeclaredBuildGateCycle(prepared.run, state, observability, phaseTokenAccumulator)
        else -> runPhaseAttempts(prepared.run, state, observability, phaseTokenAccumulator)
      }
      GoalReviewRunPreparation.CarryForward -> settleCarriedForwardGoalReview(
        run = run,
        state = state,
        observability = observability,
      )
      is GoalReviewRunPreparation.Blocked -> PhaseOutcome.blocked(prepared.reason)
    }
  }

