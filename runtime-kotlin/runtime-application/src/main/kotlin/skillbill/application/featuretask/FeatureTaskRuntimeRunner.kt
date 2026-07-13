package skillbill.application.featuretask

import me.tatarka.inject.annotations.Inject
import skillbill.application.goalrunner.stderrExcerpt
import skillbill.application.goalrunner.GoalSubtaskReviewSummaryReducer
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
import skillbill.review.CodeReviewExecutionMode
import skillbill.telemetry.estimation.estimateTokens
import skillbill.workflow.FeatureTaskRuntimePhaseOutputValidator
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

private const val PHASE_OUTPUT_STATUS_BLOCKED = "blocked"
private const val PHASE_OUTPUT_STATUS_FAILED = "failed"

/**
 * Runs the feature-task-runtime phase loop deterministically: for each ordered phase it
 * resolves the agent, assembles and persists the handoff, launches one agent synchronously,
 * gates the output through schema validation with a bounded fix loop, and persists per-phase
 * state, resuming from persisted records and blocking loudly on missing upstreams or failures.
 */
@Inject
@Suppress("TooManyFunctions") // single orchestration seam: the bounded-cyclic phase state machine
class FeatureTaskRuntimeRunner(
  private val subtaskLauncher: GoalRunnerSubtaskLauncher,
  private val recorder: FeatureTaskRuntimePhaseRecorder,
  private val goalContinuationRecorder: FeatureTaskRuntimeGoalContinuationRecorder,
  private val runInvariantsStore: FeatureTaskRuntimeRunInvariantsStore,
  private val outputValidator: FeatureTaskRuntimePhaseOutputValidator,
  private val phaseGates: FeatureTaskRuntimePhaseGates,
) {
  private val branchSetupRunner get() = phaseGates.branchSetupRunner
  private val planningStopper get() = phaseGates.planningStopper
  private val lifecycleTelemetry get() = phaseGates.lifecycleTelemetry
  private val specStatusProjector get() = phaseGates.specStatusProjector
  private val specSourceResolver get() = phaseGates.specGate.specSourceResolver

  // Refuses a foreign-mode workflow with a durable Blocked report instead of letting the mode guard
  // (getFeatureTaskWorkflowAsMode) throw uncaught, which would exit 1 with no terminal store outcome.
  private fun foreignModeWorkflowBlock(request: FeatureTaskRuntimeRunRequest): FeatureTaskRuntimeRunReport.Blocked? {
    val existingMode = recorder.existingWorkflowMode(request.workflowId, request.dbPathOverride)
    if (existingMode == null || existingMode == FeatureTaskWorkflowMode.RUNTIME) {
      return null
    }
    return FeatureTaskRuntimeRunReport.Blocked(
      issueKey = request.issueKey,
      workflowId = request.workflowId,
      featureSize = request.runInvariants.featureSize.name,
      lastIncompletePhase = FeatureTaskRuntimePhaseWorkflowDefinition.definition.defaultInitialStepId,
      blockedReason = "Cannot resume workflow '${request.workflowId}' in runtime mode: it was created in " +
        "'${existingMode.wireValue}' mode. A feature-task workflow is mode-scoped — prose and runtime are " +
        "not interchangeable. Finish this subtask in '${existingMode.wireValue}' mode, or reset the subtask " +
        "to start a fresh runtime attempt.",
      completedPhaseIds = emptyList(),
      resolvedBranch = null,
    )
  }

  @Suppress("LongMethod") // single runtime-owned orchestration seam; the token accumulator wiring is additive
  fun run(request: FeatureTaskRuntimeRunRequest): FeatureTaskRuntimeRunReport {
    foreignModeWorkflowBlock(request)?.let { return it }
    val persistedRunInvariants = runInvariantsStore.resolve(
      workflowId = request.workflowId,
      dbOverride = request.dbPathOverride,
    )
    val reportInvariants = persistedRunInvariants ?: request.runInvariants
    val persistedContinuation = runCatching {
      goalContinuationRecorder.continuation(
        request.workflowId,
        request.dbPathOverride,
      )
    }.getOrElse { error ->
      return goalContinuationPolicyBlockedReport(
        request = request,
        runInvariants = reportInvariants,
        reason = "Goal-continuation review persistence is malformed: ${error.message.orEmpty()}",
      )
    }
    val durableReviewBaseline = persistedContinuation?.let {
      runCatching {
        goalContinuationRecorder.reviewState(request.workflowId, request.dbPathOverride)
      }.getOrElse { error ->
        return goalContinuationPolicyBlockedReport(
          request = request,
          runInvariants = reportInvariants,
          reason = "Goal-continuation review state or durable raw evidence is malformed: ${error.message.orEmpty()}",
        )
      }
        ?.let { state -> GoalSubtaskReviewBaseline(state.reviewBaseSha, state.baselineUntrackedPaths) }
        ?: return goalContinuationPolicyBlockedReport(
          request = request,
          runInvariants = reportInvariants,
          reason = "Goal-continuation review state is missing; refusing to recreate its immutable review baseline.",
        )
    }
    val selectedReviewMode = when {
      persistedContinuation != null -> persistedContinuation.codeReviewMode
      request.goalContinuation != null -> request.goalContinuation.codeReviewMode
        ?: request.requestedCodeReviewMode
        ?: request.runInvariants.codeReviewMode
      else -> request.requestedCodeReviewMode ?: CodeReviewExecutionMode.AUTO
    }
    if (persistedContinuation != null) {
      goalContinuationConflict(request, persistedContinuation, requireNotNull(durableReviewBaseline))?.let { reason ->
        return goalContinuationPolicyBlockedReport(request, reportInvariants, reason)
      }
      if (persistedRunInvariants != null && persistedRunInvariants.codeReviewMode != selectedReviewMode) {
        return goalContinuationPolicyBlockedReport(
          request,
          persistedRunInvariants,
          "Goal-continuation review policy does not match the workflow's durable code-review mode.",
        )
      }
    } else if (request.goalContinuation != null) {
      newGoalContinuationConflict(request, selectedReviewMode)?.let { reason ->
        return goalContinuationPolicyBlockedReport(request, reportInvariants, reason)
      }
    } else if (persistedRunInvariants != null && request.requestedCodeReviewMode != null &&
      persistedRunInvariants.codeReviewMode != request.requestedCodeReviewMode
    ) {
      return goalContinuationPolicyBlockedReport(
        request,
        persistedRunInvariants,
        "Cannot change code-review mode on resume: workflow '${request.workflowId}' is pinned to " +
          "'${persistedRunInvariants.codeReviewMode.wireValue}', not " +
          "'${request.requestedCodeReviewMode.wireValue}'.",
      )
    }
    if (persistedRunInvariants != null &&
      (persistedContinuation != null || request.goalContinuation != null) &&
      persistedRunInvariants.codeReviewMode != selectedReviewMode
    ) {
      return goalContinuationPolicyBlockedReport(
        request,
        persistedRunInvariants,
        "Goal-continuation review policy does not match the workflow's durable code-review mode.",
      )
    }
    recorder.ensureWorkflowOpen(request.workflowId, request.sessionId, request.dbPathOverride, request.issueKey)
    if (persistedContinuation == null && request.goalContinuation != null) {
      val context = requireNotNull(request.goalContinuation)
      val recorded = goalContinuationRecorder.recordGoalContinuationState(
        workflowId = request.workflowId,
        continuation = FeatureTaskRuntimeGoalContinuationArtifact(
          issueKey = context.parentIssueKey,
          subtaskId = context.subtaskId,
          suppressPr = context.suppressPr,
          goalBranch = context.goalBranch,
          parentWorkflowId = context.parentWorkflowId,
          codeReviewMode = selectedReviewMode,
          parallelReviewAgent = context.parallelReviewAgent ?: request.parallelReviewAgent,
        ),
        reviewBaseline = requireNotNull(context.reviewBaseline),
        dbOverride = request.dbPathOverride,
      )
      if (!recorded) {
        return goalContinuationPolicyBlockedReport(
          request,
          reportInvariants,
          "Goal-continuation child state could not be persisted before freezing run invariants.",
        )
      }
    }
    val resolvedContinuation = runCatching {
      goalContinuationRecorder.continuation(request.workflowId, request.dbPathOverride)
    }.getOrElse { error ->
      return goalContinuationPolicyBlockedReport(
        request = request,
        runInvariants = reportInvariants,
        reason = "Goal-continuation review persistence is malformed after initialization: ${error.message.orEmpty()}",
      )
    }
    val resolvedBaseline = resolvedContinuation?.let {
      runCatching {
        goalContinuationRecorder.reviewState(request.workflowId, request.dbPathOverride)
      }.getOrElse { error ->
        return goalContinuationPolicyBlockedReport(
          request,
          reportInvariants,
          "Goal-continuation review state or durable raw evidence is malformed: ${error.message.orEmpty()}",
        )
      }
        ?.let { state -> GoalSubtaskReviewBaseline(state.reviewBaseSha, state.baselineUntrackedPaths) }
        ?: return goalContinuationPolicyBlockedReport(
          request,
          reportInvariants,
          "Goal-continuation review state is missing; refusing to recreate its immutable review baseline.",
        )
    }
    val proposedRunInvariants = persistedRunInvariants ?: request.runInvariants.copy(
      codeReviewMode = resolvedContinuation?.codeReviewMode ?: selectedReviewMode,
    )
    val durableRunInvariants = runInvariantsStore.resolve(
      workflowId = request.workflowId,
      dbOverride = request.dbPathOverride,
      proposed = proposedRunInvariants,
    ) ?: proposedRunInvariants
    if (resolvedContinuation != null && durableRunInvariants.codeReviewMode != resolvedContinuation.codeReviewMode) {
      return goalContinuationPolicyBlockedReport(
        request,
        durableRunInvariants,
        "Goal-continuation review policy does not match the workflow's durable code-review mode.",
      )
    }
    val effectiveGoalContinuation = resolvedContinuation?.let { durable ->
      FeatureTaskRuntimeGoalContinuationContext(
        parentIssueKey = durable.issueKey,
        subtaskId = durable.subtaskId,
        goalBranch = durable.goalBranch,
        suppressPr = durable.suppressPr,
        parentWorkflowId = durable.parentWorkflowId,
        lastResumableStep = request.goalContinuation?.lastResumableStep,
        codeReviewMode = durable.codeReviewMode,
        parallelReviewAgent = durable.parallelReviewAgent,
        reviewBaseline = requireNotNull(resolvedBaseline),
      )
    }
    val runRequest = request.copy(
      runInvariants = durableRunInvariants,
      parallelReviewAgent = resolvedContinuation?.parallelReviewAgent ?: request.parallelReviewAgent,
      goalContinuation = effectiveGoalContinuation,
    )
    // Resolve the persisted spec_source stamp once per run (artifact-only, additive — never config).
    // It gates the commit-exclusion directive and terminal-success scratch deletion; local-mode
    // resolves to LOCAL and keeps every path byte-for-byte unchanged.
    val specSource = specSourceResolver.resolve(
      repoRoot = runRequest.repoRoot,
      specReference = runRequest.runInvariants.specReference,
      isGoalContinuation = isGoalContinuationRun(runRequest),
    )
    runRequest.eventSink.emit(
      FeatureTaskRuntimeRunEvent.RunStarted(runRequest.workflowId, runRequest.runInvariants.featureSize.name),
    )
    // Runtime-owned lifecycle telemetry: the runtime mints and emits the started/finished events from
    // its own per-phase records (AC4), never the agent. Per-phase records and ledger remain the
    // authoritative observability source and are unchanged; this telemetry is additive (AC6). Every
    // telemetry call is failure-isolated (logged, never swallowed silently) so a telemetry fault can
    // neither abort the run nor falsely-fail a successful run, and the run exception always propagates.
    // The telemetry seam owns failure isolation: started/finished/finishedError each log on failure and
    // never throw, so a telemetry fault can neither abort the run nor falsely-fail a successful run.
    val telemetrySessionId = lifecycleTelemetry.started(runRequest)
    val observability = FeatureTaskRuntimeRunObservability(recorder, runRequest)
    // Best-effort per-phase outcomes for the finished events; resolved lazily inside the telemetry
    // seam's failure isolation so even loading them cannot abort or falsely-fail the run.
    val phaseOutcomes = {
      recorder.loadPhaseRecords(runRequest.workflowId, runRequest.dbPathOverride)
        .orEmpty()
        .mapValues { (_, record) -> record.status }
    }
    // The durable review-fix loop iteration count for the finished telemetry (AC6): the highest
    // `review_fix` per-edge watermark from the LOOP_EDGE ledger (0 when the loop never fired).
    // Sourced from the runtime's own durable ledger, never agent-self-reported, and resolved lazily
    // inside the telemetry seam's failure isolation so loading it cannot abort or falsely-fail the run.
    val reviewFixIterationCount = { loadReviewFixIterationCount(runRequest) }
    // The durable audit-gap loop iteration count for the finished telemetry (AC7): the highest
    // `audit_gap` per-edge watermark from the LOOP_EDGE ledger (0 when the loop never fired), sourced
    // from the runtime's own ledger and resolved lazily inside the telemetry seam's failure isolation.
    val auditGapIterationCount = { loadAuditGapIterationCount(runRequest) }
    val transitions = transitionsFor(runRequest)
    val phaseTokenAccumulator: MutableMap<String, Pair<Int, Int>> = mutableMapOf()
    val report = runCatching {
      val state = RunState(
        recorder.loadPhaseRecords(runRequest.workflowId, runRequest.dbPathOverride).orEmpty(),
        transitions,
      )
      val loop = RunLoop(runRequest, state, observability, specSource, transitions, phaseTokenAccumulator)
      loop.drive()
      loop.report()
    }.onFailure { error ->
      // An exception escaping the loop (recorder write, launcher RuntimeException, validator
      // non-schema error) would otherwise leave a dangling started-but-never-finished session.
      // Emit the error terminal from best-effort per-phase records, then rethrow the original.
      lifecycleTelemetry.finishedError(
        telemetrySessionId,
        phaseOutcomes,
        reviewFixIterationCount,
        auditGapIterationCount,
        runRequest.dbPathOverride,
        phaseTokenData = { serializeTokenData(phaseTokenAccumulator) },
      )
    }.getOrThrow()
    val terminalReport =
      persistGoalContinuationOutcome(goalContinuationRecorder, recorder, phaseGates.gitOperations, runRequest, report)
    phaseGates.specGate.finalizeSingleSpecOnTerminal(runRequest, terminalReport, specSource, ::finalizingAgentId)
    lifecycleTelemetry.finished(
      telemetrySessionId,
      terminalReport,
      phaseOutcomes,
      reviewFixIterationCount,
      auditGapIterationCount,
      runRequest.dbPathOverride,
      phaseTokenData = { serializeTokenData(phaseTokenAccumulator) },
    )
    return terminalReport
  }

  // The highest per-iteration `review_fix` fix count for finished telemetry (AC6), read from the
  // runtime-owned LOOP_EDGE ledger (0 when the loop never fired). The review_fix counter resets to 1
  // per audit_gap iteration (AC5), so each ledger segment runs 1..n monotonically before the next
  // iteration restarts at 1; the max edge_iteration across the ledger is therefore the largest
  // single-iteration fix count, NOT a cross-iteration sum — it never over-reports a converged run.
  private fun loadReviewFixIterationCount(request: FeatureTaskRuntimeRunRequest): Int =
    recorder.loadPhaseLedger(request.workflowId, request.dbPathOverride)
      .orEmpty()
      .filter {
        it.action == FeatureTaskRuntimePhaseLedgerAction.LOOP_EDGE &&
          it.loopId == FeatureTaskRuntimePhaseWorkflowDefinition.REVIEW_FIX_LOOP_ID
      }
      .mapNotNull { it.edgeIteration }
      .maxOrNull()
      ?: 0

  // The highest durable `audit_gap` per-edge iteration recorded on the LOOP_EDGE ledger (0 when the
  // loop never fired): the runtime-owned audit->plan iteration count for finished telemetry (AC7).
  private fun loadAuditGapIterationCount(request: FeatureTaskRuntimeRunRequest): Int =
    recorder.loadPhaseLedger(request.workflowId, request.dbPathOverride)
      .orEmpty()
      .filter {
        it.action == FeatureTaskRuntimePhaseLedgerAction.LOOP_EDGE &&
          it.loopId == FeatureTaskRuntimePhaseWorkflowDefinition.AUDIT_GAP_LOOP_ID
      }
      .mapNotNull { it.edgeIteration }
      .maxOrNull()
      ?: 0

  // The Seam A ledger-derived finalizing agent for the single-spec completion-time `Agent:` line; the
  // spec gate invokes this lazily only when it decides to write (terminal, non-goal-continuation run).
  private fun finalizingAgentId(request: FeatureTaskRuntimeRunRequest): String? =
    agentAttributionFromPhaseState(recorder, request.workflowId, request.dbPathOverride).finalizingAgentId

  // Drives the ordered phase loop for one run, owning the run-scoped resolved branch so the loop
  // body stays a single advance() call. The resolved branch is null until the first file-mutating
  // phase forces setup, which re-attaches the persisted branch on resume (never force-switching) so
  // a re-run never creates a second or divergent branch.
  @Suppress("TooManyFunctions") // the cohesive single-loop state-machine driver
  private inner class RunLoop(
    private val request: FeatureTaskRuntimeRunRequest,
    private val state: RunState,
    private val observability: FeatureTaskRuntimeRunObservability,
    private val specSource: SpecSource,
    // The forward-only production declaration, truncated to the goal-continuation boundary, or the
    // test-only synthetic cyclic override. The single orchestration seam consumes this declaration
    // rather than iterating a fixed list; an edge-free declaration is the strict forward pipeline.
    // Resolved once in run() so RunState reconstructs its per-visit budget baselines from the same
    // topology the driver loops on.
    private val transitions: FeatureTaskRuntimeTransitionDeclaration,
    private val phaseTokenAccumulator: MutableMap<String, Pair<Int, Int>>,
  ) {
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

    private fun reconcileCompletedGoalReviewPass(phaseId: String): String? {
      if (phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW ||
        !isGoalContinuationRun(request) ||
        !state.isComplete(phaseId)
      ) {
        return null
      }
      val reviewState = runCatching {
        goalContinuationRecorder.reviewState(request.workflowId, request.dbPathOverride)
      }.getOrElse { error ->
        return "Goal-subtask review state is malformed while reconciling a completed review pass: ${error.message.orEmpty()}"
      } ?: return "Goal-subtask review state is missing while reconciling a completed review pass."
      if (reviewState.reservedPassNumber == null) {
        return null
      }
      val output = state.outputFor(phaseId)?.payload
        ?: return "Completed goal-subtask review has no durable output to reconcile its reserved pass."
      val outputMap = runCatching {
        outputValidator.validateAndReadPhaseOutput(output, sourceLabel = phaseId)
      }.getOrElse { error ->
        return "Completed goal-subtask review output cannot reconcile its reserved pass: ${error.message.orEmpty()}"
      }
      val findings = GoalSubtaskReviewSummaryReducer.fromOutput(outputMap)
      val outcome = GoalSubtaskReviewSummaryReducer.outcomeFor(outputMap, findings)
      return if (goalContinuationRecorder.completeGoalReviewPass(
          workflowId = request.workflowId,
          verdict = outcome.verdict,
          unresolvedFindingCount = outcome.unresolvedFindingCount,
          findings = findings,
          rawReviewResult = output,
          dbOverride = request.dbPathOverride,
        ) == null
      ) {
        "Completed goal-subtask review could not persist its reserved pass."
      } else {
        null
      }
    }

    private fun carriedForwardGoalReviewSettlement(): PhaseSettlement? {
      val reviewState = runCatching {
        goalContinuationRecorder.reviewState(request.workflowId, request.dbPathOverride)
      }.getOrElse { error ->
        blockAt(
          FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW,
          "Goal-subtask review pass budget is exhausted but its durable raw review result is malformed: ${error.message.orEmpty()}",
        )
        return PhaseSettlement.stop()
      } ?: return null
      if (!reviewState.reviewCapReached && reviewState.completedPassCount < 2) return null
      val rawResult = runCatching {
        goalContinuationRecorder.lastGoalReviewResult(request.workflowId, request.dbPathOverride)
      }.getOrElse { error ->
        blockAt(
          FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW,
          "Goal-subtask review pass budget is exhausted but its durable raw review result is malformed: ${error.message.orEmpty()}",
        )
        return PhaseSettlement.stop()
      } ?: run {
        blockAt(
          FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW,
          "Goal-subtask review pass budget is exhausted but its durable raw review result is missing.",
        )
        return PhaseSettlement.stop()
      }
      runCatching {
        outputValidator.validateAndReadPhaseOutput(rawResult, sourceLabel = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW)
      }.getOrElse { error ->
        blockAt(
          FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW,
          "Goal-subtask review pass budget is exhausted but its durable raw review result is malformed: ${error.message.orEmpty()}",
        )
        return PhaseSettlement.stop()
      }
      return PhaseSettlement.completed(
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW,
        requireNotNull(reviewState.passResults.lastOrNull()).verdict,
      )
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
    state: RunState,
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
      is GoalReviewRunPreparation.Ready -> runPhaseAttempts(prepared.run, state, observability, phaseTokenAccumulator)
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
  ): GoalReviewRunPreparation {
    if (run.phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW || !isGoalContinuationRun(run.request)) {
      return GoalReviewRunPreparation.Ready(run)
    }
    val reservation = runCatching {
      goalContinuationRecorder.reserveGoalReviewPass(run.request.workflowId, run.request.dbPathOverride)
    }.getOrElse { error ->
      blockAndPersist(
        run,
        1,
        "Goal-subtask review state or durable raw evidence is malformed: ${error.message.orEmpty()}",
        observability,
      )
      return GoalReviewRunPreparation.Blocked
    }
    when (reservation) {
      GoalSubtaskReviewPassReservation.MissingState -> {
        blockAndPersist(
          run,
          1,
          "Goal-subtask review state is missing; review_base_sha must be captured before implementation and cannot be substituted.",
          observability,
        )
        return GoalReviewRunPreparation.Blocked
      }
      is GoalSubtaskReviewPassReservation.InFlight -> {}
      is GoalSubtaskReviewPassReservation.CarryForward -> return GoalReviewRunPreparation.CarryForward
      is GoalSubtaskReviewPassReservation.Reserved -> Unit
    }
    val prepared = runCatching {
      goalContinuationRecorder.buildGoalReviewInput(
        workflowId = run.request.workflowId,
        gitOperations = phaseGates.gitOperations,
        repoRoot = run.request.repoRoot,
        dbOverride = run.request.dbPathOverride,
      )
    }.getOrElse { error ->
      blockAndPersist(
        run,
        1,
        "Goal-subtask review state or durable raw evidence is malformed: ${error.message.orEmpty()}",
        observability,
      )
      return GoalReviewRunPreparation.Blocked
    }
    return when (prepared) {
      GoalSubtaskReviewInputPreparation.MissingState -> {
        blockAndPersist(run, 1, "Goal-subtask review state disappeared before review launch.", observability)
        GoalReviewRunPreparation.Blocked
      }
      is GoalSubtaskReviewInputPreparation.Blocked -> {
        blockAndPersist(run, 1, prepared.reason, observability)
        GoalReviewRunPreparation.Blocked
      }
      is GoalSubtaskReviewInputPreparation.Ready -> GoalReviewRunPreparation.Ready(run.copy(goalReviewInput = prepared.input))
    }
  }

  private fun settleCarriedForwardGoalReview(
    run: PhaseRun,
    state: RunState,
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
    state: RunState,
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
    state: RunState,
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
    state: RunState,
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
  ): AttemptResult {
    // Schema-invalid output carries the validator's reason out so a terminal block is
    // diagnosable from the persisted blocked_reason, not only from transient JVM logs.
    return try {
      val outputMap = outputValidator.validateAndReadPhaseOutput(outputText, sourceLabel = run.phaseId)
      terminalBlockedReasonFrom(run.phaseId, outputMap)?.let { reason ->
        if (run.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE) {
          AttemptResult.schemaInvalid(reason)
        } else {
          AttemptResult.settled(blockAndPersistInPhase(run, iteration, reason, observability))
        }
        // A mutating phase that reports `completed` but omits the reconciliation report has not
        // proven it reconciled the tree to target; the idempotency contract is verified, not assumed,
        // so route the silent skip through the SAME loud schema-gate failure path as bad output. The
        // fix loop then retries and ultimately blocks rather than advancing on an unverified mutation.
        // A review that reports `completed` but carries NEITHER a structured verdict NOR a findings
        // array has not emitted any verification signal: prose alone must not advance past a
        // Blocker/Major (AC1). Route the missing signal through the SAME loud schema-gate failure path
        // so the fix loop retries and ultimately blocks rather than silently advancing to audit. An
        // explicit empty findings array or an explicit verdict still legitimately advances.
      } ?: mutatingReconciliationGateReason(run.phaseId, outputMap)?.let { reason ->
        schemaInvalidAttempt(run, iteration, reason, observability)
      } ?: reviewVerificationSignalGateReason(run.phaseId, outputMap)?.let { reason ->
        schemaInvalidAttempt(run, iteration, reason, observability)
      } ?: auditVerificationSignalGateReason(run.phaseId, outputMap)?.let { reason ->
        schemaInvalidAttempt(run, iteration, reason, observability)
      } ?: run {
        if (run.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW && isGoalContinuationRun(run.request)) {
          val findings = GoalSubtaskReviewSummaryReducer.fromOutput(outputMap)
          val outcome = GoalSubtaskReviewSummaryReducer.outcomeFor(outputMap, findings)
          val completed = runCatching {
            recorder.completeGoalReviewPhase(
              request = phaseStateRequest(
                run = run,
                iteration = iteration,
                status = STATUS_COMPLETED,
                finished = true,
                outputArtifact = outputText,
              ),
              verdict = outcome.verdict,
              unresolvedFindingCount = outcome.unresolvedFindingCount,
              findings = findings,
              rawReviewResult = outputText,
              dbOverride = run.request.dbPathOverride,
            )
          }.getOrElse { error ->
            return AttemptResult.settled(
              blockAndPersistInPhase(
                run,
                iteration,
                "Goal-subtask review could not atomically persist its pass and completed phase: ${error.message.orEmpty()}",
                observability,
              ),
            )
          }
          if (!completed) {
            return AttemptResult.settled(
              blockAndPersistInPhase(
                run,
                iteration,
                "Goal-subtask review could not atomically persist its reserved pass and completed phase.",
                observability,
              ),
            )
          }
        } else {
          persistPhase(run, iteration, STATUS_COMPLETED, finished = true, outputArtifact = outputText)
        }
        observability.completed(run.phaseId, run.resolvedAgent.resolvedAgentId, iteration)
        AttemptResult.settled(
          PhaseOutcome.completed(FeatureTaskRuntimePhaseOutput(run.phaseId, iteration, outputText)),
        )
      }
    } catch (error: InvalidFeatureTaskRuntimePhaseOutputSchemaError) {
      schemaInvalidAttempt(run, iteration, error.reason, observability)
    }
  }

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
        "Goal-subtask review output failed schema validation after its reserved pass; refusing an unaccounted relaunch. $reason",
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
    state: RunState,
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
  private fun auditGapCriteriaFor(run: PhaseRun, state: RunState): List<String> {
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
    data class Ready(val run: PhaseRun) : GoalReviewRunPreparation
    data object CarryForward : GoalReviewRunPreparation
    data object Blocked : GoalReviewRunPreparation
  }

  // `completed` is derived from record status while `outputs` carries only records with a
  // validated artifact, so a complete-but-output-less upstream is absent from the handoff and
  // triggers a loud missing-upstream block instead of a blind launch. The loaded per-phase
  // records (not just outputs) are retained so the bounded fix loop resumes from the durable
  // attempt count rather than resetting to iteration 1 on resume/crash. Single-threaded.
  @Suppress("TooManyFunctions") // the single in-memory resume/loop state projection
  private class RunState(
    private val initialRecords: Map<String, FeatureTaskRuntimePhaseRecord>,
    private val transitions: FeatureTaskRuntimeTransitionDeclaration,
  ) {
    // A legacy PLAN completed without its now-required PREPLAN predecessor is invalidated up front so
    // the loop re-runs PLAN rather than honouring a pre-PREPLAN completion.
    private val completed: MutableSet<String> =
      initialRecords.values
        .filter { it.status == STATUS_COMPLETED }
        .map { it.phaseId }
        .toMutableSet()
        .also(::invalidateLegacyPlanWithoutPreplan)
    private val outputs: MutableList<FeatureTaskRuntimePhaseOutput> =
      initialRecords.values
        .mapNotNull(::recordToOutput)
        .filterNot { it.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN && it.phaseId !in completed }
        .toMutableList()
    private val priorRecords: MutableSet<String> = initialRecords.keys.toMutableSet()

    // Durable per-phase attempt count from the loaded record (0 when no record exists).
    private val persistedAttemptCounts: MutableMap<String, Int> =
      initialRecords.mapValues { (_, record) -> record.attemptCount }.toMutableMap()

    // Phases already persisted with a durable genuine-phase-agent blocked record. Branch-setup-origin
    // blocked records (resolvedAgentId == BRANCH_SETUP_AGENT_ID) are deliberately excluded: branch
    // setup is re-attemptable on resume, so a recoverable branch-setup failure must never short-circuit
    // a real phase launch once setup succeeds. Genuine non-fix-loop phase-agent blocks still re-block
    // on resume; fix-loop phase blocks relaunch through the bounded attempt policy.
    private val blockedRecords: MutableMap<String, String> = initialRecords
      .filterValues { it.status == STATUS_BLOCKED && it.resolvedAgentId != BRANCH_SETUP_AGENT_ID }
      .mapValues { (_, record) -> record.blockedReason.orEmpty() }
      .toMutableMap()

    // Phases carrying a durable branch-setup-origin blocked record from a prior run. Tracked
    // separately from blockedRecords (which only holds genuine phase-agent blocks) so the runner
    // can supersede the stale durable record once branch setup recovers on resume.
    private val branchSetupBlockedPhases: MutableSet<String> = initialRecords
      .filterValues { it.status == STATUS_BLOCKED && it.resolvedAgentId == BRANCH_SETUP_AGENT_ID }
      .keys
      .toMutableSet()

    // The per-edge iteration counter, keyed by loop id, DISTINCT from persistedAttemptCounts. Seeded
    // from the durable per-phase records' edge context (the resume watermark) so the cap survives
    // crashes; advanced as the runtime mints each backward-edge re-entry within the live run.
    private val edgeIterationByLoop: MutableMap<String, Int> = initialRecords.values
      .mapNotNull { record -> record.loopId?.let { loopId -> record.edgeIteration?.let { loopId to it } } }
      .groupBy({ it.first }, { it.second })
      .mapValues { (_, iterations) -> iterations.max() }
      .toMutableMap()

    // Loops the live run has already advanced this run, so the resume-entry cap guard does not
    // re-fire against a stale durable snapshot once the live machine owns the loop.
    private val liveClaimedLoops: MutableSet<String> = mutableSetOf()

    // Per-phase same-phase fix-loop budget baseline: the attempt watermark a backward-edge re-visit
    // starts from, so the schema-retry budget restarts each visit (the cross-visit bound is the
    // per-edge backward cap). 0 for a phase that has never been re-entered.
    //
    // Reconstructed from durable state on resume: live reopenForReentry resets this base on every
    // edge fire, but that map is in-memory only, so after a crash a re-entered phase would otherwise
    // start from an empty base and the per-visit budget index would degrade to the absolute monotonic
    // attempt_count — prematurely re-blocking a loop (e.g. a re-entered review whose attempt_count
    // already accrued across review_fix visits) instead of resuming to convergence (AC5). For every
    // fired loop (a durable per-edge watermark exists and the live machine has not yet claimed it),
    // seed each non-completed phase in the reopened span (destination through source) from its durable
    // attempt watermark, mirroring reopenForReentry's base = nextIteration - 1. The within-visit
    // schema-retry budget still bounds at MAX_FIX_LOOP_ITERATIONS; attempt_count stays monotonic.
    private val fixLoopBudgetBaseByPhase: MutableMap<String, Int> = reconstructFixLoopBudgetBases()

    fun outputs(): List<FeatureTaskRuntimePhaseOutput> = outputs.toList()

    // The durable per-phase record as loaded at resume (null when none); carries the latest edge
    // context for the cap-on-resume guard.
    fun recordFor(phaseId: String): FeatureTaskRuntimePhaseRecord? = initialRecords[phaseId]

    // The current per-edge iteration count for the loop (0 when the edge has not yet fired).
    fun edgeIterationCount(loopId: String): Int = edgeIterationByLoop[loopId] ?: 0

    // Records the runtime-minted per-edge iteration for the loop and claims it for the live run.
    fun recordEdgeIteration(loopId: String, edgeIteration: Int) {
      edgeIterationByLoop[loopId] = edgeIteration
      liveClaimedLoops += loopId
    }

    // Clears a nested loop's live per-edge counter so its next fire restarts at iteration 1. Used when a
    // wider backward edge reopens a span containing the nested loop's source: that re-run is a FRESH
    // verification cycle, so the nested cap (e.g. review_fix) counts within the new outer iteration, not
    // across the whole run (AC5 — the review_fix counter resets per audit-gap iteration; the audit_gap
    // counter is independent and never reset).
    fun resetEdgeIteration(loopId: String) {
      edgeIterationByLoop.remove(loopId)
      liveClaimedLoops.remove(loopId)
    }

    fun isLoopLiveClaimed(loopId: String): Boolean = loopId in liveClaimedLoops

    // A backward edge re-enters the phase: drop its completed marker so the driver relaunches it.
    // Its prior validated output stays in `outputs` so latest-iteration resolution still works.
    // Reset the same-phase fix-loop budget baseline to the current attempt watermark: a backward-edge
    // re-visit is a FRESH verification (e.g. a re-review), not a schema-retry of the prior visit, so it
    // must restart the per-visit schema-retry budget. The durable attempt_count stays monotonic for
    // telemetry; the cross-visit bound is the per-edge backward cap, not the same-phase budget.
    fun reopenForReentry(phaseId: String) {
      completed.remove(phaseId)
      fixLoopBudgetBaseByPhase[phaseId] = maxOf(nextIteration(phaseId) - 1, 0)
    }

    // The 1-based same-phase fix-loop iteration relative to the current visit's budget baseline, so the
    // schema-retry budget counts only attempts within this visit, not backward-edge re-visits.
    fun fixLoopIterationFor(phaseId: String, absoluteIteration: Int): Int =
      absoluteIteration - (fixLoopBudgetBaseByPhase[phaseId] ?: 0)

    // Resume reconstruction of the per-visit budget baselines (see fixLoopBudgetBaseByPhase). For every
    // backward edge whose loop durably fired (a per-edge watermark exists), seed each non-completed
    // phase in the reopened span (destination through source) from its durable attempt watermark,
    // mirroring reopenForReentry. A completed phase is excluded: it does not re-enter runPhaseAttempts,
    // so its baseline is irrelevant and a live edge fire will reset it through reopenForReentry anyway.
    private fun reconstructFixLoopBudgetBases(): MutableMap<String, Int> {
      val bases = mutableMapOf<String, Int>()
      transitions.backwardEdges.forEach { edge ->
        if ((edgeIterationByLoop[edge.loopId] ?: 0) <= 0) {
          return@forEach
        }
        reopenedSpan(edge).forEach { phaseId ->
          if (phaseId !in completed) {
            bases[phaseId] = maxOf(nextIteration(phaseId) - 1, 0)
          }
        }
      }
      return bases
    }

    // The forward span an edge reopens, destination through source inclusive (mirrors the live
    // recordBackwardEdge span); falls back to the destination alone when the indices do not bracket.
    private fun reopenedSpan(edge: FeatureTaskRuntimeBackwardEdge): List<String> {
      val destinationIndex = transitions.forwardPhaseIds.indexOf(edge.destinationPhaseId)
      val sourceIndex = transitions.forwardPhaseIds.indexOf(edge.fromPhaseId)
      return if (destinationIndex in 0..sourceIndex) {
        transitions.forwardPhaseIds.subList(destinationIndex, sourceIndex + 1)
      } else {
        listOf(edge.destinationPhaseId)
      }
    }

    // The verdict the transition function reads, derived from the phase's latest completed output. A
    // top-level `verdict` wire string is honored for any phase; the `review` phase additionally
    // CLASSIFIES from its carried findings so prose alone cannot advance past a Blocker/Major (any
    // unresolved Blocker/Major forces CHANGES_REQUESTED). A phase with no verifying output is ADVANCE.
    fun verdictFor(phaseId: String): FeatureTaskRuntimeVerdict {
      val output = outputFor(phaseId) ?: return FeatureTaskRuntimeVerdict.ADVANCE
      val outputObject = JsonSupport.parseObjectOrNull(output.payload)
        ?.let(JsonSupport::jsonElementToValue)
        ?.let(JsonSupport::anyToStringAnyMap)
      val wireVerdict = (outputObject?.get("verdict") as? String)
        ?.takeIf(String::isNotBlank)
        ?.let(FeatureTaskRuntimeVerdict::fromWire)
      return when (phaseId) {
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW -> reviewClassification(outputObject, wireVerdict)
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT -> auditClassification(outputObject, wireVerdict)
        else -> wireVerdict ?: FeatureTaskRuntimeVerdict.ADVANCE
      }
    }

    // A review CLASSIFIES from its carried findings so prose alone cannot advance past a Blocker/Major:
    // any unresolved Blocker/Major forces CHANGES_REQUESTED; otherwise the wire/derived verdict (or
    // ADVANCE) applies.
    private fun reviewClassification(
      outputObject: Map<String, Any?>?,
      wireVerdict: FeatureTaskRuntimeVerdict?,
    ): FeatureTaskRuntimeVerdict {
      val reviewVerdict = reviewVerdictFrom(outputObject)
      return if (reviewVerdict?.unresolvedFindings?.isNotEmpty() == true) {
        FeatureTaskRuntimeVerdict.CHANGES_REQUESTED
      } else {
        wireVerdict ?: reviewVerdict?.verdict ?: FeatureTaskRuntimeVerdict.ADVANCE
      }
    }

    // An audit CLASSIFIES from its unmet criteria so prose alone cannot advance past an unmet
    // acceptance criterion: any unmet criterion forces GAPS_FOUND; otherwise the wire/derived verdict
    // (or ADVANCE) applies.
    private fun auditClassification(
      outputObject: Map<String, Any?>?,
      wireVerdict: FeatureTaskRuntimeVerdict?,
    ): FeatureTaskRuntimeVerdict {
      val auditVerdict = auditVerdictFrom(outputObject)
      return if (auditVerdict?.unmetCriteria?.isNotEmpty() == true) {
        FeatureTaskRuntimeVerdict.GAPS_FOUND
      } else {
        wireVerdict ?: auditVerdict?.verdict ?: FeatureTaskRuntimeVerdict.ADVANCE
      }
    }

    // The structured review verdict decoded from a parsed review output's `produced_outputs.findings`
    // array (each entry a `{severity, message}` map). Null when no findings array is present, so the
    // caller falls back to the wire verdict / ADVANCE. The raw map never leaves this seam: it is
    // decoded straight into the domain finding/verdict types.
    fun reviewVerdictFrom(outputObject: Map<String, Any?>?): FeatureTaskRuntimeReviewVerdict? {
      val findingsRaw = outputObject?.get("produced_outputs")
        ?.let(JsonSupport::anyToStringAnyMap)
        ?.get(FeatureTaskRuntimeVerificationSignalKeys.REVIEW_FINDINGS) as? List<*>
        ?: return null
      val findings = findingsRaw.mapNotNull { entry ->
        val map = JsonSupport.anyToStringAnyMap(entry) ?: return@mapNotNull null
        val severity = (map["severity"] as? String)?.takeIf(String::isNotBlank) ?: return@mapNotNull null
        val message = (map["message"] as? String)?.takeIf(String::isNotBlank) ?: return@mapNotNull null
        FeatureTaskRuntimeReviewFinding(FeatureTaskRuntimeReviewSeverity.fromWire(severity), message)
      }
      return FeatureTaskRuntimeReviewVerdict(findings)
    }

    // The unresolved Blocker/Major findings the phase's latest review output carries (empty when none).
    fun unresolvedReviewFindings(phaseId: String): List<FeatureTaskRuntimeReviewFinding> = outputFor(phaseId)
      ?.let { JsonSupport.parseObjectOrNull(it.payload) }
      ?.let(JsonSupport::jsonElementToValue)
      ?.let(JsonSupport::anyToStringAnyMap)
      ?.let(::reviewVerdictFrom)
      ?.unresolvedFindings
      .orEmpty()

    // The structured audit verdict decoded from a parsed audit output's
    // `produced_outputs.unmet_criteria` (or `failing_criteria`) array, each entry a string or a
    // `{message|criterion}` map. Null when no such array is present, so the caller falls back to the
    // wire verdict / ADVANCE. The raw map never leaves this seam: it is decoded straight into the
    // domain gap/verdict types.
    fun auditVerdictFrom(outputObject: Map<String, Any?>?): FeatureTaskRuntimeAuditVerdict? {
      val producedOutputs = outputObject?.get("produced_outputs")?.let(JsonSupport::anyToStringAnyMap)
      val gapsRaw = (
        producedOutputs?.get(FeatureTaskRuntimeVerificationSignalKeys.AUDIT_UNMET_CRITERIA)
          ?: producedOutputs?.get(FeatureTaskRuntimeVerificationSignalKeys.AUDIT_FAILING_CRITERIA_ALIAS)
        ) as? List<*>
        ?: return null
      val gaps = gapsRaw.mapNotNull { entry ->
        val message = (entry as? String)?.takeIf(String::isNotBlank)
          ?: JsonSupport.anyToStringAnyMap(entry)?.let { map ->
            ((map["message"] ?: map["criterion"]) as? String)?.takeIf(String::isNotBlank)
          }
          ?: return@mapNotNull null
        FeatureTaskRuntimeAuditCriterionGap(message)
      }
      return FeatureTaskRuntimeAuditVerdict(gaps)
    }

    // The unmet acceptance-criteria gap messages the phase's latest audit output carries (empty when none).
    fun unmetAuditCriteria(phaseId: String): List<String> = outputFor(phaseId)
      ?.let { JsonSupport.parseObjectOrNull(it.payload) }
      ?.let(JsonSupport::jsonElementToValue)
      ?.let(JsonSupport::anyToStringAnyMap)
      ?.let(::auditVerdictFrom)
      ?.unmetCriteria
      ?.map { it.message }
      .orEmpty()

    // The latest validated output for the phase (highest iteration), or null when none is present.
    fun outputFor(phaseId: String): FeatureTaskRuntimePhaseOutput? =
      outputs.filter { it.phaseId == phaseId }.maxByOrNull { it.iteration }

    fun isComplete(phaseId: String): Boolean = phaseId in completed

    fun hasPriorRecord(phaseId: String): Boolean = phaseId in priorRecords

    // The durable per-phase record's blocked reason. The runner decides whether it is retryable;
    // this map preserves the prior attempt count and reason across resume.
    fun persistedBlockedReason(phaseId: String): String? = blockedRecords[phaseId]

    // True when the phase carries a stale branch-setup-origin blocked record from a prior run that
    // must be superseded now that branch setup has recovered.
    fun hasBranchSetupBlock(phaseId: String): Boolean = phaseId in branchSetupBlockedPhases

    // Branch setup succeeded for the phase this run, so any prior branch-setup-origin block is
    // recovered: forget it in-memory so the phase resumes normally. (The durable record is
    // superseded back to a running state by the runner before the phase launches.)
    fun clearBranchSetupBlock(phaseId: String) {
      branchSetupBlockedPhases.remove(phaseId)
      // The branch-setup block recorded an attemptCount but the phase agent never launched, so the
      // phase must resume at iteration 1, not be charged for the branch-setup attempt.
      persistedAttemptCounts.remove(phaseId)
    }

    // Resume the bounded fix loop from durable state: the next attempt is one past the greater
    // of the persisted record's attempt count and the latest validated output iteration. A phase
    // that already burned N attempts resumes at attempt N+1; the budget is never reset by resume.
    fun nextIteration(phaseId: String): Int {
      val latestOutputIteration = outputs.filter { it.phaseId == phaseId }.maxOfOrNull { it.iteration } ?: 0
      val persistedAttempts = persistedAttemptCounts[phaseId] ?: 0
      return maxOf(persistedAttempts, latestOutputIteration) + 1
    }

    fun recordCompleted(output: FeatureTaskRuntimePhaseOutput) {
      outputs += output
      completed += output.phaseId
      priorRecords += output.phaseId
    }

    fun completedPhaseIds(): List<String> =
      FeatureTaskRuntimePhaseWorkflowDefinition.definition.stepIds.filter { it in completed }
  }

  private companion object {
    const val STATUS_RUNNING = "running"
    const val STATUS_COMPLETED = "completed"
    const val STATUS_BLOCKED = "blocked"

    fun serializeTokenData(accumulator: Map<String, Pair<Int, Int>>): Pair<String?, Int?> {
      if (accumulator.isEmpty()) return null to null
      val breakdown = accumulator.mapValues { (_, pair) ->
        mapOf("estimated_input_tokens" to pair.first, "estimated_output_tokens" to pair.second)
      }
      val total = accumulator.values.sumOf { (i, o) -> i + o }
      return JsonSupport.mapToJsonString(breakdown) to total
    }

    // Branch setup is a distinct pre-implement step with no resolved phase agent; this sentinel
    // attributes its durable blocked record and ledger entry rather than a real agent id.
    const val BRANCH_SETUP_AGENT_ID = "branch-setup"

    // Preplan and plan are non-file-mutating; every later phase mutates or depends on a working
    // tree pinned to the feature branch.
    fun isFileMutating(phaseId: String): Boolean = phaseId !in NON_FILE_MUTATING_PHASES

    val NON_FILE_MUTATING_PHASES = setOf(
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PREPLAN,
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN,
    )

    // The transition declaration the run loops on: the test-only synthetic override, or the
    // forward-only production declaration truncated to the goal-continuation boundary. Resolved once
    // per run so the driver and the resume-time RunState reconstruction share one topology.
    fun transitionsFor(request: FeatureTaskRuntimeRunRequest): FeatureTaskRuntimeTransitionDeclaration =
      request.transitionsOverride ?: phasesFor(request).let { phases ->
        FeatureTaskRuntimeTransitionDeclaration(
          forwardPhaseIds = phases,
          backwardEdges = FeatureTaskRuntimePhaseWorkflowDefinition.transitions.backwardEdges,
          // Carry the loop-only set (intersected with the possibly goal-truncated pipeline) so the
          // forward edge skips implement_fix on a clean run instead of launching it inline.
          loopOnlyPhaseIds = FeatureTaskRuntimePhaseWorkflowDefinition.transitions.loopOnlyPhaseIds
            .filter { it in phases }.toSet(),
        )
      }

    fun phasesFor(request: FeatureTaskRuntimeRunRequest): List<String> {
      val phases = FeatureTaskRuntimePhaseWorkflowDefinition.definition.stepIds
      return if (isGoalContinuationRun(request)) {
        phases.takeWhile { it != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PR }
      } else {
        phases
      }
    }

    // Verifies the mutating-phase idempotency contract from a `completed` output rather than assuming
    // it: a mutating phase MUST report a reconciliation report (a `reconciled_state` object or
    // `reconciled` flag set true) in produced_outputs proving it reconciled the tree to target. A
    // missing or non-true flag returns a loud reason routed through the schema-gate failure path.
    // Returns null for every non-mutating phase and for a satisfied report. Pure: no IO, no mutation.
    private fun mutatingReconciliationGateReason(phaseId: String, outputMap: Map<String, Any?>): String? {
      if (!FeatureTaskRuntimePhaseWorkflowDefinition.isMutatingPhase(phaseId)) {
        return null
      }
      // Reconciliation is proven when produced_outputs carries a `reconciled_state` object whose
      // `reconciled` is boolean true, or a top-level `reconciled` boolean true.
      val producedOutputs = outputMap["produced_outputs"] as? Map<*, *>
      val nestedReconciled = (producedOutputs?.get("reconciled_state") as? Map<*, *>)?.get("reconciled")
      val reconciled = nestedReconciled == true || producedOutputs?.get("reconciled") == true
      return if (reconciled) {
        null
      } else {
        "Mutating phase '$phaseId' reported 'completed' without a reconciliation report proving it " +
          "reconciled the working tree to target: produced_outputs must carry 'reconciled_state' (or a " +
          "'reconciled' entry) with 'reconciled' set to true. The idempotency contract is verified, not " +
          "assumed; a silent skip fails the schema gate."
      }
    }

    // Verifies the review phase emitted a verification signal from a `completed` output rather than
    // assuming silence means approval: a review MUST carry EITHER a top-level `verdict` string OR a
    // `produced_outputs.findings` array (even an empty one). A review reporting NEITHER falls through
    // to ADVANCE in verdictFor, silently advancing past a possible Blocker/Major (the inverse of AC1),
    // so route the both-absent case through the schema-gate failure path. Returns null for every
    // non-review phase and whenever either signal is present. Pure: no IO, no mutation.
    private fun reviewVerificationSignalGateReason(phaseId: String, outputMap: Map<String, Any?>): String? {
      if (phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW) {
        return null
      }
      val hasVerdict = (outputMap[FeatureTaskRuntimeVerificationSignalKeys.VERDICT] as? String)?.isNotBlank() == true
      val producedOutputs = outputMap["produced_outputs"] as? Map<*, *>
      val findingsKey = FeatureTaskRuntimeVerificationSignalKeys.REVIEW_FINDINGS
      val hasFindingsArray =
        producedOutputs?.containsKey(findingsKey) == true && producedOutputs[findingsKey] is List<*>
      return if (hasVerdict || hasFindingsArray) {
        null
      } else {
        "Review phase reported 'completed' without a verification signal: the output must carry either a " +
          "top-level 'verdict' or a 'produced_outputs.findings' array (an explicit empty array affirms no " +
          "blocking findings). A review that emits neither cannot advance past a possible Blocker/Major; " +
          "the schema gate fails rather than silently advancing to audit."
      }
    }

    // Verifies the audit phase emitted a verification signal from a `completed` output rather than
    // assuming silence means the criteria are satisfied: an audit MUST carry EITHER a top-level
    // `verdict` string OR a `produced_outputs.unmet_criteria` (or `failing_criteria`) array (even an
    // empty one). An audit reporting NEITHER falls through to ADVANCE in verdictFor, silently advancing
    // past possibly-unmet acceptance criteria (the inverse of AC1), so route the both-absent case
    // through the schema-gate failure path. Returns null for every non-audit phase and whenever either
    // signal is present. Pure: no IO, no mutation.
    private fun auditVerificationSignalGateReason(phaseId: String, outputMap: Map<String, Any?>): String? {
      if (phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT) {
        return null
      }
      val hasVerdict = (outputMap[FeatureTaskRuntimeVerificationSignalKeys.VERDICT] as? String)?.isNotBlank() == true
      val producedOutputs = outputMap["produced_outputs"] as? Map<*, *>
      val hasCriteriaArray = listOf(
        FeatureTaskRuntimeVerificationSignalKeys.AUDIT_UNMET_CRITERIA,
        FeatureTaskRuntimeVerificationSignalKeys.AUDIT_FAILING_CRITERIA_ALIAS,
      ).any { key ->
        producedOutputs?.containsKey(key) == true && producedOutputs[key] is List<*>
      }
      return if (hasVerdict || hasCriteriaArray) {
        null
      } else {
        "Audit phase reported 'completed' without a verification signal: the output must carry either a " +
          "top-level 'verdict' or a 'produced_outputs.unmet_criteria' array (an explicit empty array affirms " +
          "every acceptance criterion is met). An audit that emits neither cannot advance past a possibly-unmet " +
          "criterion; the schema gate fails rather than silently advancing to validate."
      }
    }

    // Bound on the validator detail appended to a persisted blocked reason so a pathological
    // multi-violation reason cannot bloat the durable record or the CLI progress line.
    const val SCHEMA_GATE_DETAIL_MAX_CHARS = 500

    // Appends the schema validator's formatted reason (bounded) to the fix-loop policy's
    // terminal block reason so a blocked run is diagnosable without access to transient JVM logs.
    fun withSchemaGateDetail(policyReason: String, validationReason: String): String {
      val bounded = if (validationReason.length <= SCHEMA_GATE_DETAIL_MAX_CHARS) {
        validationReason
      } else {
        validationReason.take(SCHEMA_GATE_DETAIL_MAX_CHARS) + "… [truncated]"
      }
      return "$policyReason Last schema-gate failure: $bounded"
    }
  }
}

private fun terminalBlockedReasonFrom(phaseId: String, outputMap: Map<String, Any?>): String? {
  val status = outputMap["status"] as? String
  if (status != PHASE_OUTPUT_STATUS_BLOCKED && status != PHASE_OUTPUT_STATUS_FAILED) {
    return null
  }
  val summary = (outputMap["summary"] as? String).orEmpty().trim()
  val blockingReasons = (outputMap["produced_outputs"] as? Map<*, *>)
    ?.get("blocking_reasons")
    ?.let { value ->
      when (value) {
        is List<*> -> value.mapNotNull { it as? String }
        is String -> listOf(value)
        else -> emptyList()
      }
    }
    .orEmpty()
  val detail = (listOf(summary) + blockingReasons)
    .filter(String::isNotBlank)
    .joinToString("; ")
  val prefix = if (phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE) {
    "Validation phase reported status '$status'; retrying so the agent can fix failures."
  } else {
    "Phase output reported status '$status'."
  }
  return prefix + detail.takeIf(String::isNotBlank)?.let { " $it" }.orEmpty()
}

private fun goalContinuationConflict(
  request: FeatureTaskRuntimeRunRequest,
  durable: FeatureTaskRuntimeGoalContinuationArtifact,
  baseline: GoalSubtaskReviewBaseline,
): String? {
  if (request.requestedCodeReviewMode != null && request.requestedCodeReviewMode != durable.codeReviewMode) {
    return "Cannot change code-review mode on a goal child resume; its durable continuation policy is " +
      "'${durable.codeReviewMode.wireValue}'."
  }
  if (request.parallelReviewAgent != null && request.parallelReviewAgent != durable.parallelReviewAgent) {
    return "Cannot change the parallel-review agent on a goal child resume; its durable continuation policy is " +
      "'${durable.parallelReviewAgent ?: "none"}'."
  }
  val supplied = request.goalContinuation ?: return null
  if (supplied.parentIssueKey != durable.issueKey || supplied.subtaskId != durable.subtaskId ||
    supplied.goalBranch != durable.goalBranch || supplied.suppressPr != durable.suppressPr ||
    supplied.parentWorkflowId != durable.parentWorkflowId
  ) {
    return "The supplied goal-continuation identity conflicts with its durable child policy."
  }
  if (supplied.codeReviewMode != null && supplied.codeReviewMode != durable.codeReviewMode) {
    return "The supplied goal-continuation code-review mode conflicts with its durable child policy."
  }
  if (supplied.parallelReviewAgent != null && supplied.parallelReviewAgent != durable.parallelReviewAgent) {
    return "The supplied goal-continuation parallel-review agent conflicts with its durable child policy."
  }
  val suppliedBaseline = requireNotNull(supplied.reviewBaseline)
  return if (suppliedBaseline != baseline) {
    "The supplied goal-continuation review baseline conflicts with its durable child policy."
  } else {
    null
  }
}

private fun newGoalContinuationConflict(
  request: FeatureTaskRuntimeRunRequest,
  selectedReviewMode: CodeReviewExecutionMode,
): String? {
  val context = requireNotNull(request.goalContinuation)
  if (request.requestedCodeReviewMode != null && request.requestedCodeReviewMode != selectedReviewMode) {
    return "The supplied goal-continuation code-review mode conflicts with the requested child review policy."
  }
  return if (request.parallelReviewAgent != null && context.parallelReviewAgent != null &&
    request.parallelReviewAgent != context.parallelReviewAgent
  ) {
    "The supplied goal-continuation parallel-review agent conflicts with the requested child review policy."
  } else {
    null
  }
}

private fun goalContinuationPolicyBlockedReport(
  request: FeatureTaskRuntimeRunRequest,
  runInvariants: skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRunInvariants,
  reason: String,
): FeatureTaskRuntimeRunReport.Blocked = FeatureTaskRuntimeRunReport.Blocked(
  issueKey = request.issueKey,
  workflowId = request.workflowId,
  featureSize = runInvariants.featureSize.name,
  lastIncompletePhase = FeatureTaskRuntimePhaseWorkflowDefinition.definition.defaultInitialStepId,
  blockedReason = reason,
  completedPhaseIds = emptyList(),
  resolvedBranch = null,
)

private fun persistGoalContinuationOutcome(
  goalContinuationRecorder: FeatureTaskRuntimeGoalContinuationRecorder,
  phaseRecorder: FeatureTaskRuntimePhaseRecorder,
  gitOperations: WorkflowGitOperations,
  request: FeatureTaskRuntimeRunRequest,
  report: FeatureTaskRuntimeRunReport,
): FeatureTaskRuntimeRunReport {
  val context = request.goalContinuation ?: return report
  val outcome = goalContinuationOutcomeFor(phaseRecorder, gitOperations, request, context, report)?.let { base ->
    val attribution = agentAttributionFromPhaseState(phaseRecorder, request.workflowId, request.dbPathOverride)
    base.copy(
      finalizingAgentId = attribution.finalizingAgentId,
      participatingAgentIds = attribution.participatingAgentIds,
    )
  }
  outcome?.let { terminal ->
    goalContinuationRecorder.recordGoalContinuationState(
      workflowId = request.workflowId,
      outcome = FeatureTaskRuntimeGoalContinuationOutcome(
        issueKey = terminal.issueKey,
        subtaskId = terminal.subtaskId,
        status = terminal.status,
        workflowId = terminal.workflowId,
        commitSha = terminal.commitSha,
        blockedReason = terminal.blockedReason,
        lastResumableStep = terminal.lastResumableStep,
        finalizingAgentId = terminal.finalizingAgentId,
        participatingAgentIds = terminal.participatingAgentIds,
      ),
      workflowStatus = if (terminal.status == "complete") "completed" else "blocked",
      dbOverride = request.dbPathOverride,
    )
  }
  return when {
    report is FeatureTaskRuntimeRunReport.Completed && outcome != null -> report.copy(subtaskOutcome = outcome)
    report is FeatureTaskRuntimeRunReport.Blocked && outcome != null -> report.copy(subtaskOutcome = outcome)
    else -> report
  }
}

private fun goalContinuationOutcomeFor(
  recorder: FeatureTaskRuntimePhaseRecorder,
  gitOperations: WorkflowGitOperations,
  request: FeatureTaskRuntimeRunRequest,
  context: FeatureTaskRuntimeGoalContinuationContext,
  report: FeatureTaskRuntimeRunReport,
): FeatureTaskRuntimeSubtaskOutcome? = when (report) {
  is FeatureTaskRuntimeRunReport.Completed ->
    completedGoalContinuationOutcome(recorder, gitOperations, request, context)
  is FeatureTaskRuntimeRunReport.Blocked -> FeatureTaskRuntimeSubtaskOutcome(
    issueKey = context.parentIssueKey,
    subtaskId = context.subtaskId,
    status = "blocked",
    commitSha = null,
    workflowId = request.workflowId,
    blockedReason = report.blockedReason,
    lastResumableStep = report.lastIncompletePhase,
  )
  is FeatureTaskRuntimeRunReport.Decomposed -> null
}

private fun infraFailureReason(phaseId: String, facts: AgentRunLaunchFacts): String? = when {
  facts.spawnFailed ->
    "Feature-task-runtime phase '$phaseId' failed to launch: the agent process could not be spawned."
  facts.timedOut -> "Feature-task-runtime phase '$phaseId' launch timed out before the agent produced an output."
  facts.interrupted -> "Feature-task-runtime phase '$phaseId' launch was interrupted before completion."
  facts.exitStatus != null && facts.exitStatus != 0 -> {
    val base = "Feature-task-runtime phase '$phaseId' agent exited with non-zero status ${facts.exitStatus}."
    val output = facts.stderr.takeIf(String::isNotBlank) ?: facts.stdout.takeIf(String::isNotBlank)
    val excerpt = output?.let { stderrExcerpt(it, GoalRunnerLaunchFacts.STDERR_EXCERPT_MAX_CHARS) }
    if (excerpt != null) "$base\n$excerpt" else base
  }
  else -> null
}

// Drops a legacy PLAN completion that predates the now-required PREPLAN phase so the loop re-runs
// PLAN rather than honouring a pre-PREPLAN completion.
private fun invalidateLegacyPlanWithoutPreplan(completed: MutableSet<String>) {
  val plan = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN
  val preplan = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PREPLAN
  if (plan in completed && preplan !in completed) {
    completed.remove(plan)
  }
}

private fun recordToOutput(record: FeatureTaskRuntimePhaseRecord): FeatureTaskRuntimePhaseOutput? =
  record.outputArtifact?.let { artifact ->
    FeatureTaskRuntimePhaseOutput(
      phaseId = record.phaseId,
      iteration = record.attemptCount,
      payload = artifact,
    )
  }

private fun phaseDeclaration(
  phaseId: String,
  featureSize: skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFeatureSize,
): FeatureTaskRuntimePhaseDeclaration = FeatureTaskRuntimePhaseWorkflowDefinition.phaseDeclaration(phaseId, featureSize)

private fun missingUpstream(
  declaration: FeatureTaskRuntimePhaseDeclaration,
  recordedOutputs: List<FeatureTaskRuntimePhaseOutput>,
): List<String>? {
  val resolved = FeatureTaskRuntimeHandoffContract
    .resolveUpstreamOutputs(declaration, recordedOutputs)
    .outputsByPhaseId
    .keys
  return declaration.consumedUpstreamPhaseIds.filterNot(resolved::contains).takeIf { it.isNotEmpty() }
}
