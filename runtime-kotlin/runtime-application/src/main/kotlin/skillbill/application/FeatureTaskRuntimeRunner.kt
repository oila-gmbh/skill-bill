package skillbill.application

import me.tatarka.inject.annotations.Inject
import skillbill.application.model.FeatureTaskRuntimeFixLoopDecision
import skillbill.application.model.FeatureTaskRuntimePhaseStateRequest
import skillbill.application.model.FeatureTaskRuntimeResolvedPhaseAgent
import skillbill.application.model.FeatureTaskRuntimeRunReport
import skillbill.application.model.FeatureTaskRuntimeRunRequest
import skillbill.error.InvalidFeatureTaskRuntimePhaseOutputSchemaError
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.agentrun.model.AgentRunLaunchOutcome
import skillbill.ports.agentrun.model.SkillRunRequest
import skillbill.ports.agentrun.model.UnsupportedAgentRunLaunch
import skillbill.ports.goalrunner.GoalRunnerSubtaskLauncher
import skillbill.ports.goalrunner.model.GoalRunnerSubtaskLaunchRequest
import skillbill.workflow.FeatureTaskRuntimePhaseOutputValidator
import skillbill.workflow.taskruntime.FeatureTaskRuntimeHandoffContract
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseDeclaration
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseRecord

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
  private val outputValidator: FeatureTaskRuntimePhaseOutputValidator,
  private val branchSetupRunner: FeatureTaskRuntimeBranchSetupRunner,
) {
  fun run(request: FeatureTaskRuntimeRunRequest): FeatureTaskRuntimeRunReport {
    recorder.ensureWorkflowOpen(request.workflowId, request.sessionId, request.dbPathOverride)
    val observability = FeatureTaskRuntimeRunObservability(recorder, request)
    val state = RunState(recorder.loadPhaseRecords(request.workflowId, request.dbPathOverride).orEmpty())
    val loop = RunLoop(request, state, observability)
    for (phaseId in FeatureTaskRuntimePhaseWorkflowDefinition.definition.stepIds) {
      if (loop.advance(phaseId)) {
        break
      }
    }
    return loop.report()
  }

  // Drives the ordered phase loop for one run, owning the run-scoped resolved branch so the loop
  // body stays a single advance() call. The resolved branch is null until the first file-mutating
  // phase forces setup, which re-attaches the persisted branch on resume (never force-switching) so
  // a re-run never creates a second or divergent branch.
  private inner class RunLoop(
    private val request: FeatureTaskRuntimeRunRequest,
    private val state: RunState,
    private val observability: FeatureTaskRuntimeRunObservability,
  ) {
    private var resolvedBranch: String? = null
    private var blocked: FeatureTaskRuntimeRunReport.Blocked? = null

    // Advances one phase: skips already-complete phases, guarantees the feature branch before a
    // file-mutating phase (preplan/plan may precede setup), then launches the phase.
    // Returns true when the run is now blocked and the loop must stop.
    fun advance(phaseId: String): Boolean {
      if (state.isComplete(phaseId)) {
        return false
      }
      val reason = establishBranchIfNeeded(phaseId) ?: runPhaseFor(phaseId)
      return reason?.let { blockAt(phaseId, it) } ?: false
    }

    // Runs the phase and records its completed output; returns a blocked reason when it blocks.
    private fun runPhaseFor(phaseId: String): String? {
      val outcome = runPhase(phaseId, request, state, observability)
      outcome.blockedReason?.let { return it }
      state.recordCompleted(requireNotNull(outcome.completedOutput))
      return null
    }

    fun report(): FeatureTaskRuntimeRunReport {
      blocked?.let { return it }
      // A resume whose file-mutating phases were all already complete never re-enters setup this
      // run; surface the durably-persisted branch so the report still names it.
      val branch = resolvedBranch
        ?: recorder.loadResolvedBranch(request.workflowId, request.dbPathOverride)?.branch
      return FeatureTaskRuntimeRunReport.Completed(
        issueKey = request.issueKey,
        workflowId = request.workflowId,
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
  ): PhaseOutcome {
    val run = PhaseRun(
      phaseId = phaseId,
      declaration = phaseDeclaration(phaseId),
      resolvedAgent = FeatureTaskRuntimeAgentResolver.resolve(
        phaseId = phaseId,
        assignment = request.agentAssignment,
        invokedAgentId = request.invokedAgentId,
      ),
      request = request,
    )
    // Pre-launch blocks: a phase already durably blocked on a prior run (the record survives ledger
    // pruning, so the budget is never silently reset), or a missing required upstream (never launch
    // blind). Both resolve to a single block here so the agent is not relaunched.
    return preLaunchBlock(run, state, observability) ?: runPhaseAttempts(run, state, observability)
  }

  // Returns a blocked outcome when the phase must block before launching, else null. A persisted
  // blocked record re-blocks at the resumed iteration; a missing upstream blocks at attempt 1.
  private fun preLaunchBlock(
    run: PhaseRun,
    state: RunState,
    observability: FeatureTaskRuntimeRunObservability,
  ): PhaseOutcome? {
    val persisted = state.persistedBlockedReason(run.phaseId)?.let { persistedReason ->
      val reason = persistedReason.ifBlank {
        "Phase '${run.phaseId}' is durably blocked from a prior run; the runtime re-blocks rather than relaunching."
      }
      state.nextIteration(run.phaseId) to reason
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
    try {
      outputValidator.validatePhaseOutputText(outputText, sourceLabel = run.phaseId)
    } catch (error: InvalidFeatureTaskRuntimePhaseOutputSchemaError) {
      return AttemptResult.schemaInvalid(error.reason)
    }
    persistPhase(run, iteration, STATUS_COMPLETED, finished = true, outputArtifact = outputText)
    observability.completed(run.phaseId, run.resolvedAgent.resolvedAgentId, iteration)
    return AttemptResult.settled(
      PhaseOutcome.completed(FeatureTaskRuntimePhaseOutput(run.phaseId, iteration, outputText)),
    )
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
          promptOverride = FeatureTaskRuntimePhasePromptComposer.compose(run.request.issueKey, briefing),
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
    private val outputs: MutableList<FeatureTaskRuntimePhaseOutput> =
      initialRecords.values.mapNotNull(::recordToOutput).toMutableList()
    private val completed: MutableSet<String> =
      initialRecords.values.filter { it.status == STATUS_COMPLETED }.map { it.phaseId }.toMutableSet()
    private val priorRecords: MutableSet<String> = initialRecords.keys.toMutableSet()

    // Durable per-phase attempt count from the loaded record (0 when no record exists).
    private val persistedAttemptCounts: MutableMap<String, Int> =
      initialRecords.mapValues { (_, record) -> record.attemptCount }.toMutableMap()

    // Phases already persisted with a durable genuine-phase-agent blocked record. Branch-setup-origin
    // blocked records (resolvedAgentId == BRANCH_SETUP_AGENT_ID) are deliberately excluded: branch
    // setup is re-attemptable on resume, so a recoverable branch-setup failure must never short-circuit
    // a real phase launch once setup succeeds. Genuine per-phase agent blocks still re-block on resume.
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

    init {
      invalidateLegacyPlanWithoutPreplan()
    }

    private fun invalidateLegacyPlanWithoutPreplan() {
      val plan = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN
      val preplan = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PREPLAN
      if (plan !in completed || preplan in completed) {
        return
      }
      completed.remove(plan)
      outputs.removeAll { it.phaseId == plan }
    }

    fun outputs(): List<FeatureTaskRuntimePhaseOutput> = outputs.toList()

    fun isComplete(phaseId: String): Boolean = phaseId in completed

    fun hasPriorRecord(phaseId: String): Boolean = phaseId in priorRecords

    // The durable per-phase record's blocked reason, when the phase already exhausted its
    // budget on a prior run, so resume re-blocks immediately instead of relaunching the agent.
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

private fun infraFailureReason(phaseId: String, facts: AgentRunLaunchFacts): String? = when {
  facts.spawnFailed ->
    "Feature-task-runtime phase '$phaseId' failed to launch: the agent process could not be spawned."
  facts.timedOut -> "Feature-task-runtime phase '$phaseId' launch timed out before the agent produced an output."
  facts.interrupted -> "Feature-task-runtime phase '$phaseId' launch was interrupted before completion."
  facts.exitStatus != null && facts.exitStatus != 0 ->
    "Feature-task-runtime phase '$phaseId' agent exited with non-zero status ${facts.exitStatus}."
  else -> null
}

private fun recordToOutput(record: FeatureTaskRuntimePhaseRecord): FeatureTaskRuntimePhaseOutput? =
  record.outputArtifact?.let { artifact ->
    FeatureTaskRuntimePhaseOutput(
      phaseId = record.phaseId,
      iteration = record.attemptCount,
      payload = artifact,
    )
  }

private fun phaseDeclaration(phaseId: String): FeatureTaskRuntimePhaseDeclaration =
  FeatureTaskRuntimePhaseWorkflowDefinition.phaseDeclarations[phaseId]
    ?: error("No phase declaration for runtime phase '$phaseId'.")

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
