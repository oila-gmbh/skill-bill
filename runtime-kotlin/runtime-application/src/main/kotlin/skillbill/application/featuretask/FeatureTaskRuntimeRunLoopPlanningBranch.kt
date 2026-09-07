package skillbill.application.featuretask

import skillbill.application.featuretask.model.FeatureTaskRuntimePhaseStateRequest
import skillbill.application.featuretask.model.FeatureTaskRuntimeRunReport
import skillbill.application.review.RuntimeOwnedReviewMode
import skillbill.workflow.goal.model.GOAL_SUBTASK_REVIEW_BLOCKER_SEVERITY
import skillbill.workflow.goal.model.GoalSubtaskOperatorDecision
import skillbill.workflow.goal.model.GoalSubtaskReviewState
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.AUDIT_GAP_PAUSE_DECISION_ABANDON_SUBTASK
import skillbill.workflow.taskruntime.model.AUDIT_GAP_PAUSE_DECISION_RETRY_FIX
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditGapPause
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeBackwardEdge
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFailureDisposition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeNextPhase
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseDeclaration
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeReviewFinding
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.model.ReviewPassResolution

object FeatureTaskRuntimeRunLoopPlanningBranch {
  fun clearRecoveredBranchSetupBlock(runLoop: FeatureTaskRuntimeRunLoop, phaseId: String) {
    if (!runLoop.state.hasBranchSetupBlock(phaseId)) {
      return
    }
    runLoop.state.clearBranchSetupBlock(phaseId)
  }

  fun persistBranchSetupBlock(runLoop: FeatureTaskRuntimeRunLoop, phaseId: String, reason: String) {
    runLoop.recorder.recordPhaseState(
      FeatureTaskRuntimePhaseStateRequest(
        workflowId = runLoop.request.workflowId,
        phaseId = phaseId,
        status = STATUS_BLOCKED,
        attemptCount = 1,
        resolvedAgentId = BRANCH_SETUP_AGENT_ID,
        finished = false,
        outputArtifact = null,
        blockedReason = reason,
      ),
      runLoop.request.dbPathOverride,
    )
    runLoop.observability.branchSetupBlocked(phaseId, BRANCH_SETUP_AGENT_ID, reason)
  }

  fun blockAt(runLoop: FeatureTaskRuntimeRunLoop, phaseId: String, reason: String) {
    runLoop.session.blocked = FeatureTaskRuntimeRunReport.Blocked(
      issueKey = runLoop.request.issueKey,
      workflowId = runLoop.request.workflowId,
      featureSize = runLoop.request.runInvariants.featureSize.name,
      lastIncompletePhase = phaseId,
      blockedReason = reason,
      completedPhaseIds = runLoop.state.completedPhaseIds(),
      resolvedBranch = runLoop.session.resolvedBranch,
    )
  }

  fun blockOnCapExhaustion(
    runLoop: FeatureTaskRuntimeRunLoop,
    phaseId: String,
    transition: FeatureTaskRuntimeNextPhase.TerminalBlock,
  ) {
    val unresolvedFindings = if (transition.loopId == FeatureTaskRuntimePhaseWorkflowDefinition.AUDIT_GAP_LOOP_ID) {
      emptyList()
    } else {
      runLoop.state.unresolvedReviewFindings(phaseId)
    }
    val reason = capExhaustionReason(
      runLoop,
      transition.loopId,
      transition.edgeIteration,
      transition.unresolvedVerdict,
      unresolvedFindings,
    )
    val resolvedAgent = FeatureTaskRuntimeAgentResolver.resolve(
      phaseId = phaseId,
      assignment = runLoop.request.agentAssignment,
      invokedAgentId = runLoop.request.invokedAgentId,
    )
    val run = PhaseRun(
      phaseId = phaseId,
      declaration = phaseDeclaration(
        phaseId,
        runLoop.request.runInvariants.featureSize,
        FeatureTaskRuntimeRunLoopTransitions.qualityGateSelection(runLoop),
      ),
      resolvedAgent = resolvedAgent,
      modelDirective = FeatureTaskRuntimeModelResolver.resolve(
        phaseId,
        resolvedAgent.resolvedAgentId,
        runLoop.request.modelAssignment,
      ),
      compaction = runLoop.request.compactionSettings.directiveFor(phaseId),
      request = runLoop.request,
      specSource = runLoop.specSource,
    )
    FeatureTaskRuntimeRunLoopPhaseAttempts.blockAndPersist(
      runLoop,
      BlockAndPersistArgs(
        run = run,
        attemptCount = runLoop.state.nextIteration(phaseId),
        reason = reason,
        observability = runLoop.observability,
        loopId = transition.loopId,
        edgeIteration = transition.edgeIteration,
        failureDisposition = FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION,
        payload = BlockAndPersistPayload(outputArtifact = runLoop.state.outputFor(phaseId)?.payload),
      ),
    )
    blockAt(runLoop, phaseId, reason)
  }

