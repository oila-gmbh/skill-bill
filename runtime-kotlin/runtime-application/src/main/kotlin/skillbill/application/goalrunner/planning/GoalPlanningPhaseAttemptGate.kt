package skillbill.application.goalrunner.planning

import skillbill.application.featuretask.FeatureTaskRuntimePhaseSafetyPolicy
import skillbill.application.goalrunner.model.GoalRunnerRunRequest
import skillbill.application.goalrunner.planning.model.GoalPlanningPhaseProduction
import skillbill.application.goalrunner.planning.model.GoalPlanningSweepOutcome
import skillbill.error.InvalidFeatureTaskRuntimePhaseOutputSchemaError
import skillbill.goalrunner.model.GoalRunnerStopReason
import skillbill.ports.agentrun.model.AgentRunLaunchOutcome
import skillbill.ports.agentrun.model.AgentRunOutputStream
import skillbill.ports.agentrun.model.SkillRunRequest
import skillbill.ports.goalrunner.runner.model.GoalRunnerSubtaskLaunchRequest
import skillbill.ports.time.model.RuntimeWaitResult
import skillbill.workflow.decomposition.model.DecompositionSubtask
import skillbill.workflow.goal.model.GoalProgressOutcome
import skillbill.workflow.taskruntime.model.AcceptedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.requireAcceptedOutput
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO

internal fun DefaultGoalPlanningSweep.producePhase(args: GoalPlanningProducePhaseArgs): GoalPlanningPhaseProduction {
  val attemptArgs = args.attempt
  val phase = attemptArgs.phase
  val shared = phase.shared
  val phaseId = phase.phaseId
  val subtask = phase.subtask
  var priorSchemaFailure = attemptArgs.priorSchemaFailure
  var retryableDeclines = 0
  var attempt = 0
  while (true) {
    attempt += 1
    val scope = planningAttemptScope(shared, phaseId, subtask, attempt)
    recordPlanningAttemptStarted(scope)
    val step = advancePlanningProduceAttempt(
      PlanningProduceAdvanceArgs(
        attemptArgs = attemptArgs.copy(priorSchemaFailure = priorSchemaFailure),
        scope = scope,
        retryableDeclines = retryableDeclines,
        phaseId = phaseId,
        finalizePayload = args.finalizePayload,
      ),
    )
    when (step) {
      is PlanningProduceStep.Done -> return step.production
      is PlanningProduceStep.RetryDecline -> {
        retryableDeclines = step.retryableDeclines
      }
      is PlanningProduceStep.RetrySchema -> {
        priorSchemaFailure = step.priorSchemaFailure
      }
    }
  }
}

private data class PlanningProduceAdvanceArgs(
  val attemptArgs: GoalPlanningProduceAttemptArgs,
  val scope: GoalPlanningAttemptScope,
  val retryableDeclines: Int,
  val phaseId: String,
  val finalizePayload: (String) -> String,
)

private sealed interface PlanningProduceStep {
  class Done(val production: GoalPlanningPhaseProduction) : PlanningProduceStep
  class RetryDecline(val retryableDeclines: Int) : PlanningProduceStep
  class RetrySchema(val priorSchemaFailure: String) : PlanningProduceStep
}

