package skillbill.application.featuretask

import me.tatarka.inject.annotations.Inject
import skillbill.application.goalrunner.GoalSubtaskReviewSummaryReducer
import skillbill.application.goalrunner.stderrExcerpt
import skillbill.application.model.FeatureTaskRuntimeFixLoopDecision
import skillbill.application.model.FeatureTaskRuntimeGoalContinuationContext
import skillbill.application.model.FeatureTaskRuntimePhaseStateRequest
import skillbill.application.model.FeatureTaskRuntimePlanningStopDecision
import skillbill.application.model.FeatureTaskRuntimeResolvedPhaseAgent
import skillbill.application.model.FeatureTaskRuntimeRunEvent
import skillbill.application.model.FeatureTaskRuntimeRunReport
import skillbill.application.model.FeatureTaskRuntimeRunRequest
import skillbill.application.model.FeatureTaskRuntimeSubtaskOutcome
import skillbill.application.workflow.repoRoot
import skillbill.config.model.PhaseModelDirective
import skillbill.contracts.JsonSupport
import skillbill.error.InvalidFeatureTaskRuntimePhaseOutputSchemaError
import skillbill.goalrunner.model.GoalRunnerLaunchFacts
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.agentrun.model.AgentRunLaunchOutcome
import skillbill.ports.agentrun.model.SkillRunRequest
import skillbill.ports.agentrun.model.UnsupportedAgentRunLaunch
import skillbill.ports.goalrunner.GoalRunnerSubtaskLauncher
import skillbill.ports.goalrunner.model.GoalRunnerSubtaskLaunchRequest
import skillbill.ports.persistence.model.FeatureTaskWorkflowMode
import skillbill.ports.workflow.WorkflowGitOperations
import skillbill.ports.workflow.model.GoalSubtaskReviewBaseline
import skillbill.ports.workflow.model.GoalSubtaskReviewInput
import skillbill.telemetry.estimation.estimateTokens
import skillbill.workflow.FeatureTaskRuntimePhaseOutputValidator
import skillbill.workflow.model.CodeReviewExecutionMode
import skillbill.workflow.model.SpecSource
import skillbill.workflow.taskruntime.FeatureTaskRuntimeHandoffContract
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.FeatureTaskRuntimeTransitionFunction
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditCriterionGap
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditVerdict
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeBackwardEdge
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeGoalContinuationArtifact
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeGoalContinuationOutcome
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeNextPhase
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseDeclaration
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeReviewFinding
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeReviewSeverity
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeReviewVerdict
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeTransitionDeclaration
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict

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

