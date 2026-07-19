package skillbill.application.featuretask

import skillbill.application.goalrunner.GoalSubtaskReviewSummaryReducer
import skillbill.application.model.FeatureTaskRuntimeFixLoopDecision
import skillbill.application.model.FeatureTaskRuntimePhaseStateRequest
import skillbill.application.model.FeatureTaskRuntimePlanningStopDecision
import skillbill.application.model.FeatureTaskRuntimeResolvedPhaseAgent
import skillbill.application.model.FeatureTaskRuntimeRunReport
import skillbill.application.model.FeatureTaskRuntimeRunRequest
import skillbill.application.workflow.repoRoot
import skillbill.config.model.PhaseModelDirective
import skillbill.contracts.JsonSupport
import skillbill.error.InvalidFeatureTaskRuntimePhaseOutputSchemaError
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.agentrun.model.AgentRunLaunchOutcome
import skillbill.ports.agentrun.model.SkillRunRequest
import skillbill.ports.agentrun.model.UnsupportedAgentRunLaunch
import skillbill.ports.goalrunner.GoalRunnerSubtaskLauncher
import skillbill.ports.goalrunner.model.GoalRunnerSubtaskLaunchRequest
import skillbill.ports.workflow.model.GoalSubtaskReviewInput
import skillbill.ports.workflow.repositoryFingerprint
import skillbill.ports.workflow.runtimePhaseChangedPathsBetweenCommits
import skillbill.ports.workflow.runtimePhaseHeadCommit
import skillbill.telemetry.estimation.estimateTokens
import skillbill.workflow.FeatureTaskRuntimePhaseOutputValidator
import skillbill.workflow.model.SpecSource
import skillbill.workflow.taskruntime.FeatureTaskRuntimeHandoffContract
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.FeatureTaskRuntimeTransitionFunction
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditRepairPlan
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditRepairState
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeBackwardEdge
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeCapExhaustionBehavior
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFailureDisposition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeNextPhase
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseDeclaration
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeReviewFinding
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeTransitionDeclaration
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.model.NormalizedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.detectAuditRepairNonProgress

internal data class FeatureTaskRuntimeRunLoopDependencies(
  val recorder: FeatureTaskRuntimePhaseRecorder,
  val goalContinuationRecorder: FeatureTaskRuntimeGoalContinuationRecorder,
  val outputValidator: FeatureTaskRuntimePhaseOutputValidator,
  val phaseGates: FeatureTaskRuntimePhaseGates,
  val subtaskLauncher: GoalRunnerSubtaskLauncher,
)

internal data class FeatureTaskRuntimeRunLoopContext(
  val request: FeatureTaskRuntimeRunRequest,
  val state: FeatureTaskRuntimeRunState,
  val observability: FeatureTaskRuntimeRunObservability,
  val specSource: SpecSource,
  val transitions: FeatureTaskRuntimeTransitionDeclaration,
  val phaseTokenAccumulator: MutableMap<String, Pair<Int, Int>>,
)