private fun DefaultGoalPlanningSweep.advancePlanningProduceAttempt(
  args: PlanningProduceAdvanceArgs,
): PlanningProduceStep {
  val production = produceAttemptOrStop(args.attemptArgs)
  return when (production) {
    is GoalPlanningPhaseProduction.RetryableDecline -> {
      val nextDeclines = args.retryableDeclines + 1
      declineRetryStop(args.scope, nextDeclines, production)
        ?.let { PlanningProduceStep.Done(it) }
        ?: PlanningProduceStep.RetryDecline(nextDeclines)
    }
    is GoalPlanningPhaseProduction.EmptyProviderTurn -> {
      recordPlanningAttempt(GoalPlanningAttemptRecordArgs(args.scope, GoalProgressOutcome.FAILED))
      recordEmptyProviderTurn(args.scope, production)
      backoffStop(args.scope)
        ?.let { PlanningProduceStep.Done(it) }
        ?: PlanningProduceStep.RetryDecline(args.retryableDeclines)
    }
    else -> when (val settlement = settlePlanningProductionAttempt(args.scope, production)) {
      is GoalPlanningPhaseProductionSettlement.Settled ->
        PlanningProduceStep.Done(settlement.production)
      is GoalPlanningPhaseProductionSettlement.Retry ->
        PlanningProduceStep.RetrySchema(settlement.priorSchemaFailure)
      is GoalPlanningPhaseProductionSettlement.PendingCapture ->
        when (
          val captured = settleCapturedPlanningProduction(
            args.scope,
            settlement.production,
            args.phaseId,
            args.finalizePayload,
          )
        ) {
          is GoalPlanningPhaseProductionSettlement.Settled ->
            PlanningProduceStep.Done(captured.production)
          is GoalPlanningPhaseProductionSettlement.Retry ->
            PlanningProduceStep.RetrySchema(captured.priorSchemaFailure)
          is GoalPlanningPhaseProductionSettlement.PendingCapture ->
            error("Unexpected nested capture settlement.")
        }
    }
  }
}

internal fun DefaultGoalPlanningSweep.gateCapturedPayload(
  captured: GoalPlanningPhaseProduction.Captured,
  phaseId: String,
  finalizePayload: (String) -> String,
): GoalPlanningPhaseProduction {
  val payload = finalizePayload(captured.payload)
  val accepted = if (payload == captured.payload) {
    AcceptedFeatureTaskRuntimePhaseOutput(
      normalizedOutput = captured.normalizedOutput,
      repairEvidence = captured.repairEvidence,
    )
  } else {
    outputValidator.validatePhaseOutput(payload, phaseId).requireAcceptedOutput(phaseId)
  }
  val canonicalPayload = accepted.normalizedOutput.canonicalJson
  val gateReason = projectionGateReason(canonicalPayload, phaseId)
    ?: return GoalPlanningPhaseProduction.Captured(
      canonicalPayload,
      accepted.normalizedOutput,
      accepted.repairEvidence ?: captured.repairEvidence,
      captured.agentId,
    )
  return GoalPlanningPhaseProduction.SchemaRejected(gateReason, canonicalPayload, captured.agentId)
}

internal fun DefaultGoalPlanningSweep.produceAttemptOrStop(
  args: GoalPlanningProduceAttemptArgs,
): GoalPlanningPhaseProduction = runCatching {
  produceAttempt(args)
}.getOrElse { error ->
  val phase = args.phase
  GoalPlanningPhaseProduction.Stopped(
    stopped(
      phase.shared,
      phase.subtask?.id ?: 0,
      unexpectedPlanningFailureReason(phase.phaseId, error),
      phase.phaseId,
    ),
  )
}

internal fun DefaultGoalPlanningSweep.produceAttempt(
  args: GoalPlanningProduceAttemptArgs,
): GoalPlanningPhaseProduction {
  val phase = args.phase
  val shared = phase.shared
  val subtask = phase.subtask
  val phaseId = phase.phaseId
  val currentSubtaskId = subtask?.id ?: 0
  return planningPauseOutcome(shared, currentSubtaskId, phaseId)
    ?: produceAttemptAfterPauseCheck(args, shared, subtask, phaseId, currentSubtaskId)
}

internal fun DefaultGoalPlanningSweep.planningPauseOutcome(
  shared: GoalPlanningSharedContext,
  subtaskId: Int,
  phaseId: String,
  pauseReason: String? = null,
): GoalPlanningPhaseProduction.Stopped? {
  val controls = manifestStore.controlState(shared.parentWorkflowId, shared.dbPathOverride)
  if (!controls.requiresPauseBoundary(shared.manifest)) return null
  val reason = pauseReason?.let { " (reason=$it)" }.orEmpty()
  return GoalPlanningPhaseProduction.Stopped(
    stopped(
      shared,
      subtaskId,
      "Goal planning reached a durable pause boundary before launching phase '$phaseId'$reason.",
      phaseId,
      GoalRunnerStopReason.PAUSED,
    ),
  )
}