@Suppress("LargeClass", "TooManyFunctions")
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

  private var resolvedBranch: String? = null
  private var blocked: FeatureTaskRuntimeRunReport.Blocked? = null
  private var decomposed: FeatureTaskRuntimeRunReport.Decomposed? = null

  // The pending backward-edge re-entry to feed the next phase launch, set when an edge fires:
  // the driving verdict plus the runtime-minted loop id and per-edge iteration. Null on a forward
  // launch so the launch is byte-for-byte unchanged.
  private var pendingReentry: PendingReentry? = null

  // Single orchestration seam: the bounded state-machine driver. It starts at the first forward
  // phase, settles each phase, then asks the transition function for the next phase from the
  // declaration (forward by default; a backward edge re-enters an upstream phase up to its
  // per-edge cap, then blocks loudly). No second loop.
  fun drive() {
    var phaseId: String? = transitions.forwardPhaseIds.first()
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

  // Settles one phase: skips already-complete phases, guarantees the feature branch before a
  // file-mutating phase (preplan/plan may precede setup), then launches the phase. A re-entered
  // phase whose durable per-edge counter already hit its cap re-blocks before relaunching.
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
    // For an already-complete PLAN, re-evaluate the decompose determination on resume before
    // advancing, so a crash after PLAN persisted completed but before the decompose terminal was
    // observed never silently advances to implement (AC2). Idempotent: a recorded terminal is
    // reconstructed, never duplicate-written.
    val reason = if (state.isComplete(phaseId)) {
      state.outputFor(phaseId)
        ?.takeIf { phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN }
        ?.let { applyPlanningStop(phaseId, it) }
    } else {
      establishBranchIfNeeded(phaseId) ?: run {
        // Just before the commit phase launches, flip the run's spec-file status frontmatter to
        // complete so the commit_push agent stages and commits it with the feature work, instead
        // of leaving the spec stuck at "Pending" after the run finishes. Every preceding gate has
        // passed by commit_push, so the spec is durably complete; the projector no-ops for any
        // other phase and for goal-continuation children (the goal runner owns their status).
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
      reviewState?.takeIf { it.reviewCapReached || it.completedPassCount >= 2 }
        ?.let(::settleCarriedForwardGoalReview)
    },
    onFailure = { error -> blockCarriedForwardReview(error.message.orEmpty()) },
  )

  private fun settleCarriedForwardGoalReview(
    reviewState: skillbill.workflow.taskruntime.model.GoalSubtaskReviewState,
  ): PhaseSettlement =
    runCatching { goalContinuationRecorder.lastGoalReviewResult(request.workflowId, request.dbPathOverride) }.fold(
      onSuccess = { rawResult ->
        rawResult?.let { validateCarriedForwardGoalReview(it, reviewState) }
          ?: blockCarriedForwardReview("missing")
      },
      onFailure = { error -> blockCarriedForwardReview(error.message.orEmpty()) },
    )

  private fun validateCarriedForwardGoalReview(
    rawResult: String,
    reviewState: skillbill.workflow.taskruntime.model.GoalSubtaskReviewState,
  ): PhaseSettlement = runCatching {
    outputValidator.validateAndReadPhaseOutput(
      rawResult,
      sourceLabel = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW,
    )
  }.fold(
    onSuccess = {
      PhaseSettlement.completed(
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW,
        requireNotNull(reviewState.passResults.lastOrNull()).verdict,
      )
    },
    onFailure = { error -> blockCarriedForwardReview(error.message.orEmpty()) },
  )

  private fun blockCarriedForwardReview(detail: String): PhaseSettlement {
    val reason = if (detail == "missing") {
      "Goal-subtask review pass budget is exhausted but its durable raw review result is missing."
    } else {
      "Goal-subtask review pass budget is exhausted but its durable raw review result is malformed: $detail"
    }
    blockAt(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW, reason)
    return PhaseSettlement.stop()
  }

  // Computes the next phase from the transition function. A forward edge advances to the next
  // forward phase (or terminates with success at the pipeline end). A backward edge increments and
  // persists its runtime-minted per-edge counter, threads the driving verdict into the re-entered
  // phase, and re-enters; the same edge at its cap blocks loudly with the loop id, the iteration
  // count, and the unresolved verdict.
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
          // Remediation checkpoint: just after a verifier-passing iteration and before a backward
          // edge re-enters a mutating phase, snapshot the known-clean tree on the feature branch so
          // a crash mid mutating-phase resumes from a reconcilable boundary. A failed checkpoint
          // blocks loudly rather than re-entering a mutating phase on a dirty tree.
          if (reentersMutatingPhase(requireNotNull(edge), transition.phaseId) &&
            !establishRemediationCheckpoint(phaseId)
          ) {
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

  // True when the span reopened by [edge] (destination through source) contains a mutating phase,
  // so the upcoming re-entry could resume a mutating phase on a partially-applied tree.
  private fun reentersMutatingPhase(edge: FeatureTaskRuntimeBackwardEdge, destinationPhaseId: String): Boolean =
    spanBetween(destinationPhaseId, edge.fromPhaseId).any(FeatureTaskRuntimePhaseWorkflowDefinition::isMutatingPhase)

  // The forward span an edge reopens, destination through source inclusive; falls back to the
  // destination alone when the indices do not bracket (defensive — a real edge always brackets).
  private fun spanBetween(destinationPhaseId: String, sourcePhaseId: String): List<String> {
    val destinationIndex = transitions.forwardPhaseIds.indexOf(destinationPhaseId)
    val sourceIndex = transitions.forwardPhaseIds.indexOf(sourcePhaseId)
    return if (destinationIndex in 0..sourceIndex) {
      transitions.forwardPhaseIds.subList(destinationIndex, sourceIndex + 1)
    } else {
      listOf(destinationPhaseId)
    }
  }

  // The reopened span of a nested backward edge (its destination through its source), the set of
  // phase records whose stale per-edge watermark must be cleared when the nested loop resets.
  private fun nestedLoopSpan(edge: FeatureTaskRuntimeBackwardEdge): List<String> =
    spanBetween(edge.destinationPhaseId, edge.fromPhaseId)

  // Snapshots a known-clean boundary before re-entering a mutating phase. A no-op when the tree is
  // already clean or the feature branch is not resolved / is protected (a real run reaches a
  // mutating-phase backward edge only after branch setup, but guard anyway so we never commit on a
  // protected/default branch). Reaches git ONLY through phaseGates.gitOperations and never pushes,
  // so suppress_pr/goal-continuation is honored: the checkpoint commit is the single added durable
  // boundary, not an early push. Returns true to continue, false after blocking loudly on a failed
  // worktree read or commit so the run never silently re-enters a mutating phase on a dirty tree.
  private fun establishRemediationCheckpoint(precedingPhaseId: String): Boolean {
    val branch = resolvedBranch ?: return true
    if (FeatureTaskRuntimeBranchSetup.protectedBranchName(branch) != null) {
      return true
    }
    // The protected-branch guard inspects resolvedBranch, but the commit lands on actual HEAD. If
    // the two disagree (defensive: a real run never reaches here on a different HEAD), skip the
    // checkpoint so the guard and the commit target stay in agreement — never force-switch or block.
    val head = phaseGates.gitOperations.currentBranch(request.repoRoot)
    if (!head.ok || head.value.trim() != branch.trim()) {
      return true
    }
    val status = phaseGates.gitOperations.worktreeStatus(request.repoRoot)
    return when {
      !status.ok -> blockCheckpoint(precedingPhaseId, branch, status.error)
      status.value.isBlank() -> true // clean tree: nothing to checkpoint
      else -> commitCheckpoint(precedingPhaseId, branch)
    }
  }

  // Stages the full tree then commits it as a checkpoint; blocks loudly when staging or the commit
  // fails. Agents never `git add`, so the dirty tree is unstaged — staging first is what makes the
  // commit (which has no implicit staging) snapshot the worktree instead of an empty index.
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

  // A backward edge fired: reopen the forward span from the destination through the source phase so
  // the loop body re-executes and yields a fresh verdict (not the stale one that drove the edge),
  // register the runtime-minted per-edge counter and the driving verdict for the re-entered phase,
  // and append a durable LOOP_EDGE ledger entry. The counter is distinct from attempt_count and
  // survives resume.
  private fun recordBackwardEdge(
    edge: FeatureTaskRuntimeBackwardEdge,
    destinationPhaseId: String,
    loopId: String,
    edgeIteration: Int,
    verdict: FeatureTaskRuntimeVerdict,
  ) {
    val reopenedSpan = spanBetween(destinationPhaseId, edge.fromPhaseId)
    reopenedSpan.forEach(state::reopenForReentry)
    // A wider edge that reopens a span containing a nested loop's source restarts that nested loop:
    // the re-run is a fresh verification cycle, so the nested per-edge counter (e.g. review_fix when
    // audit_gap reopens [plan, audit]) resets per outer iteration while this edge's own counter is
    // independent (AC5). The reset is made DURABLE: the per-phase record edge context is the resume
    // source of truth, so the nested loop's span records must be stripped of their pre-reset
    // watermark too — otherwise a crash after the reset (and before the nested span re-runs) would
    // re-import the pre-reset count on resume and deny the fresh per-iteration budget.
    transitions.backwardEdges
      .filter { it.loopId != loopId && it.fromPhaseId in reopenedSpan }
      .forEach { nested ->
        state.resetEdgeIteration(nested.loopId)
        recorder.clearBackwardEdgeContext(request.workflowId, nestedLoopSpan(nested), request.dbPathOverride)
      }
    state.recordEdgeIteration(loopId, edgeIteration)
    // An audit_gap re-entry scopes the re-plan/re-implement to the failing criteria the source audit
    // carried; a review_fix re-entry carries none (its findings flow through drivingVerdict).
    val reentryGapCriteria = if (loopId == FeatureTaskRuntimePhaseWorkflowDefinition.AUDIT_GAP_LOOP_ID) {
      state.unmetAuditCriteria(edge.fromPhaseId)
    } else {
      emptyList()
    }
    pendingReentry = PendingReentry(destinationPhaseId, loopId, edgeIteration, verdict, reentryGapCriteria)
    observability.loopEdge(destinationPhaseId, loopId, edgeIteration, verdict)
  }

  // Returns a re-block reason when a re-entered phase's durable per-edge counter already reached its
  // cap on resume, so the runtime re-blocks before relaunching rather than bypassing the cap.
  private fun capExhaustedOnResume(phaseId: String): String? {
    val record = state.recordFor(phaseId) ?: return null
    val loopId = record.loopId
    val iteration = record.edgeIteration
    // Once the live machine has advanced this loop, the resume-time snapshot is stale; the live
    // cap is enforced by the transition function, so do not re-fire the resume-entry guard.
    if (loopId == null || iteration == null || state.isLoopLiveClaimed(loopId)) {
      return null
    }
    val edge = transitions.backwardEdges.firstOrNull { it.loopId == loopId && it.destinationPhaseId == phaseId }
    return edge
      ?.takeIf { iteration >= it.perEdgeCap }
      ?.let { capExhaustionReason(it.loopId, iteration, it.triggeringVerdict) }
  }

  // Runs the phase and records its completed output; returns a blocked reason when it blocks.
  private fun runPhaseFor(phaseId: String): String? {
    val reentry = pendingReentry?.takeIf { it.phaseId == phaseId }
    pendingReentry = null
    val outcome = runPhase(phaseId, request, state, observability, specSource, reentry, phaseTokenAccumulator)
    return outcome.blockedReason ?: run {
      val completedOutput = requireNotNull(outcome.completedOutput)
      state.recordCompleted(completedOutput)
      applyPlanningStop(phaseId, completedOutput)
    }
  }

  // Resolves the plan-phase stop for the given PLAN output, whether freshly completed or re-read on
  // resume. Sets `decomposed` on a decompose terminal and persists a durable block on a malformed
  // package; returns the blocked reason when the run must block, else null.
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

  private fun resolvePlanningStop(
    planOutput: FeatureTaskRuntimePhaseOutput,
  ): FeatureTaskRuntimePlanningStopDecision = planningStopper.resolve(
    request = request,
    completedOutput = planOutput,
    completedPhaseIds = state.completedPhaseIds(),
    resolvedBranch = resolvedBranch,
  )

  // Persists a durable terminal blocked record for the plan phase and emits the blocked
  // observability/ledger event so a malformed-decompose block is visible to status and the audit
  // trail, consistent with every other phase block, rather than living only in the in-memory report.
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
    return decomposed ?: blocked ?: FeatureTaskRuntimeRunReport.Completed(
      issueKey = request.issueKey,
      workflowId = request.workflowId,
      featureSize = request.runInvariants.featureSize.name,
      completedPhaseIds = state.completedPhaseIds(),
      resolvedBranch = branch,
    )
  }

  // Returns a blocked reason when a file-mutating phase cannot get a feature branch, else null.
  // A branch-setup block is made first-class and symmetric with the per-phase block path: it
  // persists a durable blocked per-phase record and emits the typed branch-setup-blocked
  // observability event + ledger entry so the failure is visible to status queries and the audit
  // trail, not lost in the in-memory report alone.
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

  // Branch setup recovered on resume (the operator fixed the transient git condition). A prior
  // branch-setup-origin blocked record persisted under "implement" would otherwise be seen by
  // preLaunchBlock and permanently re-block the phase without launching the agent. Clear it only
  // in-memory so the stale durable block remains intact until the real phase launch atomically
  // overwrites it with that phase agent's running record. Genuine phase-agent blocks are untouched.
  private fun clearRecoveredBranchSetupBlock(phaseId: String) {
    if (!state.hasBranchSetupBlock(phaseId)) {
      return
    }
    state.clearBranchSetupBlock(phaseId)
  }

  // Mirrors blockAndPersist for the branch-setup step: persists a durable terminal blocked
  // per-phase record (so blocked-ness survives ledger pruning and surfaces in status queries) and
  // emits the typed branch-setup-blocked observability event plus its ledger entry.
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

  // Cap exhaustion: persist a durable terminal blocked record (carrying the loop id + iteration in
  // the edge context) and emit the blocked observability/ledger event with the loop id, the
  // iteration count, and the unresolved verdict in the reason, consistent with every other block
  // path. Reuses blockAndPersist so the terminal blocked record and observability.blocked agree.
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

// The fixed checkpoint commit message; reconciliation checkpoints are bookkeeping commits the
// mutating-phase re-entry resumes from, not feature commits.
private fun remediationCheckpointMessage(branch: String): String =
  "chore(skill-bill): remediation checkpoint on '$branch' before mutating-phase re-entry"

private fun remediationCheckpointBlockedReason(branch: String, error: String): String =
  "Feature-task-runtime could not establish a remediation checkpoint on the feature branch '$branch' " +
    "before re-entering a mutating phase" + (if (error.isBlank()) "." else " ($error).") +
    " Refusing to re-enter a mutating phase on a dirty, non-reconcilable tree."

// The bounded fix-loop block reason shape, applied to a backward-edge cap: loud, with the loop id,
// the exhausted iteration count, the unresolved verdict, and the unresolved Blocker/Major findings
// that drove the loop so the operator sees exactly what never converged.
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
  // The failing acceptance criteria scoping an `audit_gap` re-entry; empty for a review_fix re-entry.
  val reentryGapCriteria: List<String> = emptyList(),
)

