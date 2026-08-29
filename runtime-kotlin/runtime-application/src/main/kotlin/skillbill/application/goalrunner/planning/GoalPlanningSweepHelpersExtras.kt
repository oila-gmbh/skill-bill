package skillbill.application.goalrunner.planning

import skillbill.application.goalrunner.EmptyOrStoppedArgs
import skillbill.application.goalrunner.planning.model.GoalPlanningEmptyTurnEvidence
import skillbill.application.goalrunner.planning.model.GoalPlanningPhaseProduction
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.agentrun.model.AgentRunLaunchOutcome
import skillbill.ports.agentrun.model.UnsupportedAgentRunLaunch
import skillbill.workflow.decomposition.model.DecompositionSubtask
import java.nio.file.Path
import kotlin.time.Duration

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

internal fun DefaultGoalPlanningSweep.emptyOrStopped(args: EmptyOrStoppedArgs): GoalPlanningPhaseProduction {
  val outcome = args.outcome
  val shared = args.shared
  val evidence = emptyTurnEvidence(outcome, args.durationMs)
    ?: return GoalPlanningPhaseProduction.Stopped(
      stopped(
        shared,
        args.currentSubtaskId,
        exhaustedReason(outcome, args.request.planningBudget),
        args.phaseId,
      ),
    )
  return GoalPlanningPhaseProduction.EmptyProviderTurn(
    emptyTurnReason(args.phaseId, evidence),
    evidence,
  )
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
