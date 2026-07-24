package skillbill.application.featuretask

import me.tatarka.inject.annotations.Inject
import skillbill.application.goalrunner.stderrExcerpt
import skillbill.application.model.FeatureTaskRuntimeGoalContinuationContext
import skillbill.application.model.FeatureTaskRuntimePreparation
import skillbill.application.model.FeatureTaskRuntimeRegenerationTelemetry
import skillbill.application.model.FeatureTaskRuntimeRunEvent
import skillbill.application.model.FeatureTaskRuntimeRunReport
import skillbill.application.model.FeatureTaskRuntimeRunRequest
import skillbill.application.model.FeatureTaskRuntimeSubtaskOutcome
import skillbill.application.workflow.repoRoot
import skillbill.goalrunner.model.GoalRunnerLaunchFacts
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.goalrunner.GoalRunnerSubtaskLauncher
import skillbill.ports.persistence.model.FeatureTaskWorkflowMode
import skillbill.ports.workflow.WorkflowGitOperations
import skillbill.ports.workflow.buildGoalSubtaskReviewInput
import skillbill.ports.workflow.model.GoalSubtaskReviewBaseline
import skillbill.workflow.FeatureTaskRuntimePhaseOutputValidator
import skillbill.workflow.taskruntime.FeatureTaskRuntimeHandoffContract
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_STATUS_BLOCKED
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditRepairProgress
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeGoalContinuationOutcome
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseDeclaration
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutput

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

  fun run(request: FeatureTaskRuntimeRunRequest): FeatureTaskRuntimeRunReport =
    when (val preparation = prepareRun(request)) {
      is FeatureTaskRuntimePreparation.PreparationBlocked -> preparation.report
      is FeatureTaskRuntimePreparation.Prepared -> executeRun(preparation.request)
    }

  private fun prepareRun(request: FeatureTaskRuntimeRunRequest): FeatureTaskRuntimePreparation =
    foreignModeWorkflowBlock(request)?.let(FeatureTaskRuntimePreparation::PreparationBlocked)
      ?: FeatureTaskRuntimeRunPreparation(
        recorder,
        goalContinuationRecorder,
        runInvariantsStore,
      ).prepare(request)

  @Suppress("LongMethod")
  private fun executeRun(runRequest: FeatureTaskRuntimeRunRequest): FeatureTaskRuntimeRunReport {
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
    val auditRepairProgress = {
      loadAuditRepairProgress(runRequest)
    }
    val regenerationTelemetry = { loadRegenerationTelemetry(runRequest) }
    val transitions = transitionsFor(runRequest)
    val phaseTokenAccumulator: MutableMap<String, Pair<Int, Int>> = mutableMapOf()
    val report = runCatching {
      reopenCappedReviewOnChangedDelta(runRequest)
      val state = FeatureTaskRuntimeRunState(
        recorder.loadPhaseRecords(runRequest.workflowId, runRequest.dbPathOverride).orEmpty(),
        transitions,
        recorder.loadPhaseLedger(runRequest.workflowId, runRequest.dbPathOverride).orEmpty(),
        outputValidator,
      )
      val loop = FeatureTaskRuntimeRunLoop(
        FeatureTaskRuntimeRunLoopDependencies(
          recorder,
          goalContinuationRecorder,
          outputValidator,
          phaseGates,
          subtaskLauncher,
        ),
        FeatureTaskRuntimeRunLoopContext(
          runRequest,
          state,
          observability,
          specSource,
          transitions,
          phaseTokenAccumulator,
        ),
      )
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
        auditRepairProgress,
        regenerationTelemetry,
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
      auditRepairProgress,
      regenerationTelemetry,
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
  /**
   * Runs before the run state loads: the reset that clears the legacy review attempt watermark is
   * applied at construction from the durable tombstone, so invalidating later in the run would leave
   * the reopened generation carrying the capped generation's attempts and re-block review before it
   * is ever launched.
   */
  private fun reopenCappedReviewOnChangedDelta(request: FeatureTaskRuntimeRunRequest) {
    if (!cappedReviewIsStale(request)) return
    check(recorder.persistReviewGenerationInvalidation(request.workflowId, request.dbPathOverride)) {
      "Could not durably reopen the stale capped review for workflow '${request.workflowId}'."
    }
  }

  /**
   * A review cap bounds an agent looping on unchanged code, so it must not outlive the code it
   * judged. A cap is authoritative only while the delta it judged still matches the tree, so a record
   * predating the digest cannot prove itself and reopens once, after which its fresh pass records a
   * digest and an unchanged resume blocks again. An unbuildable delta answers false, keeping the cap.
   */
  private fun cappedReviewIsStale(request: FeatureTaskRuntimeRunRequest): Boolean {
    val goalBranch = request.goalContinuation?.goalBranch ?: return false
    val state = goalContinuationRecorder.reviewState(request.workflowId, request.dbPathOverride)
      ?.takeIf { it.reviewCapReached }
      ?: return false
    val judgedDigest = state.reviewedDeltaDigest ?: return true
    val current = phaseGates.gitOperations.buildGoalSubtaskReviewInput(
      request.repoRoot,
      GoalSubtaskReviewBaseline(state.reviewBaseSha, state.baselineUntrackedPaths),
      goalBranch,
    ).input
    return current != null && current.deltaDigest != judgedDigest
  }

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

  private fun loadAuditRepairProgress(request: FeatureTaskRuntimeRunRequest): FeatureTaskRuntimeAuditRepairProgress? {
    recorder.loadAuditRepairState(request.workflowId, request.dbPathOverride)?.progress?.let { return it }
    val auditCompleted = recorder.loadPhaseRecords(request.workflowId, request.dbPathOverride)
      .orEmpty()[FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT]
      ?.status == "completed"
    if (!auditCompleted || loadAuditGapIterationCount(request) != 0) return null
    return FeatureTaskRuntimeAuditRepairProgress(
      firstPassConvergence = true,
      recurringGapCount = 0,
      newGapCount = 0,
      attemptedRepairItemCount = 0,
      resolvedRepairItemCount = 0,
      auditGapIterationCount = 0,
    )
  }

  // The highest durable `audit_gap` per-edge iteration recorded on the LOOP_EDGE ledger (0 when the
  // loop never fired): the runtime-owned audit->implement iteration count for finished telemetry (AC7).
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

  // SKILL-140: per-run quarantine-and-regenerate telemetry (AC-006), all sourced from the runtime's own
  // durable state (quarantine store + LOOP_EDGE ledger + blocked records), never agent-self-reported.
  // Activation = a distinct producer whose regeneration loop fired; attempt = each regeneration edge
  // fire; outcome tally is derived from cap-exhaustion/attribution blocks. Counts and class labels only.
  private fun loadRegenerationTelemetry(
    request: FeatureTaskRuntimeRunRequest,
  ): FeatureTaskRuntimeRegenerationTelemetry {
    val ledger = recorder.loadPhaseLedger(request.workflowId, request.dbPathOverride).orEmpty()
    val regenFires = ledger.filter {
      it.action == FeatureTaskRuntimePhaseLedgerAction.LOOP_EDGE &&
        FeatureTaskRuntimePhaseWorkflowDefinition.isRegenerationLoopId(it.loopId.orEmpty())
    }
    val firedLoops = regenFires.mapNotNull { it.loopId }.toSet()
    val blocked = recorder.loadPhaseRecords(request.workflowId, request.dbPathOverride)
      .orEmpty()
      .values
      .filter { it.status == FEATURE_TASK_RUNTIME_PHASE_STATUS_BLOCKED }
    val capExhaustedLoops = blocked
      .mapNotNull { it.loopId }
      .filter(FeatureTaskRuntimePhaseWorkflowDefinition::isRegenerationLoopId)
      .toSet()
    val unattributable = blocked.count {
      (it.blockedReason ?: "").contains("cannot attribute to a producing phase")
    }
    val producerNotInPipeline = blocked.count {
      (it.blockedReason ?: "").contains("absent from this run's resolved pipeline")
    }
    val regenerated = (firedLoops - capExhaustedLoops).size
    val outcomeCounts = buildMap {
      if (regenerated > 0) put("regenerated", regenerated)
      if (capExhaustedLoops.isNotEmpty()) put("cap_exhausted", capExhaustedLoops.size)
      if (unattributable > 0) put("unattributable", unattributable)
      if (producerNotInPipeline > 0) put("producer_not_in_pipeline", producerNotInPipeline)
    }
    return FeatureTaskRuntimeRegenerationTelemetry(
      activationCount = firedLoops.size,
      attemptCount = regenFires.size,
      outcomeCounts = outcomeCounts,
    )
  }

  // The Seam A ledger-derived finalizing agent for the single-spec completion-time `Agent:` line; the
  // spec gate invokes this lazily only when it decides to write (terminal, non-goal-continuation run).
  private fun finalizingAgentId(request: FeatureTaskRuntimeRunRequest): String? =
    agentAttributionFromPhaseState(recorder, request.workflowId, request.dbPathOverride).finalizingAgentId
}

internal fun terminalBlockedReasonFrom(phaseId: String, outputMap: Map<String, Any?>): String? {
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
      request = GoalContinuationStateRecordRequest(
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
      ),
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

internal fun infraFailureReason(phaseId: String, facts: AgentRunLaunchFacts): String? = when {
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
internal fun invalidateLegacyPlanWithoutPreplan(completed: MutableSet<String>) {
  val plan = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN
  val preplan = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PREPLAN
  if (plan in completed && preplan !in completed) {
    completed.remove(plan)
  }
}

internal fun phaseDeclaration(
  phaseId: String,
  featureSize: skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFeatureSize,
): FeatureTaskRuntimePhaseDeclaration = FeatureTaskRuntimePhaseWorkflowDefinition.phaseDeclaration(phaseId, featureSize)

internal fun missingUpstream(
  declaration: FeatureTaskRuntimePhaseDeclaration,
  recordedOutputs: List<FeatureTaskRuntimePhaseOutput>,
): List<String>? {
  val resolved = FeatureTaskRuntimeHandoffContract
    .resolveUpstreamOutputs(declaration, recordedOutputs)
    .outputsByPhaseId
    .keys
  return declaration.consumedUpstreamPhaseIds.filterNot(resolved::contains).takeIf { it.isNotEmpty() }
}
