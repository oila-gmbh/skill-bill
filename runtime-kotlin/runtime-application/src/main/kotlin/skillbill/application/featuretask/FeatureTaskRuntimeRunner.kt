package skillbill.application.featuretask

import me.tatarka.inject.annotations.Inject
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
import skillbill.error.InvalidFeatureTaskRuntimePhaseOutputSchemaError
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.agentrun.model.AgentRunLaunchOutcome
import skillbill.ports.agentrun.model.SkillRunRequest
import skillbill.ports.agentrun.model.UnsupportedAgentRunLaunch
import skillbill.ports.goalrunner.GoalRunnerSubtaskLauncher
import skillbill.ports.goalrunner.model.GoalRunnerSubtaskLaunchRequest
import skillbill.ports.workflow.WorkflowGitOperations
import skillbill.workflow.FeatureTaskRuntimePhaseOutputValidator
import skillbill.workflow.model.SpecSource
import skillbill.workflow.taskruntime.FeatureTaskRuntimeHandoffContract
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeGoalContinuationArtifact
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeGoalContinuationOutcome
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseDeclaration
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseRecord

private const val PHASE_OUTPUT_STATUS_BLOCKED = "blocked"
private const val PHASE_OUTPUT_STATUS_FAILED = "failed"

/**
 * Runs the feature-task-runtime phase loop deterministically: for each ordered phase it
 * resolves the agent, assembles and persists the handoff, launches one agent synchronously,
 * gates the output through schema validation with a bounded fix loop, and persists per-phase
 * state, resuming from persisted records and blocking loudly on missing upstreams or failures.
 */
