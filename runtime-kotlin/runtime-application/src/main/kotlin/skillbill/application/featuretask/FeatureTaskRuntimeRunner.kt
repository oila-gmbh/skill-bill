package skillbill.application.featuretask

import me.tatarka.inject.annotations.Inject
import skillbill.application.featuretask.model.FeatureTaskRuntimeCrashReconciliationResult
import skillbill.application.featuretask.model.FeatureTaskRuntimePreparation
import skillbill.application.featuretask.model.FeatureTaskRuntimeRunEvent
import skillbill.application.featuretask.model.FeatureTaskRuntimeRunReport
import skillbill.application.featuretask.model.FeatureTaskRuntimeRunRequest
import skillbill.application.telemetry.model.FeatureTaskRuntimeFindingVerificationTelemetry
import skillbill.application.telemetry.model.FeatureTaskRuntimeRegenerationTelemetry
import skillbill.application.workflow.repoRoot
import skillbill.contracts.JsonSupport
import skillbill.error.FeatureTaskRuntimeOperatorDecisionRejectedError
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.goalrunner.runner.GoalRunnerSubtaskLauncher
import skillbill.ports.workflow.gitops.buildGoalSubtaskReviewInput
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaseline
import skillbill.ports.workflow.model.FeatureTaskWorkflowMode
import skillbill.workflow.goal.model.GoalSubtaskReviewState
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseOutputValidator
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_STATUS_BLOCKED
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditProgress
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeResolvedBranch

/**
 * Runs the feature-task-runtime phase loop deterministically: for each ordered phase it
 * resolves the agent, assembles and persists the handoff, launches one agent synchronously,
 * gates the output through schema validation with an uncapped schema-correction retry, and persists per-phase
 * state, resuming from persisted records and blocking loudly on missing upstreams or failures.
 */
