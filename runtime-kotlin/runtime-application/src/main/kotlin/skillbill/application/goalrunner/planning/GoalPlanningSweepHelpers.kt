package skillbill.application.goalrunner.planning

import skillbill.application.goalrunner.model.GoalRunnerRunRequest
import skillbill.application.goalrunner.planning.model.GoalPlanningSweepOutcome
import skillbill.application.goalrunner.stderrExcerpt
import skillbill.goalrunner.model.GoalRunnerLaunchFacts
import skillbill.goalrunner.model.GoalRunnerStopReason
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.agentrun.model.AgentRunLaunchOutcome
import skillbill.ports.agentrun.model.UnsupportedAgentRunLaunch
import skillbill.error.IncompatibleGoalPlanningPreparationRecoveryError
import skillbill.application.goalrunner.planning.model.GoalPlanningEmptyTurnEvidence
import skillbill.application.goalrunner.planning.model.GoalPlanningPhaseProduction
import skillbill.workflow.decomposition.model.DecompositionSubtask
import java.nio.file.Path
import kotlin.time.Duration

internal fun DefaultGoalPlanningSweep.preSweepStopped(
  request: GoalRunnerRunRequest,
  reason: String,
  currentSubtaskId: Int = 0,
): GoalPlanningSweepOutcome.Stopped = GoalPlanningSweepOutcome.Stopped(
  issueKey = request.issueKey,
  currentSubtaskId = currentSubtaskId,
  reason = GoalRunnerStopReason.BLOCKED,
  blockedReason = reason,
  lastResumableStep = GoalPlanningSweepConstants.PHASE_PREPLAN,
)

internal fun DefaultGoalPlanningSweep.canonicalRepository(repoRoot: Path): Path = runCatching { repoRoot.toRealPath() }
  .getOrElse { repoRoot.toAbsolutePath().normalize() }

internal fun DefaultGoalPlanningSweep.planningProgressMessage(phaseId: String, subtask: DecompositionSubtask?): String =
  if (phaseId == GoalPlanningSweepConstants.PHASE_PREPLAN) {
    "skill-bill: goal planning - parent goal shared preplan\n"
  } else {
    "skill-bill: goal planning - subtask ${requireNotNull(subtask).id} plan\n"
  }

internal fun DefaultGoalPlanningSweep.sharedContextReason(error: Throwable): String =
  "Goal planning shared context could not be gathered: ${error.message.orEmpty()}"

internal fun DefaultGoalPlanningSweep.projectionRejectedReason(phaseId: String, error: Throwable): String =
  "Goal planning phase '$phaseId' rejected a declared bounded projection at the launch seam: " +
    "${error.message.orEmpty()}. Migrate or delete the affected goal-planning preparation record."

internal fun DefaultGoalPlanningSweep.preparationStateReadReason(error: Throwable, issueKey: String, subtaskId: Int): String =
  goalPlanningPreparationStateReadStopReason(error, issueKey, subtaskId)

internal fun DefaultGoalPlanningSweep.stopped(
  shared: GoalPlanningSharedContext,
  subtaskId: Int,
  blockedReason: String,
  lastResumableStep: String = GoalPlanningSweepConstants.PHASE_PREPLAN,
  reason: GoalRunnerStopReason = GoalRunnerStopReason.BLOCKED,
): GoalPlanningSweepOutcome.Stopped = GoalPlanningSweepOutcome.Stopped(
  issueKey = shared.issueKey,
  currentSubtaskId = subtaskId,
  reason = reason,
  blockedReason = blockedReason,
  lastResumableStep = lastResumableStep,
)

internal fun noSuchSubtaskReason(subtaskId: Int): String =
  "Goal planning selected subtask '$subtaskId' which is not present in the accepted decomposition."

internal fun unresolvedSpecReason(subtask: DecompositionSubtask): String =
  "Goal planning subtask '${subtask.id}' governed spec path '${subtask.specPath}' could not be resolved " +
    "inside the repository."

internal fun invariantReadReason(subtask: DecompositionSubtask, error: Throwable): String =
  "Goal planning subtask '${subtask.id}' run-invariants could not be read: ${error.message.orEmpty()}"

internal fun persistenceReason(subtask: DecompositionSubtask, error: Throwable): String =
  "Goal planning subtask '${subtask.id}' plan could not be checkpointed: ${error.message.orEmpty()}"

internal fun resolvedGovernedPath(canonicalRepository: Path, governingPath: String): Path {
  val lexical = lexicalPath(canonicalRepository, governingPath)
  return runCatching { lexical.toRealPath() }.getOrElse { lexical }
}

internal fun resolvedSubSpecPath(canonicalRepository: Path, specPath: String): Path? {
  if (specPath.isBlank()) return null
  val lexical = lexicalPath(canonicalRepository, specPath)
  val resolved = runCatching { lexical.toRealPath() }.getOrElse { lexical }
  return resolved.takeIf { it.startsWith(canonicalRepository) }
}

internal fun lexicalPath(canonicalRepository: Path, governingPath: String): Path {
  val path = Path.of(governingPath)
  return (if (path.isAbsolute) path else canonicalRepository.resolve(path)).toAbsolutePath().normalize()
}