@Suppress("LargeClass", "LongMethod", "LongParameterList", "TooManyFunctions")
internal class FeatureTaskRuntimeRunLoop(
  private val dependencies: FeatureTaskRuntimeRunLoopDependencies,
  context: FeatureTaskRuntimeRunLoopContext,
) {
  private val request = context.request
  private val state = context.state
  private val observability = context.observability
  private val specSource = context.specSource
  private val transitions = context.transitions
  private val phaseTokenAccumulator = context.phaseTokenAccumulator
  private val recorder get() = dependencies.recorder
  private val goalContinuationRecorder get() = dependencies.goalContinuationRecorder
  private val outputValidator get() = dependencies.outputValidator
  private val phaseGates get() = dependencies.phaseGates
  private val subtaskLauncher get() = dependencies.subtaskLauncher
  private val branchSetupRunner get() = phaseGates.branchSetupRunner
  private val planningStopper get() = phaseGates.planningStopper
  private val specStatusProjector get() = phaseGates.specStatusProjector
  private val gitOperations get() = phaseGates.gitOperations

  private var resolvedBranch: String? = null
  private var blocked: FeatureTaskRuntimeRunReport.Blocked? = null
  private var decomposed: FeatureTaskRuntimeRunReport.Decomposed? = null

  private var pendingReentry: PendingReentry? = resumedReentry()
  private var activeReentry: PendingReentry? = pendingReentry

  private fun resumedReentry(): PendingReentry? {
    val (loopId, reentry) = state.latestInFlightReentry() ?: return null
    state.recordEdgeIteration(loopId, reentry.edgeIteration)
    return PendingReentry(
      phaseId = reentry.destinationPhaseId,
      loopId = loopId,
      edgeIteration = reentry.edgeIteration,
      drivingVerdict = reentry.drivingVerdict,
      reentryGapCriteria = if (loopId == FeatureTaskRuntimePhaseWorkflowDefinition.AUDIT_GAP_LOOP_ID) {
        state.unmetAuditCriteria(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT)
      } else {
        emptyList()
      },
      auditRepairPlan = if (loopId == FeatureTaskRuntimePhaseWorkflowDefinition.AUDIT_GAP_LOOP_ID) {
        state.auditRepairPlan(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT)
      } else {
        null
      },
      auditRepairState = if (loopId == FeatureTaskRuntimePhaseWorkflowDefinition.AUDIT_GAP_LOOP_ID) {
        recorder.loadAuditRepairState(request.workflowId, request.dbPathOverride)
      } else {
        null
      },
    )
  }

  fun drive() {
    val resumedReentry = pendingReentry
    if (resumedReentry?.loopId == FeatureTaskRuntimePhaseWorkflowDefinition.AUDIT_GAP_LOOP_ID) {
      state.auditGapPlanningContextError()?.let { reason ->
        blockInvalidAuditGapRecovery(resumedReentry, reason)
        return
      }
      if (resumedReentry.auditRepairPlan == null || resumedReentry.auditRepairState == null) {
        blockInvalidAuditGapRecovery(
          resumedReentry,
          "Audit-gap recovery requires the exact durably persisted audit repair plan and state.",
        )
        return
      }
      if (resumedReentry.auditRepairPlan != resumedReentry.auditRepairState.acceptedPlans.lastOrNull()) {
        blockInvalidAuditGapRecovery(
          resumedReentry,
          "Audit-gap recovery requires the phase-record repair plan to be identical to the latest durable " +
            "accepted plan.",
        )
        return
      }
    }
    var phaseId: String? = resumedReentry?.phaseId ?: transitions.forwardPhaseIds.first()
    while (phaseId != null) {
      val settled = advance(phaseId)
      val completedPhaseId = settled.completedPhaseId
      phaseId = if (completedPhaseId != null) {
        nextPhaseAfter(completedPhaseId, requireNotNull(settled.completedVerdict))
      } else {
        null
      }
    }
  }

  private fun advance(phaseId: String): PhaseSettlement {
    capExhaustedOnResume(phaseId)?.let { reason ->
      blockAt(phaseId, reason)
      return PhaseSettlement.stop()
    }
    reconcileCompletedGoalReviewPass(phaseId)?.let { reason ->
      blockAt(phaseId, reason)
      return PhaseSettlement.stop()
    }
    if (phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW && isGoalContinuationRun(request)) {
      val carriedForward = carriedForwardGoalReviewSettlement()
      if (carriedForward != null) {
        return carriedForward
      }
    }
    val reason = if (state.isComplete(phaseId)) {
      state.outputFor(phaseId)
        ?.takeIf { phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN }
        ?.let { applyPlanningStop(phaseId, it) }
    } else {
      establishBranchIfNeeded(phaseId) ?: run {
        specStatusProjector.projectCompleteBeforeCommitPhase(phaseId, request, specSource)
        runPhaseFor(phaseId)
      }
    }
    return when {
      decomposed != null -> PhaseSettlement.stop()
      reason != null -> {
        blockAt(phaseId, reason)
        PhaseSettlement.stop()
      }
      else -> PhaseSettlement.completed(phaseId, state.verdictFor(phaseId))
    }
  }

  private fun reconcileCompletedGoalReviewPass(phaseId: String): String? =
    if (isCompletedGoalReview(phaseId)) reconcileReservedGoalReviewPass(phaseId) else null

  private fun isCompletedGoalReview(phaseId: String): Boolean =
    phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW &&
      isGoalContinuationRun(request) &&
      state.isComplete(phaseId)

  private fun reconcileReservedGoalReviewPass(phaseId: String): String? = runCatching {
    goalContinuationRecorder.reviewState(request.workflowId, request.dbPathOverride)
  }.fold(
    onSuccess = { reviewState ->
      when {
        reviewState == null -> "Goal-subtask review state is missing while reconciling a completed review pass."
        reviewState.reservedPassNumber != null -> reconcileReservedGoalReviewOutput(phaseId)
        else -> null
      }
    },
    onFailure = { error ->
      "Goal-subtask review state is malformed while reconciling a completed review pass: ${error.message.orEmpty()}"
    },
  )

  private fun reconcileReservedGoalReviewOutput(phaseId: String): String? = state.outputFor(phaseId)?.payload
    ?.let { output ->
      runCatching { outputValidator.validateAndReadPhaseOutput(output, sourceLabel = phaseId) }.fold(
        onSuccess = { outputMap -> completeReservedGoalReviewPass(output, outputMap) },
        onFailure = { error ->
          "Completed goal-subtask review output cannot reconcile its reserved pass: ${error.message.orEmpty()}"
        },
      )
    }
    ?: "Completed goal-subtask review has no durable output to reconcile its reserved pass."

  private fun completeReservedGoalReviewPass(output: String, outputMap: Map<String, Any?>): String? {
    val findings = GoalSubtaskReviewSummaryReducer.fromOutput(outputMap)
    val outcome = GoalSubtaskReviewSummaryReducer.outcomeFor(outputMap, findings)
    return if (
      goalContinuationRecorder.completeGoalReviewPass(
        request = GoalReviewPassCompletionRequest(
          workflowId = request.workflowId,
          verdict = outcome.verdict,
          unresolvedFindingCount = outcome.unresolvedFindingCount,
          findings = findings,
          rawReviewResult = output,
        ),
        dbOverride = request.dbPathOverride,
      ) == null
    ) {
      "Completed goal-subtask review could not persist its reserved pass."
    } else {
      null
    }
  }

  private fun carriedForwardGoalReviewSettlement(): PhaseSettlement? = runCatching {
    goalContinuationRecorder.reviewState(request.workflowId, request.dbPathOverride)
  }.fold(
    onSuccess = { reviewState ->
      reviewState?.takeIf { it.reviewCapReached || it.reviewSkippedByUser || it.completedPassCount >= 2 }
        ?.let {
          settleCarriedForwardGoalReview(
            it,
            activeReentry,
          )
        }
    },
    onFailure = { error -> blockCarriedForwardReview(error.message.orEmpty()) },
  )

  private fun settleCarriedForwardGoalReview(
    reviewState: skillbill.workflow.taskruntime.model.GoalSubtaskReviewState,
    reentry: PendingReentry?,
  ): PhaseSettlement =
    runCatching { goalContinuationRecorder.lastGoalReviewResult(request.workflowId, request.dbPathOverride) }.fold(
      onSuccess = { rawResult ->
        rawResult?.let { validateCarriedForwardGoalReview(it, reviewState, reentry) }
          ?: blockCarriedForwardReview("missing")
      },
      onFailure = { error -> blockCarriedForwardReview(error.message.orEmpty()) },
    )

  private fun validateCarriedForwardGoalReview(
    rawResult: String,
    reviewState: skillbill.workflow.taskruntime.model.GoalSubtaskReviewState,
    reentry: PendingReentry?,
  ): PhaseSettlement = runCatching {
    outputValidator.validateAndReadPhaseOutput(
      rawResult,
      sourceLabel = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW,
    )
    recordCarriedForwardGoalReview(rawResult, reentry)
  }.fold(
    onSuccess = {
      PhaseSettlement.completed(
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW,
        requireNotNull(reviewState.passResults.lastOrNull()).verdict,
      )
    },
    onFailure = { error -> blockCarriedForwardReview(error.message.orEmpty()) },
  )

  private fun recordCarriedForwardGoalReview(rawResult: String, reentry: PendingReentry?) {
    val phaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW
    if (state.isComplete(phaseId)) {
      return
    }
    val iteration = state.nextIteration(phaseId)
    val priorRecord = state.recordFor(phaseId)
    recorder.recordCompletedPhase(
      FeatureTaskRuntimePhaseStateRequest(
        workflowId = request.workflowId,
        phaseId = phaseId,
        status = STATUS_COMPLETED,
        attemptCount = iteration,
        resolvedAgentId = priorRecord?.resolvedAgentId ?: "user-directed",
        finished = true,
        outputArtifact = rawResult,
        loopId = reentry?.loopId,
        edgeIteration = reentry?.edgeIteration,
      ),
      request.dbPathOverride,
    )
    if (reentry != null) pendingReentry = null
    state.recordCompleted(FeatureTaskRuntimePhaseOutput(phaseId, iteration, rawResult))
  }

  private fun blockCarriedForwardReview(detail: String): PhaseSettlement {
    val reason = if (detail == "missing") {
      "Goal-subtask review pass budget is exhausted but its durable raw review result is missing."
    } else {
      "Goal-subtask review pass budget is exhausted but its durable raw review result is malformed: $detail"
    }
    blockAt(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW, reason)
    return PhaseSettlement.stop()
  }

  @Suppress("CyclomaticComplexMethod")
  private fun nextPhaseAfter(phaseId: String, verdict: FeatureTaskRuntimeVerdict): String? {
    val effectiveVerdict = if (
      phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW &&
      isGoalContinuationRun(request) &&
      goalContinuationRecorder.reviewState(request.workflowId, request.dbPathOverride)?.reviewCapReached == true
    ) {
      FeatureTaskRuntimeVerdict.REVIEW_CAP_REACHED
    } else {
      verdict
    }
    val edge = matchingBackwardEdge(phaseId, effectiveVerdict)
    edge?.let(::resumeInFlightReviewFix)?.let { return it }
    val transition = FeatureTaskRuntimeTransitionFunction.nextTransition(
      declaration = transitions,
      currentPhaseId = phaseId,
      verdict = effectiveVerdict,
      edgeIterationCount = edge?.let { state.edgeIterationCount(it.loopId) } ?: 0,
    )
    return when (transition) {
      is FeatureTaskRuntimeNextPhase.TerminalAdvance -> null
      is FeatureTaskRuntimeNextPhase.TerminalBlock -> {
        blockOnCapExhaustion(phaseId, transition)
        null
      }
      is FeatureTaskRuntimeNextPhase.Next -> {
        transition.loopId?.let { loopId ->
          if (reentersMutatingPhase(requireNotNull(edge), transition.phaseId) &&
            !establishRemediationCheckpoint(phaseId)
          ) {
            return null
          }
          if (loopId == FeatureTaskRuntimePhaseWorkflowDefinition.AUDIT_GAP_LOOP_ID &&
            !authoritativeAuditRepairPlanMatches(phaseId)
          ) {
            blockAt(
              phaseId,
              "The accepted audit repair plan was not durably readable and identical before the audit_gap edge.",
            )
            return null
          }
          recordBackwardEdge(
            edge = edge,
            destinationPhaseId = transition.phaseId,
            loopId = loopId,
            edgeIteration = requireNotNull(transition.edgeIteration),
            verdict = effectiveVerdict,
          )
        }
        transition.phaseId
      }
    }
  }

  private fun authoritativeAuditRepairPlanMatches(auditPhaseId: String): Boolean {
    val normalizedPlan = state.auditRepairPlan(auditPhaseId) ?: return false
    val durableState = recorder.loadAuditRepairState(request.workflowId, request.dbPathOverride) ?: return false
    return durableState.acceptedPlans.lastOrNull() == normalizedPlan
  }

  private fun reentersMutatingPhase(edge: FeatureTaskRuntimeBackwardEdge, destinationPhaseId: String): Boolean =
    spanBetween(destinationPhaseId, edge.fromPhaseId).any(FeatureTaskRuntimePhaseWorkflowDefinition::isMutatingPhase)

  private fun spanBetween(destinationPhaseId: String, sourcePhaseId: String): List<String> {
    val destinationIndex = transitions.forwardPhaseIds.indexOf(destinationPhaseId)
    val sourceIndex = transitions.forwardPhaseIds.indexOf(sourcePhaseId)
    return if (destinationIndex in 0..sourceIndex) {
      transitions.forwardPhaseIds.subList(destinationIndex, sourceIndex + 1)
    } else {
      listOf(destinationPhaseId)
    }
  }

  private fun establishRemediationCheckpoint(precedingPhaseId: String): Boolean {
    val branch = resolvedBranch ?: return true
    if (FeatureTaskRuntimeBranchSetup.protectedBranchName(branch) != null) {
      return true
    }
    val head = phaseGates.gitOperations.currentBranch(request.repoRoot)
    if (!head.ok || head.value.trim() != branch.trim()) {
      return true
    }
    val status = phaseGates.gitOperations.worktreeStatus(request.repoRoot)
    return when {
      !status.ok -> blockCheckpoint(precedingPhaseId, branch, status.error)
      status.value.isBlank() -> true
      else -> commitCheckpoint(precedingPhaseId, branch)
    }
  }

  private fun commitCheckpoint(precedingPhaseId: String, branch: String): Boolean {
    val staged = phaseGates.gitOperations.stageAll(request.repoRoot)
    if (!staged.ok) {
      return blockCheckpoint(precedingPhaseId, branch, staged.error)
    }
    val commit = phaseGates.gitOperations.createCommit(request.repoRoot, remediationCheckpointMessage(branch))
    return if (commit.ok) true else blockCheckpoint(precedingPhaseId, branch, commit.error)
  }

  private fun blockCheckpoint(precedingPhaseId: String, branch: String, error: String): Boolean {
    blockAt(precedingPhaseId, remediationCheckpointBlockedReason(branch, error))
    return false
  }

  private fun matchingBackwardEdge(
    phaseId: String,
    verdict: FeatureTaskRuntimeVerdict,
  ): FeatureTaskRuntimeBackwardEdge? =
    transitions.backwardEdges.firstOrNull { it.fromPhaseId == phaseId && it.triggeringVerdict == verdict }

  private fun resumeInFlightReviewFix(edge: FeatureTaskRuntimeBackwardEdge): String? {
    if (edge.loopId != FeatureTaskRuntimePhaseWorkflowDefinition.REVIEW_FIX_LOOP_ID) return null
    val reviewRecord = state.recordFor(edge.fromPhaseId)
    if (reviewRecord?.reviewPassNumber == 2 || state.outputCountFor(edge.fromPhaseId) >= 2) return null
    val destinationRecord = state.recordFor(edge.destinationPhaseId)
      ?.takeIf { it.loopId == edge.loopId && it.edgeIteration == state.edgeIterationCount(edge.loopId) }
      ?: return null
    val edgeIteration = requireNotNull(destinationRecord.edgeIteration)
    state.reopenForReentry(edge.fromPhaseId)
    state.recordEdgeIteration(edge.loopId, edgeIteration)
    pendingReentry = PendingReentry(
      phaseId = edge.destinationPhaseId,
      loopId = edge.loopId,
      edgeIteration = edgeIteration,
      drivingVerdict = edge.triggeringVerdict,
    )
    activeReentry = pendingReentry
    return edge.destinationPhaseId
  }

  private fun recordBackwardEdge(
    edge: FeatureTaskRuntimeBackwardEdge,
    destinationPhaseId: String,
    loopId: String,
    edgeIteration: Int,
    verdict: FeatureTaskRuntimeVerdict,
  ) {
    val reopenedSpan = spanBetween(destinationPhaseId, edge.fromPhaseId)
    reopenedSpan.forEach(state::reopenForReentry)
    state.recordEdgeIteration(loopId, edgeIteration)
    val reentryGapCriteria = if (loopId == FeatureTaskRuntimePhaseWorkflowDefinition.AUDIT_GAP_LOOP_ID) {
      state.unmetAuditCriteria(edge.fromPhaseId)
    } else {
      emptyList()
    }
    pendingReentry = PendingReentry(
      destinationPhaseId,
      loopId,
      edgeIteration,
      verdict,
      reentryGapCriteria,
      if (loopId == FeatureTaskRuntimePhaseWorkflowDefinition.AUDIT_GAP_LOOP_ID) {
        state.auditRepairPlan(edge.fromPhaseId)
      } else {
        null
      },
      if (loopId == FeatureTaskRuntimePhaseWorkflowDefinition.AUDIT_GAP_LOOP_ID) {
        recorder.loadAuditRepairState(request.workflowId, request.dbPathOverride)
      } else {
        null
      },
    )
    activeReentry = pendingReentry
    observability.loopEdge(destinationPhaseId, loopId, edgeIteration, verdict)
  }

  private fun capExhaustedOnResume(phaseId: String): String? {
    val record = state.recordFor(phaseId) ?: return null
    return capExhaustionForRecord(phaseId, record)
  }

  private fun capExhaustionForRecord(phaseId: String, record: FeatureTaskRuntimePhaseRecord): String? {
    if (
      phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW &&
      record.reviewPassNumber == 2 &&
      record.blockedReason?.startsWith("Backward-edge loop '") != true
    ) {
      return null
    }
    val loopId = record.loopId
    val iteration = record.edgeIteration
    if (loopId == null || iteration == null || state.isLoopLiveClaimed(loopId)) {
      return null
    }
    val edge = transitions.backwardEdges.firstOrNull { candidate ->
      candidate.loopId == loopId &&
        (candidate.destinationPhaseId == phaseId || candidate.fromPhaseId == phaseId)
    }
    if (edge?.destinationPhaseId == phaseId) {
      val sourceRecord = state.recordFor(edge.fromPhaseId)
      if (
        sourceRecord?.status == STATUS_BLOCKED && sourceRecord.loopId == loopId &&
        sourceRecord.edgeIteration == iteration
      ) {
        return null
      }
    }
    return edge
      ?.takeIf { candidate -> blocksWhenCapExhausted(candidate, iteration) }
      ?.let { capExhaustionReason(it.loopId, iteration, it.triggeringVerdict) }
  }

  private fun blocksWhenCapExhausted(edge: FeatureTaskRuntimeBackwardEdge, iteration: Int): Boolean =
    edge.capExhaustionBehavior == FeatureTaskRuntimeCapExhaustionBehavior.BLOCK &&
      edge.perEdgeCap?.let { iteration >= it } == true

  private fun runPhaseFor(phaseId: String): String? {
    val briefingReentry = pendingReentry?.takeIf { it.phaseId == phaseId }
    if (briefingReentry != null) pendingReentry = null
    val reentry = briefingReentry ?: activeReentry?.takeIf { active ->
      transitions.backwardEdges
        .firstOrNull { it.loopId == active.loopId }
        ?.let { edge -> phaseId in spanBetween(edge.destinationPhaseId, edge.fromPhaseId) } == true
    }?.copy(phaseId = phaseId, reentryGapCriteria = emptyList())
    val outcome = runPhase(phaseId, request, state, observability, specSource, reentry, phaseTokenAccumulator)
    return outcome.blockedReason ?: run {
      val completedOutput = requireNotNull(outcome.completedOutput)
      state.recordCompleted(completedOutput)
      applyPlanningStop(phaseId, completedOutput)
    }
  }

  private fun blockInvalidAuditGapRecovery(reentry: PendingReentry, reason: String) {
    val phaseId = reentry.phaseId
    val resolvedAgentId = FeatureTaskRuntimeAgentResolver.resolve(
      phaseId = phaseId,
      assignment = request.agentAssignment,
      invokedAgentId = request.invokedAgentId,
    ).resolvedAgentId
    val attempt = state.nextIteration(phaseId)
    recorder.recordPhaseState(
      FeatureTaskRuntimePhaseStateRequest(
        workflowId = request.workflowId,
        phaseId = phaseId,
        status = STATUS_BLOCKED,
        attemptCount = attempt,
        resolvedAgentId = resolvedAgentId,
        finished = false,
        blockedReason = reason,
        failureDisposition = FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION,
        loopId = reentry.loopId,
        edgeIteration = reentry.edgeIteration,
      ),
      request.dbPathOverride,
    )
    observability.blocked(phaseId, resolvedAgentId, attempt, reason)
    blockAt(phaseId, reason)
  }

  private fun applyPlanningStop(phaseId: String, planOutput: FeatureTaskRuntimePhaseOutput): String? {
    if (phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN) {
      return null
    }
    return when (val decision = resolvePlanningStop(planOutput)) {
      is FeatureTaskRuntimePlanningStopDecision.Proceed -> null
      is FeatureTaskRuntimePlanningStopDecision.Decomposed -> {
        decomposed = decision.report
        null
      }
      is FeatureTaskRuntimePlanningStopDecision.Blocked -> {
        persistPlanningStopBlock(phaseId, decision.reason)
        decision.reason
      }
    }
  }

  private fun resolvePlanningStop(planOutput: FeatureTaskRuntimePhaseOutput): FeatureTaskRuntimePlanningStopDecision =
    planningStopper.resolve(
      request = request,
      completedOutput = planOutput,
      completedPhaseIds = state.completedPhaseIds(),
      resolvedBranch = resolvedBranch,
      specSource = specSource,
    )

  private fun persistPlanningStopBlock(phaseId: String, reason: String) {
    val resolvedAgentId = FeatureTaskRuntimeAgentResolver.resolve(
      phaseId = phaseId,
      assignment = request.agentAssignment,
      invokedAgentId = request.invokedAgentId,
    ).resolvedAgentId
    recorder.recordPhaseState(
      FeatureTaskRuntimePhaseStateRequest(
        workflowId = request.workflowId,
        phaseId = phaseId,
        status = STATUS_BLOCKED,
        attemptCount = 1,
        resolvedAgentId = resolvedAgentId,
        finished = false,
        outputArtifact = null,
        blockedReason = reason,
      ),
      request.dbPathOverride,
    )
    observability.blocked(phaseId, resolvedAgentId, 1, reason)
  }

  fun report(): FeatureTaskRuntimeRunReport {
    val branch = resolvedBranch
      ?: recorder.loadResolvedBranch(request.workflowId, request.dbPathOverride)?.branch
    return decomposed ?: blocked?.let { report ->
      if (report.resolvedBranch == null && branch != null) report.copy(resolvedBranch = branch) else report
    } ?: FeatureTaskRuntimeRunReport.Completed(
      issueKey = request.issueKey,
      workflowId = request.workflowId,
      featureSize = request.runInvariants.featureSize.name,
      completedPhaseIds = state.completedPhaseIds(),
      resolvedBranch = branch,
    )
  }

  private fun establishBranchIfNeeded(phaseId: String): String? {
    if (!isFileMutating(phaseId)) {
      return null
    }
    val setup = branchSetupRunner.ensureFeatureBranch(request, observability)
    return setup.blockedReason?.also { reason -> persistBranchSetupBlock(phaseId, reason) } ?: run {
      resolvedBranch = requireNotNull(setup.establishedBranch)
      clearRecoveredBranchSetupBlock(phaseId)
      null
    }
  }

  private fun clearRecoveredBranchSetupBlock(phaseId: String) {
    if (!state.hasBranchSetupBlock(phaseId)) {
      return
    }
    state.clearBranchSetupBlock(phaseId)
  }

  private fun persistBranchSetupBlock(phaseId: String, reason: String) {
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

  private fun blockAt(phaseId: String, reason: String) {
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

  private fun blockOnCapExhaustion(phaseId: String, transition: FeatureTaskRuntimeNextPhase.TerminalBlock) {
    val unresolvedFindings = if (transition.loopId == FeatureTaskRuntimePhaseWorkflowDefinition.AUDIT_GAP_LOOP_ID) {
      emptyList()
    } else {
      state.unresolvedReviewFindings(phaseId)
    }
    val unmetCriteria = if (transition.loopId == FeatureTaskRuntimePhaseWorkflowDefinition.AUDIT_GAP_LOOP_ID) {
      state.unmetAuditCriteria(phaseId)
    } else {
      emptyList()
    }
    val reason = capExhaustionReason(
      transition.loopId,
      transition.edgeIteration,
      transition.unresolvedVerdict,
      unresolvedFindings,
      unmetCriteria,
    )
    val resolvedAgent = FeatureTaskRuntimeAgentResolver.resolve(
      phaseId = phaseId,
      assignment = request.agentAssignment,
      invokedAgentId = request.invokedAgentId,
    )
    val run = PhaseRun(
      phaseId = phaseId,
      declaration = phaseDeclaration(phaseId, request.runInvariants.featureSize),
      resolvedAgent = resolvedAgent,
      modelDirective = FeatureTaskRuntimeModelResolver.resolve(
        phaseId,
        resolvedAgent.resolvedAgentId,
        request.modelAssignment,
      ),
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
    )
    blockAt(phaseId, reason)
  }

  private fun remediationCheckpointMessage(branch: String): String =
    "chore(skill-bill): remediation checkpoint on '$branch' before mutating-phase re-entry"

  private fun remediationCheckpointBlockedReason(branch: String, error: String): String =
    "Feature-task-runtime could not establish a remediation checkpoint on the feature branch '$branch' " +
      "before re-entering a mutating phase" + (if (error.isBlank()) "." else " ($error).") +
      " Refusing to re-enter a mutating phase on a dirty, non-reconcilable tree."

  private fun capExhaustionReason(
    loopId: String,
    edgeIteration: Int,
    verdict: FeatureTaskRuntimeVerdict,
    unresolvedFindings: List<FeatureTaskRuntimeReviewFinding> = emptyList(),
    unmetCriteria: List<String> = emptyList(),
  ): String {
    val findingsSuffix = if (unresolvedFindings.isEmpty()) {
      ""
    } else {
      " Unresolved findings: " +
        unresolvedFindings.joinToString("; ") { "[${it.severity.wireValue}] ${it.message}" } + "."
    }
    val criteriaSuffix = if (unmetCriteria.isEmpty()) {
      ""
    } else {
      " Unmet criteria: " + unmetCriteria.joinToString("; ") + "."
    }
    return "Backward-edge loop '$loopId' exhausted its per-edge cap after $edgeIteration iteration(s) with the " +
      "verdict '${verdict.wireValue}' still unresolved; the run blocks rather than re-entering past the cap." +
      findingsSuffix + criteriaSuffix
  }

  private sealed interface PhaseSettlement {
    private data object Stopped : PhaseSettlement
    private data class Completed(val phaseId: String, val verdict: FeatureTaskRuntimeVerdict) : PhaseSettlement

    val completedPhaseId: String? get() = (this as? Completed)?.phaseId
    val completedVerdict: FeatureTaskRuntimeVerdict? get() = (this as? Completed)?.verdict

    companion object {
      fun stop(): PhaseSettlement = Stopped
      fun completed(phaseId: String, verdict: FeatureTaskRuntimeVerdict): PhaseSettlement = Completed(phaseId, verdict)
    }
  }

  private data class PendingReentry(
    val phaseId: String,
    val loopId: String,
    val edgeIteration: Int,
    val drivingVerdict: FeatureTaskRuntimeVerdict,
    val reentryGapCriteria: List<String> = emptyList(),
    val auditRepairPlan: FeatureTaskRuntimeAuditRepairPlan? = null,
    val auditRepairState: FeatureTaskRuntimeAuditRepairState? = null,
  )

  @Suppress("LongParameterList")
  private fun runPhase(
    phaseId: String,
    request: FeatureTaskRuntimeRunRequest,
    state: FeatureTaskRuntimeRunState,
    observability: FeatureTaskRuntimeRunObservability,
    specSource: SpecSource,
    reentry: PendingReentry?,
    phaseTokenAccumulator: MutableMap<String, Pair<Int, Int>>? = null,
  ): PhaseOutcome {
    val completedReviewBudgetOutput = completedReviewBudgetOutput(phaseId, state)
    val resolvedAgent = FeatureTaskRuntimeAgentResolver.resolve(
      phaseId = phaseId,
      assignment = request.agentAssignment,
      invokedAgentId = request.invokedAgentId,
    )
    val declaration = phaseDeclaration(phaseId, request.runInvariants.featureSize).let { declaration ->
      if (phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT &&
        reentry?.loopId == FeatureTaskRuntimePhaseWorkflowDefinition.AUDIT_GAP_LOOP_ID
      ) {
        declaration.copy(
          consumedUpstreamPhaseIds = listOf(
            FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PREPLAN,
            FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN,
          ),
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
      request = request,
      specSource = specSource,
      reentry = reentry,
    )
    completedReviewBudgetOutput?.let { output ->
      if (isGoalContinuationRun(request) && reentry != null) {
        val iteration = state.nextIteration(phaseId)
        val phaseState = phaseStateRequest(
          run,
          iteration,
          STATUS_COMPLETED,
          finished = true,
          outputArtifact = output.payload,
        )
        state.reserveReviewPass(phaseState.reviewPassNumber)
        recorder.recordCompletedPhase(phaseState, request.dbPathOverride)
        observability.completed(phaseId, resolvedAgent.resolvedAgentId, iteration)
        return PhaseOutcome.completed(FeatureTaskRuntimePhaseOutput(phaseId, iteration, output.payload))
      }
      return PhaseOutcome.completed(output)
    }
    preLaunchBlock(run, state, observability)?.let { return it }
    return when (val prepared = prepareGoalReviewRun(run, observability)) {
      is GoalReviewRunReady -> runPhaseAttempts(prepared.run, state, observability, phaseTokenAccumulator)
      GoalReviewRunPreparation.CarryForward -> settleCarriedForwardGoalReview(
        run = run,
        state = state,
        observability = observability,
      )
      GoalReviewRunPreparation.Blocked -> PhaseOutcome.blocked(
        "Goal-subtask review preparation could not establish the exact durable review scope.",
      )
    }
  }

  private fun prepareGoalReviewRun(
    run: PhaseRun,
    observability: FeatureTaskRuntimeRunObservability,
  ): GoalReviewRunPreparation = if (isGoalReviewRun(run)) {
    reserveGoalReviewRun(run, observability)
  } else {
    GoalReviewRunReady(run)
  }

  private fun reserveGoalReviewRun(
    run: PhaseRun,
    observability: FeatureTaskRuntimeRunObservability,
  ): GoalReviewRunPreparation = runCatching {
    goalContinuationRecorder.reserveGoalReviewPass(run.request.workflowId, run.request.dbPathOverride)
  }.fold(
    onSuccess = { reservation ->
      when (reservation) {
        GoalSubtaskReviewPassReservation.MissingState -> blockedGoalReviewRun(
          run,
          observability,
          "Goal-subtask review state is missing; review_base_sha must be captured before implementation " +
            "and cannot be substituted.",
        )
        is GoalSubtaskReviewPassCarryForward -> GoalReviewRunPreparation.CarryForward
        is GoalSubtaskReviewPassInFlight,
        is GoalSubtaskReviewPassReserved,
        -> buildGoalReviewRun(run, observability)
      }
    },
    onFailure = { error ->
      blockedGoalReviewRun(
        run,
        observability,
        goalReviewPreparationFailure("reservation", error),
        goalReviewPreparationDisposition(error),
      )
    },
  )

  private fun buildGoalReviewRun(
    run: PhaseRun,
    observability: FeatureTaskRuntimeRunObservability,
  ): GoalReviewRunPreparation = runCatching {
    goalContinuationRecorder.buildGoalReviewInput(
      workflowId = run.request.workflowId,
      gitOperations = phaseGates.gitOperations,
      repoRoot = run.request.repoRoot,
      dbOverride = run.request.dbPathOverride,
    )
  }.fold(
    onSuccess = { prepared ->
      when (prepared) {
        GoalSubtaskReviewInputPreparation.MissingState -> {
          blockedGoalReviewRun(run, observability, "Goal-subtask review state disappeared before review launch.")
        }
        is GoalSubtaskReviewInputBlocked -> {
          blockedGoalReviewRun(run, observability, prepared.reason)
        }
        is GoalSubtaskReviewInputReady ->
          GoalReviewRunReady(run.copy(goalReviewInput = prepared.input))
      }
    },
    onFailure = { error ->
      blockedGoalReviewRun(
        run,
        observability,
        goalReviewPreparationFailure("input persistence", error),
        goalReviewPreparationDisposition(error),
      )
    },
  )

  private fun goalReviewPreparationFailure(stage: String, error: Throwable): String {
    val location = error.stackTrace.firstOrNull { frame -> frame.className.startsWith("skillbill.") }
      ?.let { frame -> " at ${frame.className}.${frame.methodName}:${frame.lineNumber}" }
      .orEmpty()
    return "Goal-subtask review $stage failed$location: ${error.message.orEmpty()}"
  }

  private fun goalReviewPreparationDisposition(error: Throwable): FeatureTaskRuntimeFailureDisposition =
    if ("[SQLITE_BUSY]" in error.message.orEmpty()) {
      FeatureTaskRuntimeFailureDisposition.RETRYABLE
    } else {
      FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION
    }

  private fun blockedGoalReviewRun(
    run: PhaseRun,
    observability: FeatureTaskRuntimeRunObservability,
    reason: String,
    failureDisposition: FeatureTaskRuntimeFailureDisposition = FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION,
  ): GoalReviewRunPreparation {
    blockAndPersist(run, 1, reason, observability, failureDisposition = failureDisposition)
    return GoalReviewRunPreparation.Blocked
  }

  private fun settleCarriedForwardGoalReview(
    run: PhaseRun,
    state: FeatureTaskRuntimeRunState,
    observability: FeatureTaskRuntimeRunObservability,
  ): PhaseOutcome {
    val output = runCatching {
      goalContinuationRecorder.lastGoalReviewResult(run.request.workflowId, run.request.dbPathOverride)
    }.getOrElse { error ->
      return blockAndPersist(
        run,
        state.nextIteration(run.phaseId),
        "Goal-subtask review pass budget is exhausted but its durable raw review result is malformed: " +
          error.message.orEmpty(),
        observability,
      )
    }
      ?: return blockAndPersist(
        run,
        state.nextIteration(run.phaseId),
        "Goal-subtask review pass budget is exhausted but its durable raw review result is missing.",
        observability,
      )
    runCatching {
      outputValidator.validateAndReadPhaseOutput(output, sourceLabel = run.phaseId)
    }.getOrElse { error ->
      return blockAndPersist(
        run,
        state.nextIteration(run.phaseId),
        "Goal-subtask review pass budget is exhausted but its durable raw review result is malformed: " +
          error.message.orEmpty(),
        observability,
      )
    }
    val iteration = state.nextIteration(run.phaseId)
    val phaseState = phaseStateRequest(run, iteration, STATUS_COMPLETED, finished = true, outputArtifact = output)
    state.reserveReviewPass(phaseState.reviewPassNumber)
    recorder.recordCompletedPhase(phaseState, run.request.dbPathOverride)
    observability.completed(run.phaseId, run.resolvedAgent.resolvedAgentId, iteration)
    return PhaseOutcome.completed(FeatureTaskRuntimePhaseOutput(run.phaseId, iteration, output))
  }

  private fun preLaunchBlock(
    run: PhaseRun,
    state: FeatureTaskRuntimeRunState,
    observability: FeatureTaskRuntimeRunObservability,
  ): PhaseOutcome? {
    val persisted = state.persistedBlockedReason(run.phaseId)?.let { persistedReason ->
      val nextIteration = state.nextIteration(run.phaseId)
      val durable = state.recordFor(run.phaseId)
      val retryReviewPreparation = isRetryableGoalReviewPreparation(run.phaseId, persistedReason) ||
        state.legacyReviewPreparationRetryConsumedBudget(run.phaseId, persistedReason)
      if (retryReviewPreparation) {
        state.restartAttemptBudget(run.phaseId)
      }
      if (shouldRetryPersistedBlock(run.phaseId, durable, retryReviewPreparation)) {
        return@let null
      }
      val reason = persistedReason.ifBlank {
        "Phase '${run.phaseId}' is durably blocked from a prior run; the runtime re-blocks rather than relaunching."
      }
      PreLaunchBlock(nextIteration, reason, durable)
    }
    val invalidPlanningContext = if (
      run.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT &&
      run.reentry?.loopId == FeatureTaskRuntimePhaseWorkflowDefinition.AUDIT_GAP_LOOP_ID
    ) {
      state.auditGapPlanningContextError()?.let { reason -> PreLaunchBlock(state.nextIteration(run.phaseId), reason) }
    } else {
      null
    }
    val missing = persisted ?: invalidPlanningContext
      ?: missingUpstream(run.declaration, state.outputs())?.let { missingIds ->
        PreLaunchBlock(
          1,
          "Phase '${run.phaseId}' requires upstream output(s) ${missingIds.joinToString()} that are not " +
            "present; the runtime blocks rather than launching the phase blind.",
        )
      }
    return missing?.let { preLaunch ->
      val durable = preLaunch.durableRecord
      blockAndPersist(
        run,
        preLaunch.attemptCount,
        preLaunch.reason,
        observability,
        loopId = durable?.loopId,
        edgeIteration = durable?.edgeIteration,
        failureDisposition = durable?.failureDisposition
          ?: FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION,
        fileManifest = durable?.let {
          FeatureTaskRuntimePhaseFileManifest(it.fileManifestBefore, it.fileManifestAfter)
        },
        outputArtifact = durable?.outputArtifact,
      )
    }
  }

  private fun isRetryableGoalReviewPreparation(phaseId: String, reason: String): Boolean {
    if (phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW) return false
    val legacyDatabaseContention =
      reason.startsWith("Goal-subtask review state or durable raw evidence is malformed:") &&
        "[SQLITE_BUSY]" in reason
    return legacyDatabaseContention ||
      "[SQLITE_BUSY]" in reason && (
        reason.startsWith("Goal-subtask review reservation failed") ||
          reason.startsWith("Goal-subtask review input persistence failed")
        )
  }

  private fun shouldRetryPersistedBlock(
    phaseId: String,
    durable: FeatureTaskRuntimePhaseRecord?,
    retryReviewPreparation: Boolean,
  ): Boolean = retryReviewPreparation || durable?.failureDisposition?.retryOnResume
    ?: FeatureTaskRuntimeFixLoopPolicy.participatesInFixLoop(phaseId)

  private fun runPhaseAttempts(
    run: PhaseRun,
    state: FeatureTaskRuntimeRunState,
    observability: FeatureTaskRuntimeRunObservability,
    phaseTokenAccumulator: MutableMap<String, Pair<Int, Int>>? = null,
  ): PhaseOutcome {
    val agentId = run.resolvedAgent.resolvedAgentId
    var iteration = state.nextIteration(run.phaseId)
    val budgetBaseOffset = iteration - state.fixLoopIterationFor(run.phaseId, iteration)
    FeatureTaskRuntimeFixLoopPolicy
      .blockReasonIfBudgetExhausted(run.phaseId, iteration - budgetBaseOffset)
      ?.let { reason -> return blockAndPersistInPhase(run, iteration, reason, observability) }
    observability.started(
      run.phaseId,
      agentId,
      iteration,
      iteration > 1 || state.hasPriorRecord(run.phaseId),
      run.modelDirective,
    )
    var outcome: PhaseOutcome? = null
    var priorSchemaFailure: String? = null
    while (outcome == null) {
      val attempt = attemptOnce(run, state, iteration, observability, priorSchemaFailure, phaseTokenAccumulator)
      val decision = FeatureTaskRuntimeFixLoopPolicy.decideAfterFailure(run.phaseId, iteration - budgetBaseOffset)
      outcome = attempt.settledOutcome
        ?: when (decision) {
          is FeatureTaskRuntimeFixLoopDecision.Retry -> {
            iteration += 1
            priorSchemaFailure = attempt.schemaInvalidReason
            observability.fixLoopIteration(run.phaseId, agentId, iteration, decision.fixLoopIteration)
            null
          }
          is FeatureTaskRuntimeFixLoopDecision.Block -> blockAndPersistInPhase(
            run,
            iteration,
            withSchemaGateDetail(decision.blockedReason, requireNotNull(attempt.schemaInvalidReason)),
            observability,
            failureDisposition = FeatureTaskRuntimeFailureDisposition.INVALID_OUTPUT,
            fileManifest = attempt.fileManifest,
          )
        }
    }
    return outcome
  }

  @Suppress("LongParameterList")
  private fun blockAndPersist(
    run: PhaseRun,
    attemptCount: Int,
    reason: String,
    observability: FeatureTaskRuntimeRunObservability,
    loopId: String? = null,
    edgeIteration: Int? = null,
    failureDisposition: FeatureTaskRuntimeFailureDisposition = FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION,
    fileManifest: FeatureTaskRuntimePhaseFileManifest? = null,
    outputArtifact: String? = null,
  ): PhaseOutcome {
    val phaseState = FeatureTaskRuntimePhaseStateRequest(
      workflowId = run.request.workflowId,
      phaseId = run.phaseId,
      status = STATUS_BLOCKED,
      attemptCount = attemptCount.coerceAtLeast(1),
      resolvedAgentId = run.resolvedAgent.resolvedAgentId,
      finished = false,
      outputArtifact = outputArtifact,
      blockedReason = reason,
      failureDisposition = failureDisposition,
      fileManifestBefore = fileManifest?.before.orEmpty(),
      fileManifestAfter = fileManifest?.after.orEmpty(),
      fileManifestIntroduced = fileManifest?.introduced.orEmpty(),
      loopId = loopId,
      edgeIteration = edgeIteration,
      reviewPassNumber = reviewPassNumber(run, state),
    )
    state.reserveReviewPass(phaseState.reviewPassNumber)
    recorder.recordPhaseState(
      phaseState,
      run.request.dbPathOverride,
    )
    observability.blocked(run.phaseId, run.resolvedAgent.resolvedAgentId, attemptCount.coerceAtLeast(1), reason)
    return PhaseOutcome.blocked(reason)
  }

  private fun blockAndPersistInPhase(
    run: PhaseRun,
    attemptCount: Int,
    reason: String,
    observability: FeatureTaskRuntimeRunObservability,
    failureDisposition: FeatureTaskRuntimeFailureDisposition = FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION,
    fileManifest: FeatureTaskRuntimePhaseFileManifest? = null,
    outputArtifact: String? = null,
  ): PhaseOutcome = blockAndPersist(
    run,
    attemptCount,
    reason,
    observability,
    loopId = run.reentry?.loopId,
    edgeIteration = run.reentry?.edgeIteration,
    failureDisposition = failureDisposition,
    fileManifest = fileManifest,
    outputArtifact = outputArtifact,
  )

  @Suppress("LongParameterList")
  private fun attemptOnce(
    run: PhaseRun,
    state: FeatureTaskRuntimeRunState,
    iteration: Int,
    observability: FeatureTaskRuntimeRunObservability,
    priorSchemaFailure: String?,
    phaseTokenAccumulator: MutableMap<String, Pair<Int, Int>>? = null,
  ): AttemptResult {
    persistPhase(run, iteration, STATUS_RUNNING, finished = false, outputArtifact = null)
    val launch = launchAndCapture(run, state, priorSchemaFailure, phaseTokenAccumulator)
    launch.infraFailureReason?.let { reason ->
      return AttemptResult.settled(
        blockAndPersistInPhase(
          run,
          iteration,
          reason,
          observability,
          failureDisposition = FeatureTaskRuntimeFailureDisposition.PROCESS_FAILURE,
          fileManifest = launch.fileManifest,
        ),
      )
    }
    val fileManifest = requireNotNull(launch.fileManifest)
    return gateOutput(
      run,
      iteration,
      requireNotNull(launch.capturedStdout),
      observability,
      fileManifest,
    )
  }

  private fun gateOutput(
    run: PhaseRun,
    iteration: Int,
    outputText: String,
    observability: FeatureTaskRuntimeRunObservability,
    fileManifest: FeatureTaskRuntimePhaseFileManifest,
  ): AttemptResult = try {
    val normalized = outputValidator.normalizePhaseOutput(outputText, sourceLabel = run.phaseId)
    settleValidatedOutput(run, iteration, normalized, observability, fileManifest)
  } catch (error: InvalidFeatureTaskRuntimePhaseOutputSchemaError) {
    schemaInvalidAttempt(run, iteration, error.reason, observability, fileManifest)
  }

  @Suppress("ReturnCount")
  private fun settleValidatedOutput(
    run: PhaseRun,
    iteration: Int,
    normalizedOutput: NormalizedFeatureTaskRuntimePhaseOutput,
    observability: FeatureTaskRuntimeRunObservability,
    fileManifest: FeatureTaskRuntimePhaseFileManifest,
  ): AttemptResult {
    val outputText = normalizedOutput.canonicalJson
    val outputMap = normalizedOutput.envelope
    mutatingReconciliationGateReason(run.phaseId, outputMap)?.let { reason ->
      return schemaInvalidAttempt(run, iteration, reason, observability, fileManifest)
    }
    val terminalAuditRepairReason = terminalAuditRepairBlockGateReason(run.phaseId, outputMap)
    terminalAuditRepairReason?.let { reason ->
      return schemaInvalidAttempt(run, iteration, reason, observability, fileManifest)
    }
    auditRepairResultGateReason(run.phaseId, outputMap)?.let { reason ->
      return schemaInvalidAttempt(run, iteration, reason, observability, fileManifest)
    }
    auditDurableLedgerGateReason(run.phaseId, outputMap)?.let { reason ->
      return schemaInvalidAttempt(run, iteration, reason, observability, fileManifest)
    }
    val repositoryFingerprint = auditRepairRepositoryFingerprint(run)?.let { result ->
      if (!result.ok) {
        return AttemptResult.settled(
          blockAndPersistInPhase(
            run,
            iteration,
            "Audit-repair repository fingerprinting failed: ${result.error}",
            observability,
            failureDisposition = FeatureTaskRuntimeFailureDisposition.PROCESS_FAILURE,
            fileManifest = fileManifest,
          ),
        )
      }
      result.value
    }
    auditRepairNonProgressReason(run, outputMap, repositoryFingerprint)?.let { reason ->
      return AttemptResult.settled(
        blockAndPersistInPhase(
          run,
          iteration,
          reason,
          observability,
          failureDisposition = FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION,
          fileManifest = fileManifest,
          outputArtifact = outputText,
        ),
      )
    }
    terminalBlockedReasonFrom(run.phaseId, outputMap)?.let { reason ->
      return terminalOutputAttempt(run, iteration, reason, outputText, outputMap, observability, fileManifest)
    }
    outputVerificationGateReason(run.phaseId, outputMap)?.let { reason ->
      return schemaInvalidAttempt(run, iteration, reason, observability, fileManifest)
    }
    return persistAcceptedOutput(
      run,
      iteration,
      normalizedOutput,
      observability,
      fileManifest,
      repositoryFingerprint,
    )
  }

  private fun auditRepairRepositoryFingerprint(run: PhaseRun) =
    if (run.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT) {
      gitOperations.repositoryFingerprint(run.request.repoRoot)
    } else {
      null
    }

  @Suppress("ReturnCount")
  private fun auditRepairNonProgressReason(
    run: PhaseRun,
    outputMap: Map<String, Any?>,
    repositoryFingerprint: String?,
  ): String? {
    if (run.phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT ||
      run.reentry?.loopId != FeatureTaskRuntimePhaseWorkflowDefinition.AUDIT_GAP_LOOP_ID
    ) {
      return null
    }
    val prior = recorder.loadAuditRepairState(request.workflowId, request.dbPathOverride) ?: return null
    val previousFingerprint = prior.repositoryFingerprint ?: return null
    val currentFingerprint = repositoryFingerprint ?: return null
    val produced = JsonSupport.anyToStringAnyMap(outputMap["produced_outputs"]).orEmpty()
    val currentPlan = produced["audit_repair_plan"]?.let {
      auditRepairPlanFromWire(it, "audit.produced_outputs.audit_repair_plan")
    }
    val currentGapIds = currentPlan?.gaps.orEmpty().mapTo(linkedSetOf()) { it.gapId }
    if (currentGapIds.isEmpty()) return null
    val latestPlanItemIds = prior.acceptedPlans.last().gaps
      .flatMap { it.repairItems }
      .mapTo(linkedSetOf()) { it.repairItemId }
    val currentPlanItemIds = requireNotNull(currentPlan).gaps
      .flatMap { it.repairItems }
      .mapTo(linkedSetOf()) { it.repairItemId }
    if ((currentPlanItemIds - latestPlanItemIds).isNotEmpty()) return null
    val resolvedCount = prior.repairItemResults.count { it.repairItemId in latestPlanItemIds }
    return detectAuditRepairNonProgress(
      previousGapIds = prior.unresolvedGapLedger.unresolvedGaps.mapTo(linkedSetOf()) { it.gapId },
      currentGapIds = currentGapIds,
      previousRepositoryFingerprint = previousFingerprint,
      currentRepositoryFingerprint = currentFingerprint,
      newlyResolvedRepairItemCount = resolvedCount,
    ).reason
  }

  private fun terminalOutputAttempt(
    run: PhaseRun,
    iteration: Int,
    reason: String,
    outputText: String,
    outputMap: Map<String, Any?>,
    observability: FeatureTaskRuntimeRunObservability,
    fileManifest: FeatureTaskRuntimePhaseFileManifest,
  ): AttemptResult {
    val disposition = FeatureTaskRuntimePhaseSafetyPolicy.dispositionForTerminalOutput(run.phaseId, outputMap)
    return if (
      disposition.retryOnResume &&
      FeatureTaskRuntimeFixLoopPolicy.participatesInFixLoop(run.phaseId)
    ) {
      AttemptResult.schemaInvalid(reason, fileManifest)
    } else {
      AttemptResult.settled(
        blockAndPersistInPhase(
          run,
          iteration,
          reason,
          observability,
          failureDisposition = disposition,
          fileManifest = fileManifest,
          outputArtifact = outputText,
        ),
      )
    }
  }

  private fun outputVerificationGateReason(phaseId: String, outputMap: Map<String, Any?>): String? =
    reviewVerificationSignalGateReason(phaseId, outputMap)
      ?: auditVerificationSignalGateReason(phaseId, outputMap)

  @Suppress("ReturnCount", "CyclomaticComplexMethod")
  private fun auditRepairResultGateReason(phaseId: String, outputMap: Map<String, Any?>): String? {
    val reentry = activeReentry?.takeIf {
      it.loopId == FeatureTaskRuntimePhaseWorkflowDefinition.AUDIT_GAP_LOOP_ID
    } ?: return null
    if (!FeatureTaskRuntimePhaseWorkflowDefinition.isMutatingPhase(phaseId)) return null
    val expected = reentry.auditRepairPlan?.gaps.orEmpty()
      .flatMap { it.repairItems }
      .map { it.repairItemId }
    if (expected.isEmpty()) return "Audit-gap remediation is missing its persisted audit_repair_plan."
    val results = (
      JsonSupport.anyToStringAnyMap(outputMap["produced_outputs"])
        ?.get("repair_item_results") as? List<*>
      ).orEmpty()
    val resultMaps = results.mapNotNull(JsonSupport::anyToStringAnyMap)
    val actual = resultMaps.mapNotNull { it["repair_item_id"] as? String }
    val blocked = outputMap["status"] == STATUS_BLOCKED
    val identifiersInvalid = actual.size != resultMaps.size || actual.size != actual.toSet().size ||
      if (blocked) !expected.toSet().containsAll(actual) else actual.toSet() != expected.toSet()
    if (identifiersInvalid) {
      return "Audit-gap remediation requires exact repair_item_result identifier equality; " +
        "expected=$expected actual=$actual."
    }
    val expectedOrder = expected.withIndex().associate { (index, id) -> id to index }
    val actualOrder = actual.withIndex().associate { (index, id) -> id to index }
    val planItems = reentry.auditRepairPlan?.gaps.orEmpty().flatMap { it.repairItems }
    planItems.forEach { item ->
      val itemId = item.repairItemId
      item.dependsOn.forEach { dependency ->
        if (actualOrder.getValue(dependency) >= actualOrder.getValue(itemId) ||
          expectedOrder.getValue(dependency) >= expectedOrder.getValue(itemId)
        ) {
          return "Audit-gap remediation results must follow the accepted dependency order; " +
            "'$itemId' depends on '$dependency' so '$dependency' must be reported first. Required order: $expected."
        }
      }
    }
    resultMaps.forEachIndexed { index, result ->
      auditRepairResultError(result, index)?.let { return it }
    }
    if (containsForbiddenAuditRepairDeferral(outputMap)) {
      return "Audit-gap remediation cannot assign carried repair work to a later phase."
    }
    if (blocked) {
      val unresolvable = JsonSupport.anyToStringAnyMap(
        JsonSupport.anyToStringAnyMap(outputMap["produced_outputs"])?.get("unresolvable_repair"),
      )
      val blockedItemId = unresolvable?.get("repair_item_id") as? String
      if (blockedItemId != null && blockedItemId in actual) {
        return "An unresolvable repair item cannot also report a terminal fixed or already_satisfied result."
      }
    }
    return null
  }

  private fun auditRepairResultError(result: Map<String, Any?>, index: Int): String? {
    val label = (result["repair_item_id"] as? String)?.takeIf(String::isNotBlank)
      ?: "repair_item_results[$index]"
    val expectedKeys = setOf(
      "repair_item_id",
      "outcome",
      "changed_paths_or_symbols",
      "executed_verification",
      "result_evidence",
    )
    val missing = expectedKeys - result.keys
    val unknown = result.keys - expectedKeys
    val decodeFailure = runCatching { repairItemResultFromWire(result, "repair_item_results[$index]") }
      .exceptionOrNull()
    return when {
      missing.isNotEmpty() || unknown.isNotEmpty() ->
        "Audit repair item '$label' has invalid fields; missing=${missing.sorted()} unknown=${unknown.sorted()}."
      result["outcome"] !in setOf("fixed", "already_satisfied") ->
        "Audit repair item '$label' outcome must be 'fixed' or 'already_satisfied', was '${result["outcome"]}'."
      hasNoNonBlankStrings(result["changed_paths_or_symbols"]) ->
        "Audit repair item '$label' changed_paths_or_symbols must contain at least one nonblank path or symbol, " +
          "for example [\"src/main/Example.kt:Example\"]."
      hasNoNonBlankStrings(result["executed_verification"]) ->
        "Audit repair item '$label' executed_verification must contain at least one nonblank command and result, " +
          "for example [\"./gradlew :runtime-domain:test --tests *ExampleTest* passed\"]."
      decodeFailure != null ->
        "Audit repair item '$label' is not contract-safe: ${decodeFailure.diagnosticMessage()}"
      result["outcome"] == "already_satisfied" && !alreadySatisfiedEvidenceIsDistinct(result) ->
        "Audit repair item '$label' reports 'already_satisfied', so changed_paths_or_symbols and " +
          "executed_verification must each be nonempty and must not be the same list: name what already " +
          "satisfies the item and, separately, the verification you ran to confirm it."
      result.values.any(::containsForbiddenAuditRepairDeferral) ->
        "Audit repair item '$label' cannot defer carried work to a later phase."
      else -> null
    }
  }

  private fun hasNoNonBlankStrings(value: Any?): Boolean =
    (value as? List<*>)?.filterIsInstance<String>()?.none(String::isNotBlank) != false

  private fun alreadySatisfiedEvidenceIsDistinct(result: Map<String, Any?>): Boolean {
    val repositoryEvidence = (result["changed_paths_or_symbols"] as? List<*>)
      .orEmpty().filterIsInstance<String>().filter(String::isNotBlank)
    val verificationEvidence = (result["executed_verification"] as? List<*>)
      .orEmpty().filterIsInstance<String>().filter(String::isNotBlank)
    return repositoryEvidence.isNotEmpty() && verificationEvidence.isNotEmpty() &&
      repositoryEvidence.toSet() != verificationEvidence.toSet()
  }

  @Suppress("ReturnCount")
  private fun auditDurableLedgerGateReason(phaseId: String, outputMap: Map<String, Any?>): String? {
    if (phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT) return null
    val repairState = recorder.loadAuditRepairState(request.workflowId, request.dbPathOverride)
    val priorIds = repairState?.unresolvedGapLedger?.unresolvedGaps.orEmpty()
      .mapTo(linkedSetOf()) { it.gapId }
    if (priorIds.isEmpty()) return null
    val produced = JsonSupport.anyToStringAnyMap(outputMap["produced_outputs"]).orEmpty()
    val dispositions = (produced["prior_gap_dispositions"] as? List<*>).orEmpty()
      .mapNotNull(JsonSupport::anyToStringAnyMap)
    val dispositionIds = dispositions.mapNotNull { it["gap_id"] as? String }
    if (dispositionIds.size != dispositions.size || dispositionIds.toSet() != priorIds) {
      return "The following audit must disposition every durable unresolved gap exactly once; " +
        "expected=$priorIds actual=$dispositionIds."
    }
    dispositions.forEachIndexed { index, disposition ->
      val decodeFailure = runCatching {
        priorGapDispositionFromWire(disposition, "prior_gap_dispositions[$index]")
      }.exceptionOrNull()
      if (decodeFailure != null) {
        val gapId = disposition["gap_id"] as? String ?: "prior_gap_dispositions[$index]"
        return "Prior gap disposition '$gapId' is not contract-safe: ${decodeFailure.diagnosticMessage()}"
      }
    }
    val recurring = dispositions.filter { it["status"] == "recurring" }
    if (recurring.size + dispositions.count { it["status"] == "resolved" } != dispositions.size) {
      return "Prior gap dispositions must be resolved or recurring."
    }
    if (outputMap["verdict"] == "satisfied" && recurring.isNotEmpty()) {
      return "An audit cannot report satisfied while the durable unresolved-gap ledger remains non-empty."
    }
    return null
  }

  private fun Throwable.diagnosticMessage(): String =
    message?.takeIf(String::isNotBlank) ?: this::class.simpleName.orEmpty().ifBlank { "unknown decode failure" }

  @Suppress("ReturnCount")
  private fun terminalAuditRepairBlockGateReason(phaseId: String, outputMap: Map<String, Any?>): String? {
    if (outputMap["status"] != STATUS_BLOCKED) return null
    val reentry = activeReentry?.takeIf {
      it.loopId == FeatureTaskRuntimePhaseWorkflowDefinition.AUDIT_GAP_LOOP_ID
    } ?: return null
    if (!FeatureTaskRuntimePhaseWorkflowDefinition.isMutatingPhase(phaseId)) return null
    val produced = JsonSupport.anyToStringAnyMap(outputMap["produced_outputs"]).orEmpty()
    val block = JsonSupport.anyToStringAnyMap(produced["unresolvable_repair"])
      ?: return "Blocked audit remediation must persist unresolvable_repair with gap_id and repair_item_id."
    val gapId = block["gap_id"] as? String
    val itemId = block["repair_item_id"] as? String
    val evidence = runCatching {
      auditEvidenceFromWire(block["evidence"], "unresolvable_repair.evidence")
    }.getOrNull()
    if (gapId.isNullOrBlank() || itemId.isNullOrBlank() || evidence == null) {
      return "Blocked audit remediation requires nonblank gap_id, repair_item_id, and structured evidence."
    }
    val owningGap = reentry.auditRepairPlan?.gaps.orEmpty().firstOrNull { it.gapId == gapId }
      ?: return "Blocked audit remediation references unknown gap_id '$gapId'."
    val carriedItems = owningGap.repairItems.map { it.repairItemId }
    return if (itemId !in carriedItems) {
      "Blocked audit remediation references repair_item_id '$itemId' outside gap '$gapId'."
    } else {
      null
    }
  }

  private fun containsForbiddenAuditRepairDeferral(value: Any?): Boolean = when (value) {
    is String -> listOf(
      Regex(FORWARD_DEFERRAL_PATTERN, RegexOption.IGNORE_CASE),
      Regex(REVERSE_DEFERRAL_PATTERN, RegexOption.IGNORE_CASE),
    ).any { it.containsMatchIn(value) }
    is List<*> -> value.any(::containsForbiddenAuditRepairDeferral)
    is Map<*, *> -> value.values.any(::containsForbiddenAuditRepairDeferral)
    else -> false
  }

  private fun persistAcceptedOutput(
    run: PhaseRun,
    iteration: Int,
    normalizedOutput: NormalizedFeatureTaskRuntimePhaseOutput,
    observability: FeatureTaskRuntimeRunObservability,
    fileManifest: FeatureTaskRuntimePhaseFileManifest,
    repositoryFingerprint: String?,
  ): AttemptResult {
    val outputText = normalizedOutput.canonicalJson
    val outputMap = normalizedOutput.envelope
    if (isGoalReviewRun(run)) {
      persistGoalReviewCompletion(run, iteration, outputText, outputMap, observability, fileManifest)?.let { outcome ->
        return AttemptResult.settled(outcome)
      }
    } else {
      val persisted = recorder.recordCompletedPhase(
        phaseStateRequest(
          run,
          iteration,
          STATUS_COMPLETED,
          finished = true,
          outputArtifact = outputText,
          fileManifest = fileManifest,
          normalizedOutput = normalizedOutput,
          repositoryFingerprint = repositoryFingerprint,
        ),
        run.request.dbPathOverride,
      )
      if (!persisted) {
        return AttemptResult.settled(
          blockAndPersistInPhase(
            run,
            iteration,
            "Validated phase output could not be persisted to the authoritative workflow record.",
            observability,
            failureDisposition = FeatureTaskRuntimeFailureDisposition.PROCESS_FAILURE,
            fileManifest = fileManifest,
          ),
        )
      }
    }
    observability.completedEvent(run.phaseId, run.resolvedAgent.resolvedAgentId, iteration)
    return AttemptResult.settled(
      PhaseOutcome.completed(FeatureTaskRuntimePhaseOutput(run.phaseId, iteration, outputText, normalizedOutput)),
    )
  }

  private fun persistGoalReviewCompletion(
    run: PhaseRun,
    iteration: Int,
    outputText: String,
    outputMap: Map<String, Any?>,
    observability: FeatureTaskRuntimeRunObservability,
    fileManifest: FeatureTaskRuntimePhaseFileManifest,
  ): PhaseOutcome? {
    val findings = GoalSubtaskReviewSummaryReducer.fromOutput(outputMap)
    val outcome = GoalSubtaskReviewSummaryReducer.outcomeFor(outputMap, findings)
    val completed = runCatching {
      recorder.completeGoalReviewPhase(
        completion = GoalReviewPhaseCompletionRequest(
          phaseState = phaseStateRequest(
            run,
            iteration,
            STATUS_COMPLETED,
            finished = true,
            outputArtifact = outputText,
            fileManifest = fileManifest,
          ),
          verdict = outcome.verdict,
          unresolvedFindingCount = outcome.unresolvedFindingCount,
          findings = findings,
          rawReviewResult = outputText,
        ),
        dbOverride = run.request.dbPathOverride,
      )
    }.getOrElse { error ->
      return blockAndPersistInPhase(
        run,
        iteration,
        "Goal-subtask review could not atomically persist its pass and completed phase: " + error.message.orEmpty(),
        observability,
        fileManifest = fileManifest,
      )
    }
    return if (completed) {
      null
    } else {
      blockAndPersistInPhase(
        run,
        iteration,
        "Goal-subtask review could not atomically persist its reserved pass and completed phase.",
        observability,
        fileManifest = fileManifest,
      )
    }
  }

  private fun isGoalReviewRun(run: PhaseRun): Boolean =
    run.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW && isGoalContinuationRun(run.request)

  private fun schemaInvalidAttempt(
    run: PhaseRun,
    iteration: Int,
    reason: String,
    observability: FeatureTaskRuntimeRunObservability,
    fileManifest: FeatureTaskRuntimePhaseFileManifest,
  ): AttemptResult = if (
    run.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW && isGoalContinuationRun(run.request)
  ) {
    AttemptResult.settled(
      blockAndPersistInPhase(
        run,
        iteration,
        "Goal-subtask review output failed schema validation after its reserved pass; " +
          "refusing an unaccounted relaunch. $reason",
        observability,
        fileManifest = fileManifest,
      ),
    )
  } else {
    AttemptResult.schemaInvalid(reason, fileManifest)
  }

  private fun persistPhase(
    run: PhaseRun,
    iteration: Int,
    status: String,
    finished: Boolean,
    outputArtifact: String?,
    fileManifest: FeatureTaskRuntimePhaseFileManifest? = null,
  ) {
    val phaseState = phaseStateRequest(run, iteration, status, finished, outputArtifact, fileManifest)
    state.reserveReviewPass(phaseState.reviewPassNumber)
    recorder.recordPhaseState(
      phaseState,
      run.request.dbPathOverride,
    )
  }

  private fun phaseStateRequest(
    run: PhaseRun,
    iteration: Int,
    status: String,
    finished: Boolean,
    outputArtifact: String?,
    fileManifest: FeatureTaskRuntimePhaseFileManifest? = null,
    normalizedOutput: NormalizedFeatureTaskRuntimePhaseOutput? = null,
    repositoryFingerprint: String? = null,
  ): FeatureTaskRuntimePhaseStateRequest = FeatureTaskRuntimePhaseStateRequest(
    workflowId = run.request.workflowId,
    phaseId = run.phaseId,
    status = status,
    attemptCount = iteration,
    resolvedAgentId = run.resolvedAgent.resolvedAgentId,
    finished = finished,
    outputArtifact = outputArtifact,
    normalizedOutput = normalizedOutput,
    repositoryFingerprint = repositoryFingerprint,
    fileManifestBefore = fileManifest?.before.orEmpty(),
    fileManifestAfter = fileManifest?.after.orEmpty(),
    fileManifestIntroduced = fileManifest?.introduced.orEmpty(),
    loopId = run.reentry?.loopId,
    edgeIteration = run.reentry?.edgeIteration,
    reviewPassNumber = reviewPassNumber(run, state),
  )

  private fun reviewPassNumber(run: PhaseRun, state: FeatureTaskRuntimeRunState): Int? {
    if (run.phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW) return null
    val currentPass = state.currentReviewPassNumber()
    return if (currentPass == 2 || state.outputCountFor(run.phaseId) > 0) 2 else currentPass ?: 1
  }

  private fun completedReviewBudgetOutput(
    phaseId: String,
    state: FeatureTaskRuntimeRunState,
  ): FeatureTaskRuntimePhaseOutput? {
    if (phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW) return null
    val budgetCompleted = state.completedReviewPassNumber() == 2 || state.outputCountFor(phaseId) >= 2
    return state.outputFor(phaseId)?.takeIf { budgetCompleted }
  }

  @Suppress("ReturnCount")
  private fun launchAndCapture(
    run: PhaseRun,
    state: FeatureTaskRuntimeRunState,
    priorSchemaFailure: String? = null,
    phaseTokenAccumulator: MutableMap<String, Pair<Int, Int>>? = null,
  ): LaunchResult {
    val before = gitOperations.worktreeStatus(run.request.repoRoot)
    if (!before.ok) {
      return LaunchResult.infraFailure(
        "Feature-task-runtime phase '${run.phaseId}' could not capture its before-file manifest: ${before.error}",
      )
    }
    val beforeCommit = gitOperations.runtimePhaseHeadCommit(run.request.repoRoot)
    if (!beforeCommit.ok) {
      return LaunchResult.infraFailure(
        "Feature-task-runtime phase '${run.phaseId}' could not capture its before commit: ${beforeCommit.error}",
      )
    }
    val handoff = FeatureTaskRuntimeHandoffContract.assembleHandoff(
      declaration = run.declaration,
      runInvariants = run.request.runInvariants,
      recordedOutputs = state.outputs(),
      drivingVerdict = run.reentry?.drivingVerdict,
      reentryGapCriteria = auditGapCriteriaFor(run, state),
      auditRepairPlan = run.reentry?.auditRepairPlan,
      auditRepairState = run.reentry?.auditRepairState,
    )
    val briefing = FeatureTaskRuntimePhaseBriefingAssembler.assemble(handoff)
    recorder.recordPhaseBriefing(run.request.workflowId, briefing, run.request.dbPathOverride)
    val outcome = subtaskLauncher.launch(
      GoalRunnerSubtaskLaunchRequest(
        invokedAgentId = run.resolvedAgent.invokedAgentId,
        configuredAgentOverrideId = run.resolvedAgent.configuredAgentOverrideId,
        skillRunRequest = SkillRunRequest(
          issueKey = run.request.issueKey,
          repoRoot = run.request.repoRoot,
          dbPathOverride = run.request.dbPathOverride,
          timeout = run.request.timeout,
          modelOverride = run.modelDirective?.model,
          effortOverride = run.modelDirective?.effort,
          promptOverride = FeatureTaskRuntimePhasePromptComposer.compose(
            issueKey = run.request.issueKey,
            briefing = briefing,
            suppressDecomposition = isGoalContinuationRun(run.request),
            parallelReviewAgent = run.request.parallelReviewAgent
              ?.takeIf { run.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW },
            codeReviewMode = if (reviewPassNumber(run, state) == 2) {
              skillbill.workflow.model.CodeReviewExecutionMode.INLINE
            } else {
              run.request.runInvariants.codeReviewMode
            },
            reviewPassNumber = reviewPassNumber(run, state),
            goalSubtaskReviewInput = run.goalReviewInput,
            specSource = run.specSource,
            priorSchemaFailure = priorSchemaFailure,
            specReference = run.request.runInvariants.specReference,
            agentAddonSelection = run.request.agentAddonSelection,
          ),
        ),
      ),
    )
    if (outcome is AgentRunLaunchFacts && phaseTokenAccumulator != null) {
      val inputTokens = estimateTokens(briefing.briefingText)
      val outputTokens = estimateTokens(outcome.stdout)
      phaseTokenAccumulator[run.phaseId] = Pair(inputTokens, outputTokens)
    }
    val after = gitOperations.worktreeStatus(run.request.repoRoot)
    if (!after.ok) {
      return LaunchResult.infraFailure(
        "Feature-task-runtime phase '${run.phaseId}' could not capture its after-file manifest: ${after.error}",
      )
    }
    val afterCommit = gitOperations.runtimePhaseHeadCommit(run.request.repoRoot)
    if (!afterCommit.ok) {
      return LaunchResult.infraFailure(
        "Feature-task-runtime phase '${run.phaseId}' could not capture its after commit: ${afterCommit.error}",
      )
    }
    val committedPaths = gitOperations.runtimePhaseChangedPathsBetweenCommits(
      run.request.repoRoot,
      beforeCommit.value.orEmpty(),
      afterCommit.value.orEmpty(),
    )
    if (!committedPaths.ok) {
      return LaunchResult.infraFailure(
        "Feature-task-runtime phase '${run.phaseId}' could not capture committed file changes: ${committedPaths.error}",
      )
    }
    val fileManifest = FeatureTaskRuntimePhaseFileManifest(
      before = FeatureTaskRuntimePhaseSafetyPolicy.changedPaths(before.value),
      after = (
        FeatureTaskRuntimePhaseSafetyPolicy.changedPaths(after.value) +
          FeatureTaskRuntimePhaseSafetyPolicy.lineSeparatedPaths(committedPaths.value.orEmpty())
        ).distinct().sorted(),
    )
    return reconcileLaunch(run.phaseId, outcome, fileManifest)
  }

  private fun auditGapCriteriaFor(run: PhaseRun, state: FeatureTaskRuntimeRunState): List<String> {
    run.reentry
      ?.takeIf { it.loopId == FeatureTaskRuntimePhaseWorkflowDefinition.AUDIT_GAP_LOOP_ID }
      ?.reentryGapCriteria
      ?.let { return it }
    val scopedPhases = setOf(
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN,
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT,
    )
    val auditGapFired =
      state.edgeIterationCount(FeatureTaskRuntimePhaseWorkflowDefinition.AUDIT_GAP_LOOP_ID) > 0
    if (run.phaseId !in scopedPhases || !auditGapFired) {
      return emptyList()
    }
    return state.unmetAuditCriteria(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT)
  }

  private fun reconcileLaunch(
    phaseId: String,
    outcome: AgentRunLaunchOutcome,
    fileManifest: FeatureTaskRuntimePhaseFileManifest,
  ): LaunchResult = when (outcome) {
    is UnsupportedAgentRunLaunch -> LaunchResult.infraFailure(
      "Feature-task-runtime phase '$phaseId' could not launch an agent: ${outcome.reason}",
      fileManifest,
    )
    is AgentRunLaunchFacts -> infraFailureReason(phaseId, outcome)
      ?.let { LaunchResult.infraFailure(it, fileManifest) }
      ?: LaunchResult.captured(outcome.stdout, fileManifest)
  }

  private data class PhaseRun(
    val phaseId: String,
    val declaration: FeatureTaskRuntimePhaseDeclaration,
    val resolvedAgent: FeatureTaskRuntimeResolvedPhaseAgent,
    val modelDirective: PhaseModelDirective?,
    val request: FeatureTaskRuntimeRunRequest,
    val specSource: SpecSource,
    val reentry: PendingReentry? = null,
    val goalReviewInput: GoalSubtaskReviewInput? = null,
  )

  private data class PreLaunchBlock(
    val attemptCount: Int,
    val reason: String,
    val durableRecord: FeatureTaskRuntimePhaseRecord? = null,
  )

  private sealed interface LaunchResult {
    private data class Captured(
      val stdout: String,
      override val fileManifest: FeatureTaskRuntimePhaseFileManifest,
    ) : LaunchResult
    private data class InfraFailure(
      val reason: String,
      override val fileManifest: FeatureTaskRuntimePhaseFileManifest?,
    ) : LaunchResult

    val capturedStdout: String? get() = (this as? Captured)?.stdout
    val infraFailureReason: String? get() = (this as? InfraFailure)?.reason
    val fileManifest: FeatureTaskRuntimePhaseFileManifest?

    companion object {
      fun captured(stdout: String, fileManifest: FeatureTaskRuntimePhaseFileManifest): LaunchResult =
        Captured(stdout, fileManifest)
      fun infraFailure(reason: String, fileManifest: FeatureTaskRuntimePhaseFileManifest? = null): LaunchResult =
        InfraFailure(reason, fileManifest)
    }
  }

  private sealed interface AttemptResult {
    private data class Settled(val outcome: PhaseOutcome) : AttemptResult
    private data class SchemaInvalid(
      val validationReason: String,
      override val fileManifest: FeatureTaskRuntimePhaseFileManifest,
    ) : AttemptResult

    val settledOutcome: PhaseOutcome? get() = (this as? Settled)?.outcome
    val schemaInvalidReason: String? get() = (this as? SchemaInvalid)?.validationReason
    val fileManifest: FeatureTaskRuntimePhaseFileManifest? get() = (this as? SchemaInvalid)?.fileManifest

    companion object {
      fun settled(outcome: PhaseOutcome): AttemptResult = Settled(outcome)
      fun schemaInvalid(validationReason: String, fileManifest: FeatureTaskRuntimePhaseFileManifest): AttemptResult =
        SchemaInvalid(validationReason, fileManifest)
    }
  }

  private sealed interface PhaseOutcome {
    private data class Completed(val output: FeatureTaskRuntimePhaseOutput) : PhaseOutcome
    private data class Blocked(val reason: String) : PhaseOutcome

    val completedOutput: FeatureTaskRuntimePhaseOutput? get() = (this as? Completed)?.output

    val blockedReason: String? get() = (this as? Blocked)?.reason

    companion object {
      fun completed(output: FeatureTaskRuntimePhaseOutput): PhaseOutcome = Completed(output)
      fun blocked(reason: String): PhaseOutcome = Blocked(reason)
    }
  }

  private sealed interface GoalReviewRunPreparation {
    data object CarryForward : GoalReviewRunPreparation
    data object Blocked : GoalReviewRunPreparation
  }

  private data class GoalReviewRunReady(val run: PhaseRun) : GoalReviewRunPreparation
}

private const val FORWARD_DEFERRAL_PATTERN =
  "\\b(defer(?:red|ring)?|postpone(?:d|ment)?|leave|assign(?:ed|ing)?|hand(?:ed)?\\s+off|later)\\b" +
    ".{0,120}\\b(review|audit|validation|test(?:ing)?|later phase)\\b"
private const val REVERSE_DEFERRAL_PATTERN =
  "\\b(review|audit|validation|test(?:ing)?|later phase)\\b.{0,120}" +
    "\\b(will|should|must|can)\\s+(handle|fix|complete|cover|address|verify)\\b"