@Suppress("LongParameterList") // one cohesive launch context; bundling would only hide the seam
private fun runPhase(
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
    reentry = reentry,
  )
  // Pre-launch blocks: a non-retryable phase already durably blocked on a prior run (the record
  // survives ledger pruning, so the budget is never silently reset), or a missing required
  // upstream (never launch blind). Retryable fix-loop phases continue into runPhaseAttempts,
  // which resumes at the next durable attempt and still enforces the bounded budget.
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
      "Goal-subtask review state or durable raw evidence is malformed: ${error.message.orEmpty()}",
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
  onSuccess = { prepared -> when (prepared) {
    GoalSubtaskReviewInputPreparation.MissingState -> {
      blockedGoalReviewRun(run, observability, "Goal-subtask review state disappeared before review launch.")
    }
    is GoalSubtaskReviewInputBlocked -> {
      blockedGoalReviewRun(run, observability, prepared.reason)
    }
    is GoalSubtaskReviewInputReady ->
      GoalReviewRunReady(run.copy(goalReviewInput = prepared.input))
  } },
  onFailure = { error ->
    blockedGoalReviewRun(
      run,
      observability,
      "Goal-subtask review state or durable raw evidence is malformed: ${error.message.orEmpty()}",
    )
  },
)

private fun blockedGoalReviewRun(
  run: PhaseRun,
  observability: FeatureTaskRuntimeRunObservability,
  reason: String,
): GoalReviewRunPreparation {
  blockAndPersist(run, 1, reason, observability)
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
  persistPhase(run, iteration, STATUS_COMPLETED, finished = true, outputArtifact = output)
  observability.completed(run.phaseId, run.resolvedAgent.resolvedAgentId, iteration)
  return PhaseOutcome.completed(FeatureTaskRuntimePhaseOutput(run.phaseId, iteration, output))
}