@Inject
@Suppress("TooManyFunctions", "LongParameterList") // single orchestration seam; one cohesive constructor
class FeatureTaskRuntimeRunner(
  private val subtaskLauncher: GoalRunnerSubtaskLauncher,
  private val recorder: FeatureTaskRuntimePhaseRecorder,
  private val goalContinuationRecorder: FeatureTaskRuntimeGoalContinuationRecorder,
  private val runInvariantsStore: FeatureTaskRuntimeRunInvariantsStore,
  private val outputValidator: FeatureTaskRuntimePhaseOutputValidator,
  private val phaseGates: FeatureTaskRuntimePhaseGates,
  private val crashReconciler: FeatureTaskRuntimeCrashReconciler,
  private val phaseSettlementService: FeatureTaskPhaseSettlementService,
  private val diagnostics: RuntimeDiagnostics = NoopRuntimeDiagnostics,
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

  fun run(request: FeatureTaskRuntimeRunRequest): FeatureTaskRuntimeRunReport {
    // Unconditional crash-reconciliation pass at startup: a killed child's row is transitioned to
    // the resumable pending state before the resume point is resolved, so it is no longer classified
    // as still-running. The pass is self-isolated and never blocks an otherwise healthy start.
    val reconciliation = crashReconciler.reconcile(request.dbPathOverride)
    return when (val preparation = prepareRun(request)) {
      is FeatureTaskRuntimePreparation.PreparationBlocked -> preparation.report
      is FeatureTaskRuntimePreparation.Prepared -> executeRun(preparation.request, reconciliation)
    }
  }

  private fun prepareRun(request: FeatureTaskRuntimeRunRequest): FeatureTaskRuntimePreparation =
    foreignModeWorkflowBlock(request)?.let(FeatureTaskRuntimePreparation::PreparationBlocked)
      ?: FeatureTaskRuntimeRunPreparation(
        recorder,
        goalContinuationRecorder,
        runInvariantsStore,
      ).prepare(request)

  @Suppress("LongMethod")
  private fun executeRun(
    runRequest: FeatureTaskRuntimeRunRequest,
    reconciliation: FeatureTaskRuntimeCrashReconciliationResult,
  ): FeatureTaskRuntimeRunReport {
    val specSource = specSourceResolver.resolve(
      repoRoot = runRequest.repoRoot,
      specReference = runRequest.runInvariants.specReference,
      isGoalContinuation = isGoalContinuationRun(runRequest),
    )
    emitFeatureTaskRuntimeEventSafely(
      diagnostics = diagnostics,
      seam = "RunStarted event-sink emission",
    ) {
      runRequest.eventSink.emit(
        FeatureTaskRuntimeRunEvent.RunStarted(runRequest.workflowId, runRequest.runInvariants.featureSize.name),
      )
    }
    // Runtime-owned lifecycle telemetry: the runtime mints and emits the started/finished events from
    // its own per-phase records (AC4), never the agent. Per-phase records and ledger remain the
    // authoritative observability source and are unchanged; this telemetry is additive (AC6). Every
    // telemetry call is failure-isolated (logged, never swallowed silently) so a telemetry fault can
    // neither abort the run nor falsely-fail a successful run, and the run exception always propagates.
    // The telemetry seam owns failure isolation: started/finished/finishedError each log on failure and
    // never throw, so a telemetry fault can neither abort the run nor falsely-fail a successful run.
    val telemetrySessionId = lifecycleTelemetry.started(runRequest)
    val observability = FeatureTaskRuntimeRunObservability(recorder, runRequest, diagnostics)
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
    val findingVerificationTelemetry = { loadFindingVerificationTelemetry(runRequest) }
    val transitions = transitionsFor(runRequest)
    val phaseTokenAccumulator: MutableMap<String, Pair<Int, Int>> = mutableMapOf()
    val report = runCatching {
      reopenCappedReviewOnChangedDelta(runRequest)
      if (isGoalContinuationRun(runRequest)) {
        when (
          val reconciliation = goalContinuationRecorder.reconcileRemediationBaseCoherence(
            workflowId = runRequest.workflowId,
            gitOperations = phaseGates.gitOperations,
            repoRoot = runRequest.repoRoot,
            dbOverride = runRequest.dbPathOverride,
          )
        ) {
          is RemediationBaseBlocked ->
            return remediationBaseCoherenceBlockedReport(runRequest, reconciliation.operatorGuidance)
          is RemediationBaseCoherent -> Unit
        }
      }
      val state = FeatureTaskRuntimeRunState(
        recorder.loadPhaseRecords(runRequest.workflowId, runRequest.dbPathOverride).orEmpty(),
        transitions,
        recorder.loadPhaseLedger(runRequest.workflowId, runRequest.dbPathOverride).orEmpty(),
        outputValidator,
        recorder.reconcileReviewGeneration(runRequest.workflowId, runRequest.dbPathOverride),
      )
      val loop = FeatureTaskRuntimeRunLoop(
        FeatureTaskRuntimeRunLoopDependencies(
          recorder,
          goalContinuationRecorder,
          outputValidator,
          phaseGates,
          subtaskLauncher,
          phaseSettlementService,
        ),
        FeatureTaskRuntimeRunLoopContext(
          runRequest,
          state,
          observability,
          specSource,
          transitions,
          phaseTokenAccumulator,
        ),
        diagnostics,
      )
      runRequest.operatorDecision?.let { decision ->
        loop.applyOperatorDecision(decision)?.let { rejection ->
          throw FeatureTaskRuntimeOperatorDecisionRejectedError(runRequest.workflowId, decision.wireValue, rejection)
        }
      }
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
        findingVerificationTelemetry,
        regenerationTelemetry,
        runRequest.dbPathOverride,
        phaseTokenData = { serializeTokenData(phaseTokenAccumulator) },
        crashReconciliation = { reconciliation },
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
      findingVerificationTelemetry,
      regenerationTelemetry,
      runRequest.dbPathOverride,
      phaseTokenData = { serializeTokenData(phaseTokenAccumulator) },
      crashReconciliation = { reconciliation },
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
    checkNotNull(recorder.persistReviewGenerationInvalidation(request.workflowId, request.dbPathOverride)) {
      "Could not durably reopen the stale capped review for workflow '${request.workflowId}'."
    }
  }

  /**
   * A review cap bounds an agent looping on unchanged code, so it must not outlive the code it
   * judged. A cap is authoritative only while the delta it judged still matches the tree, so a record
   * predating the digest cannot prove itself and reopens once, after which its fresh pass records a
   * digest and an unchanged resume blocks again. An unbuildable delta answers false, keeping the cap.
   *
   * The rebuild is scoped exactly as the recorded input was — same owned-path inventory, same widened
   * untracked exclusions, same remediation base selection — because a digest compared across two
   * different scopes measures the tree's dirt rather than the workflow's own delta.
   */
  private fun cappedReviewIsStale(request: FeatureTaskRuntimeRunRequest): Boolean {
    val goalBranch = request.goalContinuation?.goalBranch ?: return false
    val state = goalContinuationRecorder.reviewState(request.workflowId, request.dbPathOverride)
      // A pause is as settled as a cap and rests on the same authority: it judged a specific delta.
      // Once that delta changes the pause is stale too, and leaving it would wedge the subtask on a
      // decision about findings the tree no longer carries.
      ?.takeIf { it.reviewCapReached || it.pausedForOperatorDecision }
      ?: return false
    val judgedDigest = state.reviewedDeltaDigest ?: return true
    val resolved = recorder.loadResolvedBranch(request.workflowId, request.dbPathOverride)
    // A remediation pass records its digest from the rescoped pre-fix base, and the reservation that
    // selected that base is cleared once the pass completes. The base a resume can no longer identify
    // is therefore tried alongside the immutable one: a match under either is the same judged delta.
    val digests = listOfNotNull(state.remediationBaseSha, state.reviewBaseSha).distinct().mapNotNull { base ->
      phaseGates.gitOperations.buildGoalSubtaskReviewInput(
        request.repoRoot,
        reviewBaseline(request, resolved, state, base),
        goalBranch,
      ).input?.deltaDigest
    }
    return digests.isNotEmpty() && judgedDigest !in digests
  }

  private fun reviewBaseline(
    request: FeatureTaskRuntimeRunRequest,
    resolved: FeatureTaskRuntimeResolvedBranch?,
    state: GoalSubtaskReviewState,
    reviewBaseSha: String,
  ): GoalSubtaskReviewBaseline = resolved
    ?.let { FeatureTaskRuntimeScopedReviewBaseline.of(phaseGates.gitOperations, request.repoRoot, it, reviewBaseSha) }
    ?: GoalSubtaskReviewBaseline(reviewBaseSha, state.baselineUntrackedPaths)

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

  /**
   * The audit loop's progress for telemetry, derived by the shared
   * [FeatureTaskRuntimeAuditConvergence] so this value and the operator status projection cannot
   * disagree about the same workflow.
   */
  private fun loadAuditRepairProgress(request: FeatureTaskRuntimeRunRequest): FeatureTaskRuntimeAuditProgress =
    FeatureTaskRuntimeAuditConvergence.progressFrom(
      auditRecord = recorder.loadPhaseRecords(request.workflowId, request.dbPathOverride)
        ?.get(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT),
      auditGapIterationCount = loadAuditGapIterationCount(request),
    )

  private fun loadFindingVerificationTelemetry(
    request: FeatureTaskRuntimeRunRequest,
  ): FeatureTaskRuntimeFindingVerificationTelemetry {
    val verifyRecord = recorder.loadPhaseRecords(request.workflowId, request.dbPathOverride)
      ?.get(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS)
      ?: return FeatureTaskRuntimeFindingVerificationTelemetry(
        reviewFixCapExhausted = loadReviewFixIterationCount(request) >= 1,
      )
    val outputMap = verifyRecord.outputArtifact
      ?.let(JsonSupport::parseObjectOrNull)
      ?.let(JsonSupport::jsonElementToValue)
      ?.let(JsonSupport::anyToStringAnyMap)
      ?: return FeatureTaskRuntimeFindingVerificationTelemetry(
        reviewFixCapExhausted = loadReviewFixIterationCount(request) >= 1,
      )
    return FeatureTaskRuntimeFindingVerificationTelemetry(
      verifiedCount = FeatureTaskRuntimeOutputVerification.verifiedFindingDispositions(outputMap).size,
      rejectedCount = FeatureTaskRuntimeOutputVerification.rejectedFindingDispositions(outputMap).size,
      reviewFixCapExhausted = loadReviewFixIterationCount(request) >= 1,
    )
  }

  private fun loadAuditGapIterationCount(request: FeatureTaskRuntimeRunRequest): Int =
    FeatureTaskRuntimeAuditConvergence.auditGapIterationCount(
      recorder.loadPhaseLedger(request.workflowId, request.dbPathOverride).orEmpty(),
    )

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