internal fun DefaultGoalPlanningSweep.interruptibleWait(
  duration: Duration,
  shared: GoalPlanningSharedContext,
  subtaskId: Int,
  phaseId: String,
): GoalPlanningSweepOutcome.Stopped? {
  if (duration <= ZERO) return null
  var remaining = duration
  while (remaining > ZERO) {
    planningPauseOutcome(shared, subtaskId, phaseId)?.let { return it.outcome }
    val slice = remaining.coerceAtMost(burstSchedule.waitSlice)
    when (timingPort.wait(slice)) {
      RuntimeWaitResult.COMPLETED -> remaining -= slice
      RuntimeWaitResult.INTERRUPTED -> return stopped(
        shared,
        subtaskId,
        "Goal planning wait was interrupted before launching phase '$phaseId'.",
        phaseId,
      )
    }
  }
  return planningPauseOutcome(shared, subtaskId, phaseId)?.outcome
}

internal fun DefaultGoalPlanningSweep.validatePlanningAttemptOutput(
  stdout: String,
  shared: GoalPlanningSharedContext,
  subtaskId: Int,
  phaseId: String,
  agentId: String,
): GoalPlanningPhaseProduction = runCatching {
  outputValidator.validatePhaseOutput(stdout, phaseId).requireAcceptedOutput(phaseId)
}.fold(
  onSuccess = { accepted ->
    val payload = accepted.normalizedOutput.envelope
    if (payload["status"] != "completed") {
      val reason = unsuccessfulStatusReason(phaseId, payload)
      val canonical = accepted.normalizedOutput.canonicalJson
      if (FeatureTaskRuntimePhaseSafetyPolicy.dispositionForTerminalOutput(phaseId, payload).retryOnResume) {
        GoalPlanningPhaseProduction.RetryableDecline(reason, canonical, agentId)
      } else {
        GoalPlanningPhaseProduction.UnsuccessfulStatus(
          reason,
          canonical,
          agentId,
          stopped(shared, subtaskId, reason, phaseId),
        )
      }
    } else {
      GoalPlanningPhaseProduction.Captured(
        accepted.normalizedOutput.canonicalJson,
        accepted.normalizedOutput,
        accepted.repairEvidence,
        agentId,
      )
    }
  },
  onFailure = { error ->
    if (error is InvalidFeatureTaskRuntimePhaseOutputSchemaError) {
      GoalPlanningPhaseProduction.SchemaRejected(
        error.payloadFreeReason ?: "Goal planning phase output was rejected by its schema contract.",
        stdout,
        agentId,
      )
    } else {
      GoalPlanningPhaseProduction.Stopped(
        stopped(shared, subtaskId, malformedReason(phaseId, error), phaseId),
      )
    }
  },
)

internal fun DefaultGoalPlanningSweep.launchPlanningAttempt(
  shared: GoalPlanningSharedContext,
  request: GoalRunnerRunRequest,
  subtask: DecompositionSubtask?,
  phaseId: String,
  prompt: String,
): AgentRunLaunchOutcome {
  request.outputSink.write(AgentRunOutputStream.STDERR, planningProgressMessage(phaseId, subtask))
  return subtaskLauncher.launch(
    GoalRunnerSubtaskLaunchRequest(
      invokedAgentId = shared.invokedAgentId,
      configuredAgentOverrideId = shared.configuredAgentOverrideId,
      skillRunRequest = SkillRunRequest(
        issueKey = request.issueKey,
        repoRoot = shared.repoRoot,
        subtaskId = subtask?.id,
        dbPathOverride = shared.dbPathOverride,
        timeout = request.planningBudget,
        progressIdleTimeout = request.progressIdleTimeout,
        outputSink = request.outputSink,
        promptOverride = prompt,
        streamOutputForLiveness = true,
        spawnAuthorization = manifestStore.authorizePlanningLaunch(shared.parentWorkflowId, shared.dbPathOverride),
      ),
    ),
  )
}