// Returns a blocked outcome when the phase must block before launching, else null. A persisted
// blocked record re-blocks at the resumed iteration only when that phase is not retryable; a
// missing upstream blocks at attempt 1.
private fun preLaunchBlock(
  run: PhaseRun,
  state: FeatureTaskRuntimeRunState,
  observability: FeatureTaskRuntimeRunObservability,
): PhaseOutcome? {
  val persisted = state.persistedBlockedReason(run.phaseId)?.let { persistedReason ->
    val nextIteration = state.nextIteration(run.phaseId)
    if (FeatureTaskRuntimeFixLoopPolicy.participatesInFixLoop(run.phaseId)) {
      return@let null
    }
    val reason = persistedReason.ifBlank {
      "Phase '${run.phaseId}' is durably blocked from a prior run; the runtime re-blocks rather than relaunching."
    }
    nextIteration to reason
  }
  val missing = persisted ?: missingUpstream(run.declaration, state.outputs())?.let { missingIds ->
    1 to "Phase '${run.phaseId}' requires upstream output(s) ${missingIds.joinToString()} that are not " +
      "present; the runtime blocks rather than launching the phase blind."
  }
  return missing?.let { (attemptCount, reason) ->
    // Re-blocking a durably-blocked re-entered phase preserves the record's loop context so the
    // per-edge watermark is never dropped across resumes.
    val durable = state.recordFor(run.phaseId)
    blockAndPersist(
      run,
      attemptCount,
      reason,
      observability,
      loopId = durable?.loopId,
      edgeIteration = durable?.edgeIteration,
    )
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
  // The resumed iteration may already exceed the bounded budget (e.g. a fix-loop phase that
  // burned the cap on a prior run with no valid artifact). Block before launching rather than
  // relaunching the agent and bypassing the budget across resumes/crashes.
  // A re-entered phase must carry its loop context onto every block so a crash-at-blocked-re-entry
  // does not reset the per-edge watermark; blockAndPersistInPhase forwards run.reentry (null and
  // thus byte-for-byte unchanged on a forward launch). Now that implement is a bounded fix loop,
  // an exhausted-budget or schema-gate (incl. the reconciliation-gate rejection) block on a
  // re-entered mutating phase routes through this same loop-context-preserving path.
  // The budget index is RELATIVE to the current visit (a backward-edge re-visit restarts it), while
  // `iteration` stays the durable monotonic attempt index recorded per attempt. Within a single
  // visit they advance in lockstep, so the visit base is a fixed offset between them.
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
  // The prior attempt's schema-gate reason, threaded into the next attempt's prompt so a retry is a
  // corrective attempt rather than a blind re-roll of the identical prompt (null on the first attempt).
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
        )
      }
  }
  return outcome
}