  fun effectiveEdgeIterationCount(runLoop: FeatureTaskRuntimeRunLoop, edge: FeatureTaskRuntimeBackwardEdge): Int =
    runLoop.state.edgeIterationCount(edge.loopId)

  internal fun persistResolvedReviewTier(
    runLoop: FeatureTaskRuntimeRunLoop,
    run: PhaseRun,
    resolution: ReviewPassResolution,
  ) {
    if (run.phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW ||
      !isGoalContinuationRun(runLoop.request)
    ) {
      return
    }
    runLoop.goalContinuationRecorder.updateReviewState(
      runLoop.request.workflowId,
      runLoop.request.dbPathOverride,
    ) { state ->
      state.copy(
        resolvedTier = RuntimeOwnedReviewMode.execute(resolution.resolvedTier),
        decidingRule = resolution.decidingRule,
      )
    }
  }

  fun priorBlockerFindingIds(runLoop: FeatureTaskRuntimeRunLoop): List<String> {
    val priorPass = goalReviewStateOrNull(runLoop)?.passResults?.lastOrNull() ?: return emptyList()
    return priorPass.findings
      .filter { it.severity == GOAL_SUBTASK_REVIEW_BLOCKER_SEVERITY }
      .mapIndexed { index, finding -> finding.findingId ?: "pass${priorPass.passNumber}-blocker-${index + 1}" }
  }

  fun goalReviewStateOrNull(runLoop: FeatureTaskRuntimeRunLoop): GoalSubtaskReviewState? =
    if (!isGoalContinuationRun(runLoop.request)) {
      null
    } else {
      runLoop.goalContinuationRecorder.reviewState(runLoop.request.workflowId, runLoop.request.dbPathOverride)
    }

  fun regenerationCapExhaustionReason(runLoop: FeatureTaskRuntimeRunLoop, loopId: String, edgeIteration: Int): String {
    val producer = FeatureTaskRuntimePhaseWorkflowDefinition.REGENERATION_LOOP_ID_BY_PRODUCER.entries
      .firstOrNull { it.value == loopId }?.key
    val latest = producer?.let { producing ->
      runLoop.recorder.loadQuarantinedRecords(runLoop.request.workflowId, runLoop.request.dbPathOverride)
        .orEmpty()
        .lastOrNull { it.producingPhaseId == producing }
    }
    val recordId = latest?.recordIdentifier() ?: producer?.let { "$it#<unknown-iteration>" } ?: "<unknown>"
    return "Quarantine-and-regenerate loop '$loopId' exhausted its regeneration cap after $edgeIteration " +
      "attempt(s): the quarantined record '$recordId' produced by phase '${producer ?: "<unknown>"}' still " +
      "fails projection validation. The run blocks durably rather than regenerating past the cap; recover the " +
      "record out of band by deleting or migrating the offending row."
  }

  internal fun runPhase(runLoop: FeatureTaskRuntimeRunLoop, args: RunPhaseArgs): PhaseOutcome {
    val phaseId = args.phaseId
    val request = args.request
    val state = args.state
    val observability = args.observability
    val specSource = args.specSource
    val reentry = args.reentry
    val phaseTokenAccumulator = args.phaseTokenAccumulator
    val declaration = phaseDeclarationForRun(runLoop, phaseId, runLoop.state, reentry)
    val run = buildPhaseRun(
      runLoop,
      BuildPhaseRunArgs(phaseId, runLoop.request, declaration, runLoop.specSource, reentry),
    )
    FeatureTaskRuntimeRunLoopPhaseRunner.preLaunchBlock(
      runLoop,
      run,
      runLoop.state,
      runLoop.observability,
    )?.let { return it }
    return runPreparedPhase(runLoop, run, runLoop.state, runLoop.observability, runLoop.phaseTokenAccumulator)
  }