internal fun DefaultGoalPlanningSweep.emptyOrStopped(
  outcome: AgentRunLaunchOutcome,
  shared: GoalPlanningSharedContext,
  request: GoalRunnerRunRequest,
  currentSubtaskId: Int,
  phaseId: String,
  durationMs: Long,
): GoalPlanningPhaseProduction {
  val evidence = emptyTurnEvidence(outcome, durationMs)
    ?: return GoalPlanningPhaseProduction.Stopped(
      stopped(shared, currentSubtaskId, exhaustedReason(outcome, request.planningBudget), phaseId),
    )
  return GoalPlanningPhaseProduction.EmptyProviderTurn(emptyTurnReason(phaseId, evidence), evidence)
}

internal fun emptyTurnEvidence(outcome: AgentRunLaunchOutcome, durationMs: Long): GoalPlanningEmptyTurnEvidence? {
  if (outcome !is AgentRunLaunchFacts) return null
  val cleanExit = !outcome.spawnFailed && !outcome.timedOut && !outcome.interrupted && outcome.exitStatus == 0
  if (!cleanExit) return null
  return GoalPlanningEmptyTurnEvidence(
    agentId = outcome.agent.id,
    durationMs = durationMs,
    exitStatus = outcome.exitStatus,
    assistantEventCount = outcome.assistantEventCount,
    rawOutputPreview = outcome.rawOutputPreview,
  )
}

internal fun launchedAgentId(outcome: AgentRunLaunchOutcome): String = when (outcome) {
  is AgentRunLaunchFacts -> outcome.agent.id
  is UnsupportedAgentRunLaunch -> "unknown"
}

internal fun stdoutFor(outcome: AgentRunLaunchOutcome): String? = when (outcome) {
  is AgentRunLaunchFacts -> outcome.stdout.takeIf { stdout ->
    !outcome.spawnFailed &&
      !outcome.timedOut &&
      !outcome.interrupted &&
      outcome.exitStatus == 0 &&
      stdout.isNotBlank()
  }
  is UnsupportedAgentRunLaunch -> null
}

internal fun exhaustedReason(outcome: AgentRunLaunchOutcome, planningBudget: Duration?): String = when (outcome) {
  is UnsupportedAgentRunLaunch -> "Goal planning could not launch a planning agent: ${outcome.reason}"
  is AgentRunLaunchFacts ->
    "Goal planning produced no usable agent output: ${exhaustedCause(outcome, planningBudget)}."
}

internal fun exhaustedCause(facts: AgentRunLaunchFacts, planningBudget: Duration?): String = when {
  facts.spawnFailed -> stderrExcerpt(facts.stderr, GoalRunnerLaunchFacts.STDERR_EXCERPT_MAX_CHARS)
    ?.let { excerpt -> "the planning agent failed to spawn — $excerpt" }
    ?: "the planning agent failed to spawn"
  facts.timedOut ->
    "the planning agent exhausted its $planningBudget planning budget; " +
      "raise or disable it with --planning-budget-minutes"
  facts.interrupted -> "the planning agent was interrupted"
  facts.exitStatus != null && facts.exitStatus != 0 -> "the planning agent exited with status ${facts.exitStatus}"
  else -> "the planning agent produced no usable output"
}

internal fun exhaustedDeclineReason(production: GoalPlanningPhaseProduction.RetryableDecline, declines: Int): String =
  "${production.reason} Relaunched $declines times under a retryable disposition " +
    "without a different outcome; the decline is not transient."

internal fun malformedReason(phaseId: String, error: Throwable): String =
  "Goal planning '$phaseId' output failed the schema gate and could not be prepared: ${error.message.orEmpty()}"

internal fun unexpectedPlanningFailureReason(phaseId: String, error: Exception): String =
  "Goal planning '$phaseId' failed before its output could be checkpointed: " +
    "${error::class.simpleName ?: "Exception"}: ${error.message.orEmpty()}"

internal fun unsuccessfulStatusReason(phaseId: String, payload: Map<String, Any?>): String {
  val status = payload["status"] ?: "missing"
  val disposition = (payload["failure_disposition"] as? String)
    ?.let { " disposition '$it'" }
    .orEmpty()
  val summary = (payload["summary"] as? String)
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
    ?.let { " Agent reported: ${it.take(GoalPlanningSweepConstants.PLANNING_STOP_DETAIL_MAX_CHARS)}" }
    .orEmpty()
  return "Goal planning '$phaseId' stopped with status '$status'$disposition; " +
    "its output was not checkpointed.$summary"
}

internal fun emptyTurnReason(phaseId: String, evidence: GoalPlanningEmptyTurnEvidence): String =
  "Goal planning '$phaseId' agent turn exited cleanly and returned no output. ${evidence.summary()}"

internal fun DefaultGoalPlanningSweep.recoverySubtaskId(error: Throwable): Int {
  val recoveryError = error as? IncompatibleGoalPlanningPreparationRecoveryError
  if (
    recoveryError != null &&
    error.message?.contains("must be completed with non-empty produced_outputs") == true
  ) {
    return 0
  }
  return recoveryError?.subtaskId ?: 0
}