// Persists a durable terminal blocked per-phase record (so blocked-ness survives ledger
// pruning), emits the blocked observability/ledger event, and returns the blocked outcome.
@Suppress("LongParameterList") // the optional loop context is additive to the existing block seam
private fun blockAndPersist(
  run: PhaseRun,
  attemptCount: Int,
  reason: String,
  observability: FeatureTaskRuntimeRunObservability,
  loopId: String? = null,
  edgeIteration: Int? = null,
): PhaseOutcome {
  recorder.recordPhaseState(
    FeatureTaskRuntimePhaseStateRequest(
      workflowId = run.request.workflowId,
      phaseId = run.phaseId,
      status = STATUS_BLOCKED,
      attemptCount = attemptCount.coerceAtLeast(1),
      resolvedAgentId = run.resolvedAgent.resolvedAgentId,
      finished = false,
      outputArtifact = null,
      blockedReason = reason,
      loopId = loopId,
      edgeIteration = edgeIteration,
    ),
    run.request.dbPathOverride,
  )
  observability.blocked(run.phaseId, run.resolvedAgent.resolvedAgentId, attemptCount.coerceAtLeast(1), reason)
  return PhaseOutcome.blocked(reason)
}

// An in-phase block (infra failure or a non-VALIDATE terminal-status output) on a re-entered phase
// must carry the runtime-minted loop context so the terminal blocked record does not overwrite the
// running record's loop id/edge iteration. Without it, resume reconstruction seeds edgeIterationByLoop
// at 0 and the backward edge would fire perEdgeCap more times after every crash-at-blocked-re-entry.
private fun blockAndPersistInPhase(
  run: PhaseRun,
  attemptCount: Int,
  reason: String,
  observability: FeatureTaskRuntimeRunObservability,
): PhaseOutcome = blockAndPersist(
  run,
  attemptCount,
  reason,
  observability,
  loopId = run.reentry?.loopId,
  edgeIteration = run.reentry?.edgeIteration,
)