  internal fun phaseDeclarationForRun(
    runLoop: FeatureTaskRuntimeRunLoop,
    phaseId: String,
    state: FeatureTaskRuntimeRunState,
    reentry: PendingReentry?,
  ): FeatureTaskRuntimePhaseDeclaration {
    val declaration = phaseDeclaration(
      phaseId,
      runLoop.request.runInvariants.featureSize,
      FeatureTaskRuntimeRunLoopTransitions.qualityGateSelection(runLoop),
    )
    return when {
      phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT &&
        reentry?.loopId == FeatureTaskRuntimePhaseWorkflowDefinition.AUDIT_GAP_LOOP_ID ->
        declaration.copy(
          projectionDeclarations = FeatureTaskRuntimePhaseWorkflowDefinition.auditRemediationProjections(),
        )
      phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT &&
        state.edgeIterationCount(FeatureTaskRuntimePhaseWorkflowDefinition.AUDIT_GAP_LOOP_ID) > 0 ->
        declaration.copy(
          projectionDeclarations = declaration.projectionDeclarations +
            FeatureTaskRuntimePhaseWorkflowDefinition.priorGapMemoryDeclaration(phaseId),
        )
      else -> declaration
    }
  }

  internal fun buildPhaseRun(runLoop: FeatureTaskRuntimeRunLoop, args: BuildPhaseRunArgs): PhaseRun {
    val phaseId = args.phaseId
    val declaration = args.declaration
    val reentry = args.reentry
    val resolvedAgent = FeatureTaskRuntimeAgentResolver.resolve(
      phaseId = phaseId,
      assignment = runLoop.request.agentAssignment,
      invokedAgentId = runLoop.request.invokedAgentId,
    )
    return PhaseRun(
      phaseId = phaseId,
      declaration = declaration,
      resolvedAgent = resolvedAgent,
      modelDirective = FeatureTaskRuntimeModelResolver.resolve(
        phaseId,
        resolvedAgent.resolvedAgentId,
        runLoop.request.modelAssignment,
      ),
      compaction = runLoop.request.compactionSettings.directiveFor(phaseId),
      request = runLoop.request,
      specSource = runLoop.specSource,
      reentry = reentry,
    )
  }

  internal fun runPreparedPhase(
    runLoop: FeatureTaskRuntimeRunLoop,
    run: PhaseRun,
    state: FeatureTaskRuntimeRunState,
    observability: FeatureTaskRuntimeRunObservability,
    phaseTokenAccumulator: MutableMap<String, Pair<Int, Int>>?,
  ): PhaseOutcome = when (
    val prepared = FeatureTaskRuntimeRunLoopPhaseRunner.prepareGoalReviewRun(
      runLoop,
      run,
      observability,
    )
  ) {
    is GoalReviewRunReady -> when {
      run.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW ->
        FeatureTaskRuntimeRunLoopPhaseRunner.runDeclaredReviewDriverCycle(runLoop, prepared.run, state, observability)
      run.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE ->
        FeatureTaskRuntimeRunLoopValidationGate.runDeclaredValidationGateCycle(
          runLoop,
          prepared.run,
          state,
          observability,
          runLoop.phaseTokenAccumulator,
        )
      run.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD ->
        FeatureTaskRuntimeRunLoopValidationGate.runDeclaredBuildGateCycle(
          runLoop,
          prepared.run,
          state,
          observability,
          runLoop.phaseTokenAccumulator,
        )
      else -> FeatureTaskRuntimeRunLoopValidationGate.runPhaseAttempts(
        runLoop,
        prepared.run,
        state,
        observability,
        runLoop.phaseTokenAccumulator,
      )
    }
    GoalReviewRunPreparation.CarryForward ->
      FeatureTaskRuntimeRunLoopPhaseRunner.settleCarriedForwardGoalReview(
        runLoop,
        run = run,
        state = state,
        observability = observability,
      )
    is GoalReviewRunPreparation.Blocked -> PhaseOutcome.blocked(prepared.reason)
  }

  fun pauseAt(runLoop: FeatureTaskRuntimeRunLoop, phaseId: String, reason: String, resumableStep: String) {
    runLoop.session.paused = FeatureTaskRuntimeRunReport.Paused(
      issueKey = runLoop.request.issueKey,
      workflowId = runLoop.request.workflowId,
      featureSize = runLoop.request.runInvariants.featureSize.name,
      pausedPhase = phaseId,
      pauseReason = reason,
      resumableStep = resumableStep,
      completedPhaseIds = runLoop.state.completedPhaseIds(),
      resolvedBranch = runLoop.session.resolvedBranch,
    )
  }

