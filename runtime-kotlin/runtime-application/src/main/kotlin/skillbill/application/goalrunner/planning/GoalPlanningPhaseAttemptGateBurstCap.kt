package skillbill.application.goalrunner.planning

import skillbill.application.featuretask.boundedSchemaGateDetail
import skillbill.application.goalrunner.EmptyOrStoppedArgs
import skillbill.application.goalrunner.planning.model.GoalPlanningPhaseProduction
import skillbill.application.planningprojection.producerProjectionGateReason
import skillbill.contracts.JsonCodec
import skillbill.error.InvalidFeatureTaskRuntimeHandoffProjectionError
import skillbill.error.InvalidFeatureTaskRuntimePlanningProjectionSchemaError
import skillbill.ports.goalrunner.runner.model.GoalRunnerLaunchAuthorizationDeniedException

fun DefaultGoalPlanningSweep.projectionGateReason(payload: String, phaseId: String): String? {
  val envelope = JsonCodec.parseObjectOrNull(payload)
    ?.let(JsonCodec::jsonElementToValue)
    ?.let(JsonCodec::anyToStringAnyMap)
    ?: return "Goal planning '$phaseId' payload is not a JSON object."
  return producerProjectionGateReason(phaseId, envelope, planningProjectionValidator)
    ?.let(::boundedSchemaGateDetail)
}

internal fun DefaultGoalPlanningSweep.produceAttemptAfterPauseCheck(
  args: GoalPlanningProduceAttemptArgs,
  shared: GoalPlanningSharedContext,
  phaseId: String,
  currentSubtaskId: Int,
): GoalPlanningPhaseProduction {
  val prompt = runCatching { composePlanningPrompt(args) }.getOrElse { error ->
    if (error !is InvalidFeatureTaskRuntimePlanningProjectionSchemaError &&
      error !is InvalidFeatureTaskRuntimeHandoffProjectionError
    ) {
      throw error
    }
    return GoalPlanningPhaseProduction.Stopped(
      stopped(shared, currentSubtaskId, projectionRejectedReason(phaseId, error), phaseId),
    )
  }
  val startedAtNanos = System.nanoTime()
  val outcome = runCatching { launchPlanningAttempt(args.phase, prompt) }
    .getOrElse { error ->
      if (error is GoalRunnerLaunchAuthorizationDeniedException) {
        return planningPauseOutcome(shared, currentSubtaskId, phaseId, error.controlState.pauseReason)
          ?: error("planning pause outcome was unexpectedly absent")
      }
      throw error
    }
  val durationMs = (System.nanoTime() - startedAtNanos) / GoalPlanningSweepConstants.NANOS_PER_MILLI
  val stdout = stdoutFor(outcome) ?: return emptyOrStopped(
    EmptyOrStoppedArgs(
      outcome = outcome,
      shared = shared,
      request = args.phase.request,
      currentSubtaskId = currentSubtaskId,
      phaseId = phaseId,
      durationMs = durationMs,
    ),
  )
  return validatePlanningAttemptOutput(stdout, shared, currentSubtaskId, phaseId, launchedAgentId(outcome))
}