// Returns SchemaInvalid only on schema-invalid output (caller consults the fix-loop policy).
// An infrastructure failure must block distinctly rather than be laundered through the schema
// gate, which would misreport it as bad output and burn the fix-loop budget on doomed retries.
@Suppress("LongParameterList") // cohesive single-attempt launch seam; the accumulator is additive
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
    return AttemptResult.settled(blockAndPersistInPhase(run, iteration, reason, observability))
  }
  return gateOutput(run, iteration, requireNotNull(launch.capturedStdout), observability)
}

private fun gateOutput(
  run: PhaseRun,
  iteration: Int,
  outputText: String,
  observability: FeatureTaskRuntimeRunObservability,
): AttemptResult = try {
  val outputMap = outputValidator.validateAndReadPhaseOutput(outputText, sourceLabel = run.phaseId)
  settleValidatedOutput(run, iteration, outputText, outputMap, observability)
} catch (error: InvalidFeatureTaskRuntimePhaseOutputSchemaError) {
  schemaInvalidAttempt(run, iteration, error.reason, observability)
}

private fun settleValidatedOutput(
  run: PhaseRun,
  iteration: Int,
  outputText: String,
  outputMap: Map<String, Any?>,
  observability: FeatureTaskRuntimeRunObservability,
): AttemptResult {
  terminalBlockedReasonFrom(run.phaseId, outputMap)?.let { reason ->
    return terminalOutputAttempt(run, iteration, reason, observability)
  }
  outputVerificationGateReason(run.phaseId, outputMap)?.let { reason ->
    return schemaInvalidAttempt(run, iteration, reason, observability)
  }
  return persistAcceptedOutput(run, iteration, outputText, outputMap, observability)
}

