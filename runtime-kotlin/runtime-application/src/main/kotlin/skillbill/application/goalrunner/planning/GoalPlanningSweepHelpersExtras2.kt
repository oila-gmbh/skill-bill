package skillbill.application.goalrunner.planning

import skillbill.application.goalrunner.planning.model.GoalPlanningEmptyTurnEvidence
import skillbill.application.goalrunner.planning.model.GoalPlanningPhaseProduction
import skillbill.application.goalrunner.stderrExcerpt
import skillbill.error.IncompatibleGoalPlanningPreparationRecoveryError
import skillbill.goalrunner.model.GoalRunnerLaunchFacts
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import kotlin.time.Duration

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

internal fun unexpectedPlanningFailureReason(phaseId: String, error: Throwable): String =
  "Goal planning '$phaseId' failed before its output could be checkpointed: " +
    "${error::class.simpleName ?: "Throwable"}: ${error.message.orEmpty()}"

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