@Inject
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

  fun run(request: FeatureTaskRuntimeRunRequest): FeatureTaskRuntimeRunReport {
    recorder.ensureWorkflowOpen(request.workflowId, request.sessionId, request.dbPathOverride)
    val durableRunInvariants = runInvariantsStore.resolve(
      workflowId = request.workflowId,
      dbOverride = request.dbPathOverride,
      proposed = request.runInvariants,
    ) ?: request.runInvariants
    val runRequest = request.copy(runInvariants = durableRunInvariants)
    // Resolve the persisted spec_source stamp once per run (artifact-only, additive — never config).
    // It gates the commit-exclusion directive and terminal-success scratch deletion; local-mode
    // resolves to LOCAL and keeps every path byte-for-byte unchanged.
    val specSource = specSourceResolver.resolve(
      repoRoot = runRequest.repoRoot,
      specReference = runRequest.runInvariants.specReference,
      isGoalContinuation = isGoalContinuationRun(runRequest),
    )
    persistGoalContinuationContext(goalContinuationRecorder, runRequest)
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
    val report = runCatching {
      val state = RunState(recorder.loadPhaseRecords(runRequest.workflowId, runRequest.dbPathOverride).orEmpty())
      val loop = RunLoop(runRequest, state, observability, specSource)
      for (phaseId in phasesFor(runRequest)) {
        if (loop.advance(phaseId)) {
          break
        }
      }
      loop.report()
    }.onFailure { error ->
      // An exception escaping the loop (recorder write, launcher RuntimeException, validator
      // non-schema error) would otherwise leave a dangling started-but-never-finished session.
      // Emit the error terminal from best-effort per-phase records, then rethrow the original.
      lifecycleTelemetry.finishedError(telemetrySessionId, phaseOutcomes, runRequest.dbPathOverride)
    }.getOrThrow()
    val terminalReport =
      persistGoalContinuationOutcome(goalContinuationRecorder, recorder, phaseGates.gitOperations, runRequest, report)
    phaseGates.specGate.deleteSingleSpecScratchOnTerminalSuccess(runRequest, terminalReport, specSource)
    lifecycleTelemetry.finished(telemetrySessionId, terminalReport, phaseOutcomes, runRequest.dbPathOverride)
    return terminalReport
  }

  // Drives the ordered phase loop for one run, owning the run-scoped resolved branch so the loop
  // body stays a single advance() call. The resolved branch is null until the first file-mutating
  // phase forces setup, which re-attaches the persisted branch on resume (never force-switching) so
  // a re-run never creates a second or divergent branch.
  private inner class RunLoop(
    private val request: FeatureTaskRuntimeRunRequest,
    private val state: RunState,
    private val observability: FeatureTaskRuntimeRunObservability,
    private val specSource: SpecSource,
  ) {
    private var resolvedBranch: String? = null
    private var blocked: FeatureTaskRuntimeRunReport.Blocked? = null
    private var decomposed: FeatureTaskRuntimeRunReport.Decomposed? = null

    // Advances one phase: skips already-complete phases, guarantees the feature branch before a
    // file-mutating phase (preplan/plan may precede setup), then launches the phase.
    // Returns true when the run is now blocked, decomposed, or the loop must otherwise stop.
    fun advance(phaseId: String): Boolean {
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
        decomposed != null -> true
        reason != null -> blockAt(phaseId, reason)
        else -> false
      }
    }

    // Runs the phase and records its completed output; returns a blocked reason when it blocks.
    private fun runPhaseFor(phaseId: String): String? {
      val outcome = runPhase(phaseId, request, state, observability, specSource)
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

    private fun blockAt(phaseId: String, reason: String): Boolean {
      blocked = FeatureTaskRuntimeRunReport.Blocked(
        issueKey = request.issueKey,
        workflowId = request.workflowId,
        featureSize = request.runInvariants.featureSize.name,
        lastIncompletePhase = phaseId,
        blockedReason = reason,
        completedPhaseIds = state.completedPhaseIds(),
        resolvedBranch = resolvedBranch,
      )
      return true
    }
  }

  private fun runPhase(
    phaseId: String,
    request: FeatureTaskRuntimeRunRequest,
    state: RunState,
    observability: FeatureTaskRuntimeRunObservability,
    specSource: SpecSource,
  ): PhaseOutcome {
    val run = PhaseRun(
      phaseId = phaseId,
      declaration = phaseDeclaration(phaseId, request.runInvariants.featureSize),
      resolvedAgent = FeatureTaskRuntimeAgentResolver.resolve(
        phaseId = phaseId,
        assignment = request.agentAssignment,
        invokedAgentId = request.invokedAgentId,
      ),
      request = request,
      specSource = specSource,
    )
    // Pre-launch blocks: a non-retryable phase already durably blocked on a prior run (the record
    // survives ledger pruning, so the budget is never silently reset), or a missing required
    // upstream (never launch blind). Retryable fix-loop phases continue into runPhaseAttempts,
    // which resumes at the next durable attempt and still enforces the bounded budget.
    return preLaunchBlock(run, state, observability) ?: runPhaseAttempts(run, state, observability)
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
    return missing?.let { (attemptCount, reason) -> blockAndPersist(run, attemptCount, reason, observability) }
  }

  private fun runPhaseAttempts(
    run: PhaseRun,
    state: RunState,
    observability: FeatureTaskRuntimeRunObservability,
  ): PhaseOutcome {
    val agentId = run.resolvedAgent.resolvedAgentId
    var iteration = state.nextIteration(run.phaseId)
    // The resumed iteration may already exceed the bounded budget (e.g. a fix-loop phase that
    // burned the cap on a prior run with no valid artifact). Block before launching rather than
    // relaunching the agent and bypassing the budget across resumes/crashes.
    FeatureTaskRuntimeFixLoopPolicy.blockReasonIfBudgetExhausted(run.phaseId, iteration)?.let { reason ->
      return blockAndPersist(run, iteration, reason, observability)
    }
    observability.started(run.phaseId, agentId, iteration, iteration > 1 || state.hasPriorRecord(run.phaseId))
    var outcome: PhaseOutcome? = null
    while (outcome == null) {
      val attempt = attemptOnce(run, state, iteration, observability)
      outcome = attempt.settledOutcome
        ?: when (val decision = FeatureTaskRuntimeFixLoopPolicy.decideAfterFailure(run.phaseId, iteration)) {
          is FeatureTaskRuntimeFixLoopDecision.Retry -> {
            iteration = decision.nextIteration
            observability.fixLoopIteration(run.phaseId, agentId, decision.nextIteration, decision.fixLoopIteration)
            null
          }
          is FeatureTaskRuntimeFixLoopDecision.Block -> blockAndPersist(
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
  private fun blockAndPersist(
    run: PhaseRun,
    attemptCount: Int,
    reason: String,
    observability: FeatureTaskRuntimeRunObservability,
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
      ),
      run.request.dbPathOverride,
    )
    observability.blocked(run.phaseId, run.resolvedAgent.resolvedAgentId, attemptCount.coerceAtLeast(1), reason)
    return PhaseOutcome.blocked(reason)
  }

  // Returns SchemaInvalid only on schema-invalid output (caller consults the fix-loop policy).
  // An infrastructure failure must block distinctly rather than be laundered through the schema
  // gate, which would misreport it as bad output and burn the fix-loop budget on doomed retries.
  private fun attemptOnce(
    run: PhaseRun,
    state: RunState,
    iteration: Int,
    observability: FeatureTaskRuntimeRunObservability,
  ): AttemptResult {
    persistPhase(run, iteration, STATUS_RUNNING, finished = false, outputArtifact = null)
    val launch = launchAndCapture(run, state)
    launch.infraFailureReason?.let { reason ->
      return AttemptResult.settled(blockAndPersist(run, iteration, reason, observability))
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
      terminalBlockedReasonFrom(outputMap)?.let { reason ->
        AttemptResult.settled(blockAndPersist(run, iteration, reason, observability))
      } ?: run {
        persistPhase(run, iteration, STATUS_COMPLETED, finished = true, outputArtifact = outputText)
        observability.completed(run.phaseId, run.resolvedAgent.resolvedAgentId, iteration)
        AttemptResult.settled(
          PhaseOutcome.completed(FeatureTaskRuntimePhaseOutput(run.phaseId, iteration, outputText)),
        )
      }
    } catch (error: InvalidFeatureTaskRuntimePhaseOutputSchemaError) {
      AttemptResult.schemaInvalid(error.reason)
    }
  }

  private fun persistPhase(run: PhaseRun, iteration: Int, status: String, finished: Boolean, outputArtifact: String?) {
    recorder.recordPhaseState(
      FeatureTaskRuntimePhaseStateRequest(
        workflowId = run.request.workflowId,
        phaseId = run.phaseId,
        status = status,
        attemptCount = iteration,
        resolvedAgentId = run.resolvedAgent.resolvedAgentId,
        finished = finished,
        outputArtifact = outputArtifact,
      ),
      run.request.dbPathOverride,
    )
  }

  // Persist the briefing before launching so it is a durable handoff a consumer can read
  // back, then deliver the same briefing to the agent as the launch prompt: the phase agent
  // only ever sees what the prompt carries, so a persisted-but-undelivered briefing would
  // leave it running the default goal-continuation flow and failing the schema gate.
  private fun launchAndCapture(run: PhaseRun, state: RunState): LaunchResult {
    val handoff = FeatureTaskRuntimeHandoffContract.assembleHandoff(
      declaration = run.declaration,
      runInvariants = run.request.runInvariants,
      recordedOutputs = state.outputs(),
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
          promptOverride = FeatureTaskRuntimePhasePromptComposer.compose(
            issueKey = run.request.issueKey,
            briefing = briefing,
            suppressDecomposition = isGoalContinuationRun(run.request),
            parallelReviewAgent = run.request.parallelReviewAgent
              ?.takeIf { run.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW },
            specSource = run.specSource,
          ),
        ),
      ),
    )
    return reconcileLaunch(run.phaseId, outcome)
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
    val request: FeatureTaskRuntimeRunRequest,
    val specSource: SpecSource,
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

  // `completed` is derived from record status while `outputs` carries only records with a
  // validated artifact, so a complete-but-output-less upstream is absent from the handoff and
  // triggers a loud missing-upstream block instead of a blind launch. The loaded per-phase
  // records (not just outputs) are retained so the bounded fix loop resumes from the durable
  // attempt count rather than resetting to iteration 1 on resume/crash. Single-threaded.
  private class RunState(initialRecords: Map<String, FeatureTaskRuntimePhaseRecord>) {
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

    fun outputs(): List<FeatureTaskRuntimePhaseOutput> = outputs.toList()

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

    fun phasesFor(request: FeatureTaskRuntimeRunRequest): List<String> {
      val phases = FeatureTaskRuntimePhaseWorkflowDefinition.definition.stepIds
      return if (isGoalContinuationRun(request)) {
        phases.takeWhile { it != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PR }
      } else {
        phases
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

private fun terminalBlockedReasonFrom(outputMap: Map<String, Any?>): String? {
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
  return "Phase output reported status '$status'." + detail.takeIf(String::isNotBlank)?.let { " $it" }.orEmpty()
}

private fun persistGoalContinuationContext(
  recorder: FeatureTaskRuntimeGoalContinuationRecorder,
  request: FeatureTaskRuntimeRunRequest,
) {
  val context = request.goalContinuation
  if (context != null) {
    recorder.recordGoalContinuationState(
      workflowId = request.workflowId,
      continuation = FeatureTaskRuntimeGoalContinuationArtifact(
        issueKey = context.parentIssueKey,
        subtaskId = context.subtaskId,
        suppressPr = context.suppressPr,
        goalBranch = context.goalBranch,
        parentWorkflowId = context.parentWorkflowId,
      ),
      dbOverride = request.dbPathOverride,
    )
  }
}

private fun persistGoalContinuationOutcome(
  goalContinuationRecorder: FeatureTaskRuntimeGoalContinuationRecorder,
  phaseRecorder: FeatureTaskRuntimePhaseRecorder,
  gitOperations: WorkflowGitOperations,
  request: FeatureTaskRuntimeRunRequest,
  report: FeatureTaskRuntimeRunReport,
): FeatureTaskRuntimeRunReport {
  val context = request.goalContinuation ?: return report
  val outcome = goalContinuationOutcomeFor(phaseRecorder, gitOperations, request, context, report)
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
  facts.exitStatus != null && facts.exitStatus != 0 ->
    "Feature-task-runtime phase '$phaseId' agent exited with non-zero status ${facts.exitStatus}."
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