private fun terminalOutputAttempt(
  run: PhaseRun,
  iteration: Int,
  reason: String,
  observability: FeatureTaskRuntimeRunObservability,
): AttemptResult = if (run.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE) {
  AttemptResult.schemaInvalid(reason)
} else {
  AttemptResult.settled(blockAndPersistInPhase(run, iteration, reason, observability))
}

private fun outputVerificationGateReason(phaseId: String, outputMap: Map<String, Any?>): String? =
  mutatingReconciliationGateReason(phaseId, outputMap)
    ?: reviewVerificationSignalGateReason(phaseId, outputMap)
    ?: auditVerificationSignalGateReason(phaseId, outputMap)

private fun persistAcceptedOutput(
  run: PhaseRun,
  iteration: Int,
  outputText: String,
  outputMap: Map<String, Any?>,
  observability: FeatureTaskRuntimeRunObservability,
): AttemptResult {
  if (isGoalReviewRun(run)) {
    persistGoalReviewCompletion(run, iteration, outputText, outputMap, observability)?.let { outcome ->
      return AttemptResult.settled(outcome)
    }
  } else {
    persistPhase(run, iteration, STATUS_COMPLETED, finished = true, outputArtifact = outputText)
  }
  observability.completed(run.phaseId, run.resolvedAgent.resolvedAgentId, iteration)
  return AttemptResult.settled(
    PhaseOutcome.completed(FeatureTaskRuntimePhaseOutput(run.phaseId, iteration, outputText)),
  )
}

private fun persistGoalReviewCompletion(
  run: PhaseRun,
  iteration: Int,
  outputText: String,
  outputMap: Map<String, Any?>,
  observability: FeatureTaskRuntimeRunObservability,
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
    ),
  )
} else {
  AttemptResult.schemaInvalid(reason)
}

private fun persistPhase(run: PhaseRun, iteration: Int, status: String, finished: Boolean, outputArtifact: String?) {
  recorder.recordPhaseState(
    phaseStateRequest(run, iteration, status, finished, outputArtifact),
    run.request.dbPathOverride,
  )
}

private fun phaseStateRequest(
  run: PhaseRun,
  iteration: Int,
  status: String,
  finished: Boolean,
  outputArtifact: String?,
): FeatureTaskRuntimePhaseStateRequest = FeatureTaskRuntimePhaseStateRequest(
  workflowId = run.request.workflowId,
  phaseId = run.phaseId,
  status = status,
  attemptCount = iteration,
  resolvedAgentId = run.resolvedAgent.resolvedAgentId,
  finished = finished,
  outputArtifact = outputArtifact,
  loopId = run.reentry?.loopId,
  edgeIteration = run.reentry?.edgeIteration,
)

