package skillbill.application.goalrunner.planning

import skillbill.application.featuretask.FeatureTaskRuntimePhaseBriefingAssembler
import skillbill.application.featuretask.FeatureTaskRuntimePhasePromptComposer
import skillbill.application.featuretask.boundedSchemaGateDetail
import skillbill.application.featuretask.model.FeatureTaskRuntimePhasePromptComposeInputs
import skillbill.application.featuretask.producerProjectionGateReason
import skillbill.application.goalrunner.EmptyOrStoppedArgs
import skillbill.application.goalrunner.planning.model.GoalPlanningPhaseProduction
import skillbill.contracts.JsonSupport
import skillbill.error.InvalidFeatureTaskRuntimeHandoffProjectionError
import skillbill.error.InvalidFeatureTaskRuntimePlanningProjectionSchemaError
import skillbill.ports.goalrunner.runner.model.GoalRunnerLaunchAuthorizationDeniedException
import skillbill.workflow.decomposition.model.DecompositionSubtask
import skillbill.workflow.goal.model.GoalProgressOutcome
import skillbill.workflow.taskruntime.FeatureTaskRuntimeHandoffContract
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowQueries
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffAssemblyRequest

internal fun DefaultGoalPlanningSweep.composePlanningPrompt(args: GoalPlanningProduceAttemptArgs): String {
  val phase = args.phase
  val handoff = FeatureTaskRuntimeHandoffContract.assembleHandoff(
    FeatureTaskRuntimeHandoffAssemblyRequest(
      declaration = FeatureTaskRuntimePhaseWorkflowQueries.phaseDeclaration(
        phase.phaseId,
        phase.runInvariants.featureSize,
      ),
      runInvariants = phase.runInvariants,
      recordedOutputs = args.recordedOutputs,
    ),
  )
  val briefing = FeatureTaskRuntimePhaseBriefingAssembler.assemble(
    handoff,
    planningProjectionValidator = planningProjectionValidator,
    agentAddonSelection = phase.request.agentAddonSelection,
  )
  val basePrompt = FeatureTaskRuntimePhasePromptComposer.compose(
    FeatureTaskRuntimePhasePromptComposeInputs(
      issueKey = phase.request.issueKey,
      briefing = briefing,
      suppressDecomposition = true,
      priorSchemaFailure = args.priorSchemaFailure,
    ),
  )
  return GoalPlanningContextPromptFormatter.append(
    basePrompt,
    phase.shared.planningPacket,
    phase.subtask,
    phase.phaseId,
    args.resolvedBodies,
  )
}

internal fun DefaultGoalPlanningSweep.projectionGateReason(payload: String, phaseId: String): String? {
  val envelope = JsonSupport.parseObjectOrNull(payload)
    ?.let(JsonSupport::jsonElementToValue)
    ?.let(JsonSupport::anyToStringAnyMap)
    ?: return "Goal planning '$phaseId' payload is not a JSON object."
  return producerProjectionGateReason(phaseId, envelope, planningProjectionValidator)
    ?.let(::boundedSchemaGateDetail)
}

internal fun DefaultGoalPlanningSweep.produceAttemptAfterPauseCheck(
  args: GoalPlanningProduceAttemptArgs,
  shared: GoalPlanningSharedContext,
  subtask: DecompositionSubtask?,
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
  val outcome = runCatching { launchPlanningAttempt(shared, args.phase.request, subtask, phaseId, prompt) }
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

internal fun DefaultGoalPlanningSweep.settlePlanningProductionAttempt(
  scope: GoalPlanningAttemptScope,
  production: GoalPlanningPhaseProduction,
): GoalPlanningPhaseProductionSettlement = when (production) {
  is GoalPlanningPhaseProduction.Stopped -> {
    recordPlanningAttempt(GoalPlanningAttemptRecordArgs(scope, GoalProgressOutcome.FAILED))
    GoalPlanningPhaseProductionSettlement.Settled(production)
  }
  is GoalPlanningPhaseProduction.SchemaRejected -> {
    recordFailedAttempt(
      scope,
      GoalPlanningSweepConstants.SCHEMA_REJECTED_PLANNING_RULE,
      production,
    )
    GoalPlanningPhaseProductionSettlement.Retry(production.reason)
  }
  is GoalPlanningPhaseProduction.UnsuccessfulStatus -> {
    recordFailedAttempt(
      scope,
      GoalPlanningSweepConstants.UNSUCCESSFUL_PLANNING_STATUS_RULE,
      production,
    )
    GoalPlanningPhaseProductionSettlement.Settled(
      GoalPlanningPhaseProduction.Stopped(production.outcome),
    )
  }
  is GoalPlanningPhaseProduction.Captured ->
    GoalPlanningPhaseProductionSettlement.PendingCapture(production)
  is GoalPlanningPhaseProduction.RetryableDecline,
  is GoalPlanningPhaseProduction.EmptyProviderTurn,
  -> error("Decline/empty production must be settled before settlePlanningProductionAttempt.")
}

internal sealed interface GoalPlanningPhaseProductionSettlement {
  data class Settled(val production: GoalPlanningPhaseProduction) : GoalPlanningPhaseProductionSettlement
  data class Retry(val priorSchemaFailure: String) : GoalPlanningPhaseProductionSettlement
  data class PendingCapture(val production: GoalPlanningPhaseProduction.Captured) :
    GoalPlanningPhaseProductionSettlement
}

internal fun DefaultGoalPlanningSweep.settleCapturedPlanningProduction(
  scope: GoalPlanningAttemptScope,
  production: GoalPlanningPhaseProduction.Captured,
  phaseId: String,
  finalizePayload: (String) -> String,
): GoalPlanningPhaseProductionSettlement {
  val gated = gateCapturedPayload(production, phaseId, finalizePayload)
  return if (gated is GoalPlanningPhaseProduction.Captured) {
    recordPlanningAttempt(GoalPlanningAttemptRecordArgs(scope, GoalProgressOutcome.SUCCEEDED))
    GoalPlanningPhaseProductionSettlement.Settled(gated)
  } else {
    val rejected = gated as GoalPlanningPhaseProduction.SchemaRejected
    recordFailedAttempt(
      scope,
      GoalPlanningSweepConstants.SCHEMA_REJECTED_PLANNING_RULE,
      rejected,
    )
    GoalPlanningPhaseProductionSettlement.Retry(rejected.reason)
  }
}
