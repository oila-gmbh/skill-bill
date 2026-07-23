package skillbill.application.featuretask

import skillbill.application.goalrunner.GoalSubtaskReviewSummaryReducer
import skillbill.application.model.FeatureTaskRuntimeFixLoopDecision
import skillbill.application.model.FeatureTaskRuntimePhaseLaunchBriefing
import skillbill.application.model.FeatureTaskRuntimePhaseStateRequest
import skillbill.application.model.FeatureTaskRuntimePlanningStopDecision
import skillbill.application.model.FeatureTaskRuntimeResolvedPhaseAgent
import skillbill.application.model.FeatureTaskRuntimeRunReport
import skillbill.application.model.FeatureTaskRuntimeRunRequest
import skillbill.application.workflow.repoRoot
import skillbill.config.model.PhaseModelDirective
import skillbill.contracts.JsonSupport
import skillbill.contracts.workflow.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import skillbill.error.FeatureTaskRuntimeHandoffProjectionFailureKind
import skillbill.error.FeatureTaskRuntimePhaseOrderViolationError
import skillbill.error.FeatureTaskRuntimePhaseOutputFailureKind
import skillbill.error.InvalidFeatureTaskRuntimeAuditRepairPlanSchemaError
import skillbill.error.InvalidFeatureTaskRuntimeHandoffProjectionError
import skillbill.error.InvalidFeatureTaskRuntimePhaseBriefingFramingError
import skillbill.error.InvalidFeatureTaskRuntimePhaseOutputSchemaError
import skillbill.error.InvalidFeatureTaskRuntimePlanningProjectionSchemaError
import skillbill.error.InvalidWorkflowStateSchemaError
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.agentrun.model.AgentRunLaunchOutcome
import skillbill.ports.agentrun.model.SkillRunRequest
import skillbill.ports.agentrun.model.UnsupportedAgentRunLaunch
import skillbill.ports.goalrunner.GoalRunnerSubtaskLauncher
import skillbill.ports.goalrunner.model.GoalRunnerSubtaskLaunchRequest
import skillbill.ports.workflow.buildGoalSubtaskReviewInput
import skillbill.ports.workflow.model.GoalSubtaskReviewBaseline
import skillbill.ports.workflow.model.GoalSubtaskReviewInput
import skillbill.ports.workflow.repositoryFingerprint
import skillbill.ports.workflow.repositoryOwnedPaths
import skillbill.ports.workflow.runtimePhaseChangedPathsBetweenCommits
import skillbill.ports.workflow.runtimePhaseHeadCommit
import skillbill.telemetry.estimation.estimateTokens
import skillbill.workflow.FeatureTaskRuntimePhaseOutputValidator
import skillbill.workflow.model.SpecSource
import skillbill.workflow.taskruntime.FeatureTaskRuntimeHandoffContract
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.FeatureTaskRuntimeTransitionFunction
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditRepairGapIdentities
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditRepairPlan
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditRepairState
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeBackwardEdge
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeCapExhaustionBehavior
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFailureDisposition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeNextPhase
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeOperatorBlockRetry
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseDeclaration
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepositoryCheckpoint
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepositoryCheckpointPolicy
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeReviewFinding
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeTransitionDeclaration
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.model.NormalizedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.acceptanceCriterionRefsFor
import skillbill.workflow.taskruntime.model.canonicalAuditIdentifier
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
  private val gitOperations get() = phaseGates.gitOperations
  private val planningProjectionValidator get() = phaseGates.planningProjectionValidator

  private var resolvedBranch: String? = null
  private var blocked: FeatureTaskRuntimeRunReport.Blocked? = null
  private var decomposed: FeatureTaskRuntimeRunReport.Decomposed? = null
  private val operatorBlockRetry: FeatureTaskRuntimeOperatorBlockRetry? = recorder
    .loadOperatorBlockRetry(request.workflowId, request.dbPathOverride)
    ?.takeIf { state.recordFor(it.phaseId) == null }
  private var operatorBlockRetryCompleted: Boolean = false

  private var pendingReentry: PendingReentry? = resumedReentry()
  private var activeReentry: PendingReentry? = pendingReentry

  private fun resumedReentry(): PendingReentry? {
    val (loopId, reentry) = state.latestInFlightReentry() ?: return null
    // A durable re-entry minted under an earlier phase ordering can name a span the live topology
    // cannot legally complete — a review_fix re-entry whose review is now gated behind an audit that
    // never ran. Entering at its destination would step over the gating phase for the rest of the
    // run, so the stale re-entry is dropped and the run restarts from the pipeline head, walking the
    // already-completed phases until it reaches the gating one.
    if (state.spanBlockedByEntryGate(reentry.span)) {
      state.discardStaleReentry(loopId)
      return null
    }
    state.recordEdgeIteration(loopId, reentry.edgeIteration)
    val auditGapLoop = loopId == FeatureTaskRuntimePhaseWorkflowDefinition.AUDIT_GAP_LOOP_ID
    val auditRepairState = if (auditGapLoop) {
      recorder.loadAuditRepairState(request.workflowId, request.dbPathOverride)
    } else {
      null
    }
    // A blocked audit attempt replaces the phase record, erasing its copy of the accepted plan and the
    // unmet criteria. The durable repair state is the single authority and still holds both, and
    // drive() requires the phase-record copy to equal the durable accepted plan anyway, so recover from
    // the authority rather than blocking a resume that already has everything it needs.
    val auditRepairPlan = if (auditGapLoop) {
      state.auditRepairPlan(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT)
        ?: auditRepairState?.acceptedPlans?.lastOrNull()
    } else {
      null
    }
    return PendingReentry(
      phaseId = reentry.destinationPhaseId,
      loopId = loopId,
      edgeIteration = reentry.edgeIteration,
      drivingVerdict = reentry.drivingVerdict,
      reentryGapCriteria = if (auditGapLoop) {
        state.unmetAuditCriteria(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT)
          .ifEmpty { auditRepairPlan?.gaps.orEmpty().map { it.acceptanceCriterionRef } }
      } else {
        emptyList()
      },
      auditRepairPlan = auditRepairPlan,
      auditRepairState = auditRepairState,
    )
  }

  fun drive() {
    if (FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW in state.phasesRequiringDurableGateInvalidation()) {
      check(recorder.persistReviewGenerationInvalidation(request.workflowId, request.dbPathOverride)) {
        "Could not durably invalidate legacy review evidence for workflow '${request.workflowId}'."
      }
      state.resetInvalidatedReviewGeneration()
      if (pendingReentry?.loopId == FeatureTaskRuntimePhaseWorkflowDefinition.REVIEW_FIX_LOOP_ID) {
        pendingReentry = null
        activeReentry = null
      }
    }
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
    phaseEntryBlockReason(phaseId)?.let { reason ->
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

  // Every reason the phase cannot be entered, evaluated in order and short-circuiting: the declared
  // ordering gate, then the resume cap guard, then the goal review-pass reconciliation.
  private fun phaseEntryBlockReason(phaseId: String): String? = entryGateBlockReason(phaseId)
    ?: capExhaustedOnResume(phaseId)
    ?: reconcileCompletedGoalReviewPass(phaseId)

  // The phase-entry seam of the declared ordering gate. drive() can enter a phase directly from a
  // resumed pending re-entry without ever consulting the transition function, so guarding only the
  // transition would leave a resume hole through which a stale durable record re-enters a gated
  // phase. Both seams evaluate the same declaration-owned predicate.
  //
  // The violation degrades to a durable, resumable Blocked report rather than an escaping throw:
  // an uncaught contract exception here would leave the workflow row running with no blocked reason
  // and skip goal-continuation outcome persistence, so the parent goal could neither resume nor
  // report. Every other governed gate in this runtime blocks the same way.
  private fun entryGateBlockReason(phaseId: String): String? {
    val settledVerdicts = state.settledVerdictsByPhaseId()
    return transitions.entryGateViolation(phaseId, settledVerdicts)?.let { gate ->
      FeatureTaskRuntimePhaseOrderViolationError(
        phaseId = gate.phaseId,
        requiredPhaseId = gate.requiredPhaseId,
        requiredVerdict = gate.requiredVerdict.wireValue,
        observedVerdict = settledVerdicts[gate.requiredPhaseId]?.wireValue,
      ).message
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
          normalizedOutput = outputMap,
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
    val normalizedOutput = outputValidator.normalizePhaseOutput(
      rawResult,
      sourceLabel = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW,
    )
    recordCarriedForwardGoalReview(rawResult, normalizedOutput, reentry)
  }.fold(
    onSuccess = {
      PhaseSettlement.completed(
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW,
        requireNotNull(reviewState.passResults.lastOrNull()).verdict,
      )
    },
    onFailure = { error -> blockCarriedForwardReview(error.message.orEmpty()) },
  )

  private fun recordCarriedForwardGoalReview(
    rawResult: String,
    normalizedOutput: NormalizedFeatureTaskRuntimePhaseOutput,
    reentry: PendingReentry?,
  ) {
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
    state.recordCompleted(FeatureTaskRuntimePhaseOutput(phaseId, iteration, rawResult, normalizedOutput))
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
    val transition = runCatching {
      FeatureTaskRuntimeTransitionFunction.nextTransition(
        declaration = transitions,
        currentPhaseId = phaseId,
        verdict = effectiveVerdict,
        edgeIterationCount = edge?.let { state.edgeIterationCount(it.loopId) } ?: 0,
        settledVerdictsByPhaseId = state.settledVerdictsByPhaseId(),
      )
    }.getOrElse { error ->
      if (error !is FeatureTaskRuntimePhaseOrderViolationError) throw error
      blockAt(error.phaseId, error.message.orEmpty())
      return null
    }
    return transitionTarget(phaseId, edge, effectiveVerdict, transition)
  }

  private fun transitionTarget(
    phaseId: String,
    edge: FeatureTaskRuntimeBackwardEdge?,
    effectiveVerdict: FeatureTaskRuntimeVerdict,
    transition: FeatureTaskRuntimeNextPhase,
  ): String? = when (transition) {
    is FeatureTaskRuntimeNextPhase.TerminalAdvance -> null
    is FeatureTaskRuntimeNextPhase.TerminalBlock -> {
      blockOnCapExhaustion(phaseId, transition)
      null
    }
    is FeatureTaskRuntimeNextPhase.Next -> {
      val loopId = transition.loopId
      when {
        phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW &&
          (state.completedReviewPassNumber() == 2 || state.outputCountFor(phaseId) >= 2) &&
          state.unresolvedReviewFindings(phaseId).isNotEmpty() -> {
          val reviewLoopId = FeatureTaskRuntimePhaseWorkflowDefinition.REVIEW_FIX_LOOP_ID
          blockOnCapExhaustion(
            phaseId,
            FeatureTaskRuntimeNextPhase.TerminalBlock(
              loopId = reviewLoopId,
              edgeIteration = state.edgeIterationCount(reviewLoopId).coerceAtLeast(1),
              unresolvedVerdict = FeatureTaskRuntimeVerdict.CHANGES_REQUESTED,
            ),
          )
          null
        }
        loopId == null -> transition.phaseId
        reentersMutatingPhase(requireNotNull(edge), transition.phaseId) &&
          !establishRemediationCheckpoint(phaseId) -> null
        loopId == FeatureTaskRuntimePhaseWorkflowDefinition.AUDIT_GAP_LOOP_ID &&
          !authoritativeAuditRepairPlanMatches(phaseId) -> {
          blockAt(
            phaseId,
            "The accepted audit repair plan was not durably readable and identical before the audit_gap edge.",
          )
          null
        }
        else -> {
          recordBackwardEdge(
            edge = edge,
            destinationPhaseId = transition.phaseId,
            loopId = loopId,
            edgeIteration = requireNotNull(transition.edgeIteration),
            verdict = effectiveVerdict,
          )
          transition.phaseId
        }
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

  private fun spanBetween(destinationPhaseId: String, sourcePhaseId: String): List<String> =
    transitions.spanBetween(destinationPhaseId, sourcePhaseId)

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
      if (operatorBlockRetry?.phaseId == phaseId) operatorBlockRetryCompleted = true
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
      outputArtifact = state.outputFor(phaseId)?.payload,
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
          projectionDeclarations = FeatureTaskRuntimePhaseWorkflowDefinition.upstreamReceiptProjections(
            phaseId,
            listOf(
              FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PREPLAN,
              FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN,
            ),
          ),
        )
      } else if (
        phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW &&
        reentry?.loopId == FeatureTaskRuntimePhaseWorkflowDefinition.REVIEW_FIX_LOOP_ID
      ) {
        declaration.copy(
          projectionDeclarations = FeatureTaskRuntimePhaseWorkflowDefinition.upstreamReceiptProjections(
            phaseId,
            declaration.consumedUpstreamPhaseIds +
              FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX,
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
    settledFullyClosedAudit(run, state, observability)?.let { return it }
    completedReviewBudgetOutput
      ?.let { output -> settleCompletedReviewBudget(run, state, observability, output) }
      ?.let { return it }
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

  private fun settleCompletedReviewBudget(
    run: PhaseRun,
    state: FeatureTaskRuntimeRunState,
    observability: FeatureTaskRuntimeRunObservability,
    output: FeatureTaskRuntimePhaseOutput,
  ): PhaseOutcome {
    if (!isGoalContinuationRun(run.request) || run.reentry == null) return PhaseOutcome.completed(output)
    val iteration = state.nextIteration(run.phaseId)
    val phaseState = phaseStateRequest(
      run,
      iteration,
      STATUS_COMPLETED,
      finished = true,
      outputArtifact = output.payload,
    )
    state.reserveReviewPass(phaseState.reviewPassNumber)
    recorder.recordCompletedPhase(phaseState, run.request.dbPathOverride)
    observability.completed(run.phaseId, run.resolvedAgent.resolvedAgentId, iteration)
    return PhaseOutcome.completed(output.copy(phaseId = run.phaseId, iteration = iteration))
  }

  private fun declaredCriterionRefs(): List<String> =
    acceptanceCriterionRefsFor(request.runInvariants.acceptanceCriteria.size)

  private fun durablyClosedCriterionRefs(): List<String> {
    val closed = recorder.loadAuditRepairState(request.workflowId, request.dbPathOverride)
      ?.satisfiedCriterionRefs
      .orEmpty()
    val undeclared = closed.filterNot { it in declaredCriterionRefs().toSet() }.sorted()
    if (undeclared.isNotEmpty()) {
      throw InvalidWorkflowStateSchemaError(
        "audit_repair_state.satisfied_criterion_refs contains criteria not declared by this run: $undeclared.",
      )
    }
    return closed
  }

  private fun openAuditCriterionRefs(closedCriterionRefs: List<String> = durablyClosedCriterionRefs()): List<String> =
    declaredCriterionRefs() - closedCriterionRefs.toSet()

  /**
   * Settles the audit as satisfied without launching a child when every acceptance criterion is
   * already durably closed. The audit has nothing left to verify, so launching one could only produce
   * a gap against a closed criterion, which the closure gate rejects anyway.
   */
  private fun settledFullyClosedAudit(
    run: PhaseRun,
    state: FeatureTaskRuntimeRunState,
    observability: FeatureTaskRuntimeRunObservability,
  ): PhaseOutcome? {
    val closedCriterionRefs = fullyClosedAuditCriterionRefs(run) ?: return null
    val iteration = state.nextIteration(run.phaseId)
    val outputText = fullyClosedAuditOutput(closedCriterionRefs)
    val normalizedOutput = runCatching {
      outputValidator.normalizePhaseOutput(outputText, sourceLabel = run.phaseId)
    }.getOrElse { error ->
      return blockAndPersistInPhase(
        run,
        iteration,
        "Audit settlement derived from durable criterion closure did not validate: ${error.message.orEmpty()}",
        observability,
      )
    }
    val persisted = recorder.recordCompletedPhase(
      phaseStateRequest(
        run,
        iteration,
        STATUS_COMPLETED,
        finished = true,
        outputArtifact = outputText,
        normalizedOutput = normalizedOutput,
      ),
      run.request.dbPathOverride,
    )
    if (!persisted) {
      return blockAndPersistInPhase(
        run,
        iteration,
        "Audit settlement derived from durable criterion closure could not be persisted.",
        observability,
        failureDisposition = FeatureTaskRuntimeFailureDisposition.PROCESS_FAILURE,
      )
    }
    observability.completed(run.phaseId, run.resolvedAgent.resolvedAgentId, iteration)
    return PhaseOutcome.completed(
      FeatureTaskRuntimePhaseOutput(run.phaseId, iteration, outputText, normalizedOutput),
    )
  }

  private fun fullyClosedAuditCriterionRefs(run: PhaseRun): List<String>? {
    if (run.phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT) return null
    val repairState = recorder.loadAuditRepairState(request.workflowId, request.dbPathOverride) ?: return null
    val closedCriterionRefs = repairState.satisfiedCriterionRefs
    return closedCriterionRefs.takeIf {
      repairState.unresolvedGapLedger.unresolvedGaps.isEmpty() &&
        closedCriterionRefs.isNotEmpty() &&
        openAuditCriterionRefs(closedCriterionRefs).isEmpty()
    }
  }

  private fun fullyClosedAuditOutput(closedCriterionRefs: List<String>): String = JsonSupport.mapToJsonString(
    mapOf(
      "contract_version" to FEATURE_TASK_RUNTIME_CONTRACT_VERSION,
      "phase_id" to FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT,
      "status" to STATUS_COMPLETED,
      "verdict" to FeatureTaskRuntimeVerdict.SATISFIED.wireValue,
      "summary" to "Every acceptance criterion reached a satisfied verdict in an earlier audit and is durably " +
        "closed, so this audit settles satisfied from that closure without re-verifying a closed criterion.",
      "produced_outputs" to mapOf(
        "unmet_criteria" to emptyList<Any?>(),
        "durably_closed_criteria" to closedCriterionRefs.sorted(),
      ),
    ),
  )

  private fun prepareGoalReviewRun(
    run: PhaseRun,
    observability: FeatureTaskRuntimeRunObservability,
  ): GoalReviewRunPreparation = when {
    run.phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW -> GoalReviewRunReady(run)
    isGoalReviewRun(run) -> reserveGoalReviewRun(run, observability)
    else -> prepareStandaloneReviewRun(run, observability)
  }

  private fun prepareStandaloneReviewRun(
    run: PhaseRun,
    observability: FeatureTaskRuntimeRunObservability,
  ): GoalReviewRunPreparation {
    val resolved = recorder.loadResolvedBranch(run.request.workflowId, run.request.dbPathOverride)
      ?: return blockedGoalReviewRun(run, observability, "Standalone review is missing its durable resolved branch.")
    val reviewBaseSha = resolved.reviewBaseSha
      ?: return blockedGoalReviewRun(
        run,
        observability,
        "Standalone review is missing the immutable review base captured before implementation.",
      )
    val result = phaseGates.gitOperations.buildGoalSubtaskReviewInput(
      run.request.repoRoot,
      GoalSubtaskReviewBaseline(reviewBaseSha, resolved.baselineUntrackedPaths),
      resolved.branch,
    )
    val input = result.input
      ?: return blockedGoalReviewRun(run, observability, result.error.ifBlank { "Standalone review input failed." })
    return GoalReviewRunReady(run.copy(goalReviewInput = input))
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
    val normalizedOutput = runCatching {
      outputValidator.normalizePhaseOutput(output, sourceLabel = run.phaseId)
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
    return PhaseOutcome.completed(
      FeatureTaskRuntimePhaseOutput(run.phaseId, iteration, output, normalizedOutput),
    )
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
      if (shouldRetryPersistedBlock(run.phaseId, durable, retryReviewPreparation, persistedReason)) {
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
        rejectedOutput = durable?.rejectedOutput,
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

  // The gate that wrote this reason blocked a goal review on schema-invalid output instead of retrying it,
  // and persisted a terminal needs_user_action disposition. That gate is gone, so such a record is stale
  // rather than terminal: the reserved pass still has no completed output, which the bounded fix loop is
  // now what decides. The remaining attempt budget is deliberately not restarted.
  private fun isRemovedGoalReviewSchemaGateBlock(phaseId: String, reason: String): Boolean =
    phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW &&
      reason.startsWith("Goal-subtask review output failed schema validation after its reserved pass")

  private fun shouldRetryPersistedBlock(
    phaseId: String,
    durable: FeatureTaskRuntimePhaseRecord?,
    retryReviewPreparation: Boolean,
    persistedReason: String,
  ): Boolean {
    val disposition = durable?.failureDisposition
    return when {
      retryReviewPreparation -> true
      isRemovedGoalReviewSchemaGateBlock(phaseId, persistedReason) -> true
      disposition != null -> disposition.retryOnResume
      else -> FeatureTaskRuntimeFixLoopPolicy.participatesInFixLoop(phaseId)
    }
  }

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
    var malformedAttemptCount = 0
    var semanticIteration = iteration - budgetBaseOffset
    while (outcome == null) {
      val attempt = attemptOnce(run, state, iteration, observability, priorSchemaFailure, phaseTokenAccumulator)
      outcome = attempt.settledOutcome ?: if (attempt.malformedOutput) {
        malformedAttemptCount += 1
        val formatBlock = FeatureTaskRuntimeFixLoopPolicy.malformedOutputBlockReason(
          run.phaseId,
          malformedAttemptCount,
        )
        if (formatBlock == null) {
          iteration += 1
          priorSchemaFailure = attempt.schemaInvalidReason
          observability.fixLoopIteration(run.phaseId, agentId, iteration, malformedAttemptCount)
          null
        } else {
          blockAndPersistInPhase(
            run,
            iteration,
            withSchemaGateDetail(formatBlock, requireNotNull(attempt.schemaInvalidReason)),
            observability,
            failureDisposition = FeatureTaskRuntimeFailureDisposition.INVALID_OUTPUT,
            fileManifest = attempt.fileManifest,
            rejectedOutput = attempt.rejectedOutput,
          )
        }
      } else {
        when (val decision = FeatureTaskRuntimeFixLoopPolicy.decideAfterFailure(run.phaseId, semanticIteration)) {
          is FeatureTaskRuntimeFixLoopDecision.Retry -> {
            iteration += 1
            semanticIteration += 1
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
            rejectedOutput = attempt.rejectedOutput,
          )
        }
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
    rejectedOutput: String? = null,
  ): PhaseOutcome {
    val phaseState = FeatureTaskRuntimePhaseStateRequest(
      workflowId = run.request.workflowId,
      phaseId = run.phaseId,
      status = STATUS_BLOCKED,
      attemptCount = attemptCount.coerceAtLeast(1),
      resolvedAgentId = run.resolvedAgent.resolvedAgentId,
      finished = false,
      outputArtifact = outputArtifact,
      rejectedOutput = rejectedOutput,
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
    rejectedOutput: String? = null,
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
    rejectedOutput = rejectedOutput,
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
          failureDisposition = launch.failureDisposition,
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
    schemaInvalidAttempt(
      error.reason,
      fileManifest,
      outputText,
      malformedOutput = error.failureKind == FeatureTaskRuntimePhaseOutputFailureKind.MALFORMED,
    )
  } catch (error: InvalidFeatureTaskRuntimeAuditRepairPlanSchemaError) {
    schemaInvalidAttempt(error.reason, fileManifest, outputText)
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
      return schemaInvalidAttempt(reason, fileManifest, outputText)
    }
    val terminalAuditRepairReason = terminalAuditRepairBlockGateReason(run.phaseId, outputMap)
    terminalAuditRepairReason?.let { reason ->
      return schemaInvalidAttempt(reason, fileManifest, outputText)
    }
    auditRepairResultGateReason(run.phaseId, outputMap)?.let { reason ->
      return schemaInvalidAttempt(reason, fileManifest, outputText)
    }
    if (!isCompactAuditOutput(run.phaseId, outputText)) {
      auditDurableLedgerGateReason(run.phaseId, outputMap)?.let { reason ->
        return schemaInvalidAttempt(reason, fileManifest, outputText)
      }
    }
    auditClosedCriterionGateReason(run.phaseId, outputMap)?.let { reason ->
      return schemaInvalidAttempt(reason, fileManifest, outputText)
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
    // Placed after the terminal path so a blocked or failed envelope never reaches it: only a phase
    // claiming 'completed' owes the projection its consumer will parse.
    producerProjectionGateReason(run.phaseId, outputMap, planningProjectionValidator)?.let { reason ->
      return schemaInvalidAttempt(reason, fileManifest, outputText)
    }
    outputVerificationGateReason(run.phaseId, outputMap)?.let { reason ->
      return schemaInvalidAttempt(reason, fileManifest, outputText)
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

  /**
   * Resolves a repository checkpoint only when some declaration actually needs one, reusing the same
   * `WorkflowGitOperations` fingerprint the audit-repair path already depends on. No new git port is
   * introduced and the domain stays git-agnostic: the checkpoint arrives as a plain value.
   */
  private fun resolveRepositoryCheckpoint(run: PhaseRun): FeatureTaskRuntimeRepositoryCheckpoint? {
    val needsCheckpoint = run.declaration.projectionDeclarations.any { projection ->
      projection.checkpointPolicy != FeatureTaskRuntimeRepositoryCheckpointPolicy.NOT_REQUIRED
    }
    if (!needsCheckpoint) return null
    val fingerprint = gitOperations.repositoryFingerprint(run.request.repoRoot)
      .takeIf { it.ok }
      ?.value
      ?.takeIf(String::isNotBlank)
      ?: return null
    val resolvedBranch = recorder.loadResolvedBranch(run.request.workflowId, run.request.dbPathOverride)
    // On a goal child the review state, not the resolved branch, holds the immutable base and the
    // baseline untracked inventory the parent captured for this subtask alone.
    val goalReviewState = goalContinuationRecorder.reviewState(run.request.workflowId, run.request.dbPathOverride)
    val ownedPaths = checkpointOwnedPaths(
      run,
      goalReviewState?.baselineUntrackedPaths ?: resolvedBranch?.baselineUntrackedPaths.orEmpty(),
    ) ?: return null
    return FeatureTaskRuntimeRepositoryCheckpoint(
      fingerprint = fingerprint,
      baseRef = goalReviewState?.reviewBaseSha ?: resolvedBranch?.reviewBaseSha,
      // The run-owned branch, not a measured HEAD: the durable pair is the same base/head scope review
      // resolves, and measuring HEAD here would add a git read the commit-sha path deliberately avoids.
      headRef = resolvedBranch?.branch?.takeIf(String::isNotBlank),
      workingTreeOwnedPaths = ownedPaths,
    )
  }

  /**
   * Owned paths for the checkpoint scope. Subtracting the run's recorded baseline untracked inventory
   * is what keeps a goal-child scoped to its own subtask: sibling subtasks share the tree, and every
   * path they left behind is already in that baseline.
   *
   * Both sides of that subtraction come from the same NUL-delimited plumbing listing. `git status
   * --porcelain` is deliberately not the source: it collapses a wholly-untracked directory to one
   * `dir/` entry and C-quotes non-ASCII paths, so a sibling subtask's new directory would never match
   * the `ls-files`-written baseline and would leak into the child's audit scope (AC-014).
   *
   * Returns null when the listing cannot be measured. An empty inventory is a real answer — the scope
   * owns nothing — and an audit reading it concludes no work exists here, so an unmeasurable read must
   * not be able to produce it. The caller drops the whole checkpoint, matching how an unmeasurable
   * fingerprint already blocks the launch instead of degrading it.
   *
   * An inventory past [MAX_CHECKPOINT_OWNED_PATHS] is rejected as a typed projection failure rather
   * than left to trip the briefing framing ceiling: that ceiling throws `IllegalArgumentException`,
   * which the launch path does not catch, so it would unwind past the handler that already persisted
   * STATUS_RUNNING and leave the row running with no blocked reason. Truncating instead is not an
   * option — audit would read a silently narrowed scope as the complete one.
   */
  private fun checkpointOwnedPaths(run: PhaseRun, baselineUntrackedPaths: List<String>): List<String>? {
    val owned = gitOperations.repositoryOwnedPaths(run.request.repoRoot)
    if (!owned.ok) return null
    val baseline = baselineUntrackedPaths.toSet()
    val paths = owned.value.orEmpty()
      .split(OWNED_PATH_DELIMITER)
      .map(String::trim)
      .filter(String::isNotBlank)
      .filterNot { it in baseline }
      .distinct()
      .sorted()
    if (paths.size > MAX_CHECKPOINT_OWNED_PATHS) {
      val declaration = run.declaration.projectionDeclarations.first { projection ->
        projection.checkpointPolicy != FeatureTaskRuntimeRepositoryCheckpointPolicy.NOT_REQUIRED
      }
      throw InvalidFeatureTaskRuntimeHandoffProjectionError(
        workflowId = run.request.workflowId,
        consumerPhaseId = run.phaseId,
        projectionName = declaration.projectionName,
        projectionContractId = declaration.projectionContractId,
        projectionContractVersion = declaration.projectionContractVersion,
        failureKind = FeatureTaskRuntimeHandoffProjectionFailureKind.BUDGET_OVERFLOW,
        reason = "the scoped owned-path inventory holds ${paths.size} entries, over the " +
          "$MAX_CHECKPOINT_OWNED_PATHS-entry checkpoint limit; narrow the run scope or commit " +
          "unrelated working-tree changes before relaunching",
      )
    }
    return paths
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
    // Review no longer sits inside the reopened [implement, audit] span, so non-progress detection is
    // the only bound left on the uncapped audit-gap cycle. An absent fingerprint means repository
    // change could not be proven, which is not evidence that anything moved: treat it as unchanged so
    // an equivalent recurring gap set blocks, rather than disarming the bound and looping forever.
    val previousFingerprint = prior.repositoryFingerprint ?: UNPROVEN_REPOSITORY_FINGERPRINT
    val currentFingerprint = repositoryFingerprint ?: UNPROVEN_REPOSITORY_FINGERPRINT
    val produced = JsonSupport.anyToStringAnyMap(outputMap["produced_outputs"]).orEmpty()
    val currentPlan = produced["audit_repair_plan"]?.let {
      auditRepairPlanFromWire(it, "audit.produced_outputs.audit_repair_plan")
    }
    val currentGapIds = currentPlan?.gaps.orEmpty().mapTo(linkedSetOf()) { it.gapId }
    if (currentGapIds.isEmpty()) return null
    val latestPlanItemIds = prior.acceptedPlans.last().gaps
      .flatMap { it.repairItems }
      .mapTo(linkedSetOf()) { it.repairItemId }
    val resolvedCount = prior.repairItemResults.count { it.repairItemId in latestPlanItemIds }
    return detectAuditRepairNonProgress(
      previous = FeatureTaskRuntimeAuditRepairGapIdentities(
        gapIds = prior.unresolvedGapLedger.unresolvedGaps.mapTo(linkedSetOf()) { it.gapId },
        criterionRefs = prior.unresolvedGapLedger.unresolvedGaps.mapTo(linkedSetOf()) { it.acceptanceCriterionRef },
      ),
      current = FeatureTaskRuntimeAuditRepairGapIdentities(
        gapIds = currentGapIds,
        criterionRefs = currentPlan?.gaps.orEmpty().mapTo(linkedSetOf()) { it.acceptanceCriterionRef },
      ),
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
      AttemptResult.schemaInvalid(reason, fileManifest, boundedRejectedOutput(outputText))
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

  private fun isCompactAuditOutput(phaseId: String, canonicalJson: String): Boolean {
    if (phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT) return false
    val wireOutput = JsonSupport.parseObjectOrNull(canonicalJson)
      ?.let(JsonSupport::jsonElementToValue)
      ?.let(JsonSupport::anyToStringAnyMap)
      ?: return false
    val produced = JsonSupport.anyToStringAnyMap(wireOutput["produced_outputs"]) ?: return false
    return produced.containsKey(FeatureTaskRuntimeVerificationSignalKeys.AUDIT_GAPS)
  }

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
    val actual = resultMaps.mapNotNull { (it["repair_item_id"] as? String)?.let(::canonicalAuditIdentifier) }
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
      val itemPosition = actualOrder[itemId] ?: return@forEach
      item.dependsOn.forEach { dependency ->
        val dependencyPosition = actualOrder[dependency] ?: return@forEach
        val expectedDependency = expectedOrder[dependency] ?: return@forEach
        val expectedItem = expectedOrder[itemId] ?: return@forEach
        if (dependencyPosition >= itemPosition || expectedDependency >= expectedItem) {
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

  // Closure is only durable if nothing can quietly reopen it: an audit naming a closed criterion under
  // any gap id, or naming a criterion the run never declared, is rejected here rather than reconciled.
  private fun auditClosedCriterionGateReason(phaseId: String, outputMap: Map<String, Any?>): String? {
    if (phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT) return null
    val produced = JsonSupport.anyToStringAnyMap(outputMap["produced_outputs"]).orEmpty()
    val planRefs = (JsonSupport.anyToStringAnyMap(produced["audit_repair_plan"])?.get("gaps") as? List<*>)
      .orEmpty()
      .mapNotNull { gap -> JsonSupport.anyToStringAnyMap(gap)?.get("acceptance_criterion_ref") as? String }
    val criteriaRefs = (produced["unmet_criteria"] as? List<*>).orEmpty()
      .mapNotNull { entry -> JsonSupport.anyToStringAnyMap(entry)?.get("acceptance_criterion_ref") as? String }
    val referenced = (planRefs + criteriaRefs).distinct()
    if (referenced.isEmpty()) return null
    val undeclared = referenced.filterNot { it in declaredCriterionRefs().toSet() }.sorted()
    if (undeclared.isNotEmpty()) {
      return "Audit reported acceptance criteria not declared by this run: $undeclared."
    }
    val reopened = referenced.filter { it in durablyClosedCriterionRefs().toSet() }.sorted()
    return if (reopened.isEmpty()) {
      null
    } else {
      "Audit reported a gap against durably closed acceptance criteria $reopened; a criterion that reached a " +
        "satisfied verdict is closed and is not re-verified by a later audit."
    }
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
    if (gapId.isNullOrBlank() || itemId.isNullOrBlank()) {
      return "Blocked audit remediation requires nonblank gap_id and repair_item_id."
    }
    runCatching {
      auditEvidenceFromWire(block["evidence"], "unresolvable_repair.evidence")
    }.exceptionOrNull()?.let { decodeFailure ->
      return "Blocked audit remediation evidence is not contract-safe: ${decodeFailure.diagnosticMessage()}"
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
      persistGoalReviewCompletion(run, iteration, normalizedOutput, observability, fileManifest)?.let { outcome ->
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
    normalizedOutput: NormalizedFeatureTaskRuntimePhaseOutput,
    observability: FeatureTaskRuntimeRunObservability,
    fileManifest: FeatureTaskRuntimePhaseFileManifest,
  ): PhaseOutcome? {
    val outputText = normalizedOutput.canonicalJson
    val outputMap = normalizedOutput.envelope
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
            normalizedOutput = normalizedOutput,
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

  // A rejected output is the only evidence of why a contract gate refused it. Persisting it bounded
  // keeps the durable record diagnosable without letting a runaway agent payload into workflow state.
  private fun boundedRejectedOutput(rejectedOutput: String?): String? =
    rejectedOutput?.takeIf(String::isNotBlank)?.take(REJECTED_OUTPUT_MAX_CHARS)

  // A goal-subtask review reserves its pass once in prepareGoalReviewRun, outside runPhaseAttempts, so a
  // bounded in-loop re-attempt reuses that same reserved pass instead of allocating another. Schema-invalid
  // output therefore earns the same fix-loop retries as every other phase: the reserved pass has no completed
  // output, which is the state a resume is already contracted to re-enter rather than treat as terminal.
  private fun schemaInvalidAttempt(
    reason: String,
    fileManifest: FeatureTaskRuntimePhaseFileManifest,
    rejectedOutput: String? = null,
    malformedOutput: Boolean = false,
  ): AttemptResult = AttemptResult.schemaInvalid(
    reason,
    fileManifest,
    boundedRejectedOutput(rejectedOutput),
    malformedOutput,
  )

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
    auditScopeCriterionRefs = if (run.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT) {
      openAuditCriterionRefs()
    } else {
      emptyList()
    },
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

  private fun prepareLaunch(
    run: PhaseRun,
    state: FeatureTaskRuntimeRunState,
    priorSchemaFailure: String?,
  ): PreparedLaunch {
    val handoff = FeatureTaskRuntimeHandoffContract.assembleHandoff(
      declaration = run.declaration,
      runInvariants = run.request.runInvariants,
      recordedOutputs = state.outputs(),
      drivingVerdict = run.reentry?.drivingVerdict,
      reentryGapCriteria = auditGapCriteriaFor(run, state),
      auditRepairPlan = run.reentry?.auditRepairPlan,
      auditRepairState = run.reentry?.auditRepairState,
      durablyClosedCriterionRefs = if (run.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT) {
        durablyClosedCriterionRefs()
      } else {
        emptyList()
      },
      repositoryCheckpoint = resolveRepositoryCheckpoint(run),
      expectedRepositoryCheckpoint = run.reentry?.auditRepairState?.repositoryFingerprint
        ?.let(::FeatureTaskRuntimeRepositoryCheckpoint),
    )
    val briefing = FeatureTaskRuntimePhaseBriefingAssembler.assemble(
      handoff,
      run.request.workflowId,
      planningProjectionValidator,
    )
    recorder.recordPhaseBriefing(run.request.workflowId, briefing, run.request.dbPathOverride)
    val prompt = FeatureTaskRuntimePhasePromptComposer.compose(
      issueKey = run.request.issueKey,
      briefing = briefing,
      suppressDecomposition = isGoalContinuationRun(run.request),
      parallelReviewAgent = run.request.parallelReviewAgent
        ?.takeIf { run.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW },
      codeReviewMode = reviewPassNumber(run, state)?.let { passNumber ->
        skillbill.workflow.taskruntime.model.FeatureTaskRuntimeReviewPassSequence.modeForPass(
          run.request.runInvariants.codeReviewMode,
          passNumber,
        )
      } ?: run.request.runInvariants.codeReviewMode,
      reviewPassNumber = reviewPassNumber(run, state),
      goalSubtaskReviewInput = run.goalReviewInput,
      specSource = run.specSource,
      priorSchemaFailure = priorSchemaFailure,
      operatorBlockRetry = operatorBlockRetry
        ?.takeIf { it.phaseId == run.phaseId && !operatorBlockRetryCompleted },
      specReference = run.request.runInvariants.specReference,
      agentAddonSelection = run.request.agentAddonSelection,
    )
    return PreparedLaunch(briefing, prompt)
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
    val prepared = try {
      prepareLaunch(run, state, priorSchemaFailure)
    } catch (error: InvalidFeatureTaskRuntimeHandoffProjectionError) {
      // A projection rejection is static declaration/config drift, not agent output: block the phase
      // durably instead of unwinding out of a run that already persisted STATUS_RUNNING.
      return LaunchResult.projectionRejected(
        "Feature-task-runtime phase '${run.phaseId}' could not build its declared handoff projection: " +
          error.message,
      )
    } catch (error: InvalidFeatureTaskRuntimePhaseBriefingFramingError) {
      // The assembled framing (governing contract plus the resolved repository checkpoint) overflows the
      // briefing byte ceiling. Without this catch the assembler's throw would unwind past the STATUS_RUNNING
      // persist and wedge the row with no blockedReason; block durably instead.
      return LaunchResult.projectionRejected(
        "Feature-task-runtime phase '${run.phaseId}' could not fit its launch briefing under the byte ceiling: " +
          error.message,
      )
    } catch (error: InvalidFeatureTaskRuntimePlanningProjectionSchemaError) {
      // A producing phase is already settled completed by the time its projection is parsed, so an
      // unhandled throw here would unwind past the handler that persisted STATUS_RUNNING and leave the
      // row running with no blockedReason, crashing identically on every later resume. Block durably
      // instead, naming the producing phase so the operator knows which record to migrate or delete.
      return LaunchResult.projectionRejected(
        "Feature-task-runtime phase '${run.phaseId}' rejected an upstream bounded planning projection at the " +
          "launch seam (workflow '${run.request.workflowId}'): ${error.message}",
      )
    } catch (error: InvalidWorkflowStateSchemaError) {
      return LaunchResult.projectionRejected(
        "Feature-task-runtime phase '${run.phaseId}' rejected a durable handoff envelope at the launch seam: " +
          error.message,
      )
    }
    val briefing = prepared.briefing
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
          promptOverride = prepared.prompt,
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

  private data class PreparedLaunch(
    val briefing: FeatureTaskRuntimePhaseLaunchBriefing,
    val prompt: String,
  )

  private sealed interface LaunchResult {
    private data class Captured(
      val stdout: String,
      override val fileManifest: FeatureTaskRuntimePhaseFileManifest,
    ) : LaunchResult
    private data class InfraFailure(
      val reason: String,
      override val fileManifest: FeatureTaskRuntimePhaseFileManifest?,
      val disposition: FeatureTaskRuntimeFailureDisposition,
    ) : LaunchResult

    val capturedStdout: String? get() = (this as? Captured)?.stdout
    val infraFailureReason: String? get() = (this as? InfraFailure)?.reason
    val failureDisposition: FeatureTaskRuntimeFailureDisposition
      get() = (this as? InfraFailure)?.disposition ?: FeatureTaskRuntimeFailureDisposition.PROCESS_FAILURE
    val fileManifest: FeatureTaskRuntimePhaseFileManifest?

    companion object {
      fun captured(stdout: String, fileManifest: FeatureTaskRuntimePhaseFileManifest): LaunchResult =
        Captured(stdout, fileManifest)
      fun infraFailure(reason: String, fileManifest: FeatureTaskRuntimePhaseFileManifest? = null): LaunchResult =
        InfraFailure(reason, fileManifest, FeatureTaskRuntimeFailureDisposition.PROCESS_FAILURE)

      /** Static declaration or configuration drift: retrying without operator action reproduces it. */
      fun projectionRejected(reason: String): LaunchResult =
        InfraFailure(reason, null, FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION)
    }
  }

  private sealed interface AttemptResult {
    private data class Settled(val outcome: PhaseOutcome) : AttemptResult
    private data class SchemaInvalid(
      val validationReason: String,
      override val fileManifest: FeatureTaskRuntimePhaseFileManifest,
      override val rejectedOutput: String?,
      override val malformedOutput: Boolean,
    ) : AttemptResult

    val settledOutcome: PhaseOutcome? get() = (this as? Settled)?.outcome
    val schemaInvalidReason: String? get() = (this as? SchemaInvalid)?.validationReason
    val fileManifest: FeatureTaskRuntimePhaseFileManifest? get() = (this as? SchemaInvalid)?.fileManifest
    val rejectedOutput: String? get() = (this as? SchemaInvalid)?.rejectedOutput
    val malformedOutput: Boolean get() = (this as? SchemaInvalid)?.malformedOutput == true

    companion object {
      fun settled(outcome: PhaseOutcome): AttemptResult = Settled(outcome)
      fun schemaInvalid(
        validationReason: String,
        fileManifest: FeatureTaskRuntimePhaseFileManifest,
        rejectedOutput: String?,
        malformedOutput: Boolean = false,
      ): AttemptResult = SchemaInvalid(validationReason, fileManifest, rejectedOutput, malformedOutput)
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

// Stands in for a repository fingerprint that could not be computed. Comparing it against itself
// yields "unchanged", so an audit that cannot prove the repository moved cannot claim progress.
private const val UNPROVEN_REPOSITORY_FINGERPRINT = "<unproven>"

private const val REJECTED_OUTPUT_MAX_CHARS = 20_000

// NUL delimiter of the `-z` plumbing listing the checkpoint owned-path inventory is derived from.
private const val OWNED_PATH_DELIMITER = '\u0000'

// Bounds the rendered checkpoint scope well under the briefing framing ceiling, so an oversized
// inventory is rejected as a typed projection failure instead of tripping that ceiling's untyped throw.
private const val MAX_CHECKPOINT_OWNED_PATHS = 500

private const val FORWARD_DEFERRAL_PATTERN =
  "\\b(defer(?:red|ring)?|postpone(?:d|ment)?|leave|assign(?:ed|ing)?|hand(?:ed)?\\s+off|later)\\b" +
    ".{0,120}\\b(review|audit|validation|test(?:ing)?|later phase)\\b"
private const val REVERSE_DEFERRAL_PATTERN =
  "\\b(review|audit|validation|test(?:ing)?|later phase)\\b.{0,120}" +
    "\\b(will|should|must|can)\\s+(handle|fix|complete|cover|address|verify)\\b"