// Persist the briefing before launching so it is a durable handoff a consumer can read
// back, then deliver the same briefing to the agent as the launch prompt: the phase agent
// only ever sees what the prompt carries, so a persisted-but-undelivered briefing would
// leave it running the default goal-continuation flow and failing the schema gate.
private fun launchAndCapture(
  run: PhaseRun,
  state: FeatureTaskRuntimeRunState,
  priorSchemaFailure: String? = null,
  phaseTokenAccumulator: MutableMap<String, Pair<Int, Int>>? = null,
): LaunchResult {
  val handoff = FeatureTaskRuntimeHandoffContract.assembleHandoff(
    declaration = run.declaration,
    runInvariants = run.request.runInvariants,
    recordedOutputs = state.outputs(),
    drivingVerdict = run.reentry?.drivingVerdict,
    reentryGapCriteria = auditGapCriteriaFor(run, state),
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
          codeReviewMode = run.request.runInvariants.codeReviewMode,
          goalSubtaskReviewInput = run.goalReviewInput,
          specSource = run.specSource,
          priorSchemaFailure = priorSchemaFailure,
          specReference = run.request.runInvariants.specReference,
        ),
      ),
    ),
  )
  if (outcome is AgentRunLaunchFacts && phaseTokenAccumulator != null) {
    val inputTokens = estimateTokens(briefing.briefingText)
    val outputTokens = estimateTokens(outcome.stdout)
    phaseTokenAccumulator[run.phaseId] = Pair(inputTokens, outputTokens)
  }
  return reconcileLaunch(run.phaseId, outcome)
}

// The failing acceptance criteria scoping an audit_gap re-entry's briefing. The reopened `[plan,
// audit]` span re-enters `plan` (carried directly on the pending re-entry) then forward-launches
// `implement`; for both, the criteria are the ones the latest audit output recorded, so the
// re-implement addresses the same gaps as the re-plan. Empty for every other phase and on a forward
// run with no audit output yet, keeping those briefings byte-for-byte unchanged.
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

private fun reconcileLaunch(phaseId: String, outcome: AgentRunLaunchOutcome): LaunchResult = when (outcome) {
  is UnsupportedAgentRunLaunch -> LaunchResult.infraFailure(
    "Feature-task-runtime phase '$phaseId' could not launch an agent: ${outcome.reason}",
  )
  is AgentRunLaunchFacts -> infraFailureReason(phaseId, outcome)
    ?.let(LaunchResult::infraFailure)
    ?: LaunchResult.captured(outcome.stdout)
}

private data class PhaseRun(
  val phaseId: String,
  val declaration: FeatureTaskRuntimePhaseDeclaration,
  val resolvedAgent: FeatureTaskRuntimeResolvedPhaseAgent,
  val modelDirective: PhaseModelDirective?,
  val request: FeatureTaskRuntimeRunRequest,
  val specSource: SpecSource,
  // Set only when this launch is a backward-edge re-entry; null for an ordinary forward launch.
  val reentry: PendingReentry? = null,
  val goalReviewInput: GoalSubtaskReviewInput? = null,
)

private sealed interface LaunchResult {
  private data class Captured(val stdout: String) : LaunchResult
  private data class InfraFailure(val reason: String) : LaunchResult

  val capturedStdout: String? get() = (this as? Captured)?.stdout
  val infraFailureReason: String? get() = (this as? InfraFailure)?.reason

  companion object {
    fun captured(stdout: String): LaunchResult = Captured(stdout)
    fun infraFailure(reason: String): LaunchResult = InfraFailure(reason)
  }
}

// One launch attempt either settles the phase (completed or blocked) or fails the schema
// gate with the validator's reason, which the fix-loop caller threads into a terminal block.
private sealed interface AttemptResult {
  private data class Settled(val outcome: PhaseOutcome) : AttemptResult
  private data class SchemaInvalid(val validationReason: String) : AttemptResult

  val settledOutcome: PhaseOutcome? get() = (this as? Settled)?.outcome
  val schemaInvalidReason: String? get() = (this as? SchemaInvalid)?.validationReason

  companion object {
    fun settled(outcome: PhaseOutcome): AttemptResult = Settled(outcome)
    fun schemaInvalid(validationReason: String): AttemptResult = SchemaInvalid(validationReason)
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