  fun mintAuditGapPause(
    runLoop: FeatureTaskRuntimeRunLoop,
    pause: FeatureTaskRuntimeAuditGapPause,
    auditPhaseId: String,
    auditOutputArtifact: String?,
  ) {
    runLoop.recorder.persistAuditGapPause(runLoop.request.workflowId, pause, runLoop.request.dbPathOverride)
    if (isGoalContinuationRun(runLoop.request)) {
      runLoop.goalContinuationRecorder.recordGoalContinuationState(
        GoalContinuationStateRecordRequest(
          workflowId = runLoop.request.workflowId,
          workflowStatus = STATUS_PAUSED,
        ),
        dbOverride = runLoop.request.dbPathOverride,
      )
    }
    val resolvedAgent = FeatureTaskRuntimeAgentResolver.resolve(
      phaseId = auditPhaseId,
      assignment = runLoop.request.agentAssignment,
      invokedAgentId = runLoop.request.invokedAgentId,
    )
    runLoop.recorder.recordPhaseState(
      FeatureTaskRuntimePhaseStateRequest(
        workflowId = runLoop.request.workflowId,
        phaseId = auditPhaseId,
        status = STATUS_PAUSED,
        attemptCount = runLoop.state.nextIteration(auditPhaseId),
        resolvedAgentId = resolvedAgent.resolvedAgentId,
        finished = false,
        blockedReason = pause.reason,
        failureDisposition = FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION,
        loopId = FeatureTaskRuntimePhaseWorkflowDefinition.AUDIT_GAP_LOOP_ID,
        edgeIteration = pause.edgeIteration,
        outputArtifact = auditOutputArtifact,
      ),
      dbOverride = runLoop.request.dbPathOverride,
    )
    pauseAt(runLoop, auditPhaseId, pause.reason, FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT)
  }

  fun applyAuditGapPauseDecision(
    runLoop: FeatureTaskRuntimeRunLoop,
    pause: FeatureTaskRuntimeAuditGapPause,
    decision: GoalSubtaskOperatorDecision,
  ): String? {
    if (pause.grantConsumed) {
      return "The audit-gap pause's retry grant is already consumed; a new operator decision is required to act."
    }
    return when (decision) {
      GoalSubtaskOperatorDecision.RETRY_FIX -> {
        runLoop.recorder.persistAuditGapPause(
          runLoop.request.workflowId,
          pause.copy(operatorDecision = AUDIT_GAP_PAUSE_DECISION_RETRY_FIX),
          runLoop.request.dbPathOverride,
        )
        null
      }
      GoalSubtaskOperatorDecision.ABANDON_SUBTASK -> {
        runLoop.recorder.persistAuditGapPause(
          runLoop.request.workflowId,
          pause.copy(operatorDecision = AUDIT_GAP_PAUSE_DECISION_ABANDON_SUBTASK),
          runLoop.request.dbPathOverride,
        )
        null
      }
      GoalSubtaskOperatorDecision.ACCEPT_AND_ADVANCE ->
        "An unmet acceptance criterion cannot be accepted-and-advanced; choose retry_fix or abandon_subtask " +
          "for an audit-gap pause."
    }
  }

  fun remediationCheckpointBlockedReason(branch: String, error: String): String =
    "Feature-task-runtime could not establish a remediation checkpoint on the feature branch '$branch' " +
      "before re-entering a mutating phase" + (if (error.isBlank()) "." else " ($error).") +
      " Refusing to re-enter a mutating phase on a dirty, non-reconcilable tree."

  fun auditReviewCheckpointBlockedReason(branch: String, error: String): String =
    "Feature-task-runtime could not commit the audited implementation on the feature branch '$branch' " +
      "before review" + (if (error.isBlank()) "." else " ($error).") +
      " Refusing to review an uncommitted final audit iteration."

  fun capExhaustionReason(
    runLoop: FeatureTaskRuntimeRunLoop,
    loopId: String,
    edgeIteration: Int,
    verdict: FeatureTaskRuntimeVerdict,
    unresolvedFindings: List<FeatureTaskRuntimeReviewFinding> = emptyList(),
  ): String {
    if (FeatureTaskRuntimePhaseWorkflowDefinition.isRegenerationLoopId(loopId)) {
      return regenerationCapExhaustionReason(runLoop, loopId, edgeIteration)
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
}
