package skillbill.application.goalrunner.planning

import skillbill.application.featuretask.FeatureTaskRuntimePhaseBriefingAssembler
import skillbill.application.featuretask.FeatureTaskRuntimePhasePromptComposer
import skillbill.application.featuretask.FeatureTaskRuntimePhaseSafetyPolicy
import skillbill.application.featuretask.boundedSchemaGateDetail
import skillbill.application.featuretask.producerProjectionGateReason
import skillbill.application.goalrunner.model.GoalRunnerRunRequest
import skillbill.application.goalrunner.planning.model.GoalPlanningPhaseProduction
import skillbill.application.goalrunner.planning.model.GoalPlanningSweepOutcome
import skillbill.contracts.JsonSupport
import skillbill.error.InvalidFeatureTaskRuntimeHandoffProjectionError
import skillbill.error.InvalidFeatureTaskRuntimePhaseOutputSchemaError
import skillbill.error.InvalidFeatureTaskRuntimePlanningProjectionSchemaError
import skillbill.goalrunner.model.GoalRunnerStopReason
import skillbill.ports.agentrun.model.AgentRunLaunchOutcome
import skillbill.ports.agentrun.model.AgentRunOutputStream
import skillbill.ports.agentrun.model.SkillRunRequest
import skillbill.ports.goalrunner.planning.model.GoalPlanningResolvedBoundaryBodies
import skillbill.ports.goalrunner.runner.model.GoalRunnerLaunchAuthorizationDeniedException
import skillbill.ports.goalrunner.runner.model.GoalRunnerSubtaskLaunchRequest
import skillbill.ports.time.model.RuntimeWaitResult
import skillbill.workflow.decomposition.model.DecompositionSubtask
import skillbill.workflow.goal.model.GoalProgressOutcome
import skillbill.workflow.taskruntime.FeatureTaskRuntimeHandoffContract
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.AcceptedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRunInvariants
import skillbill.workflow.taskruntime.model.requireAcceptedOutput
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO

internal fun DefaultGoalPlanningSweep.producePhase(
  shared: GoalPlanningSharedContext,
  request: GoalRunnerRunRequest,
  subtask: DecompositionSubtask?,
  runInvariants: FeatureTaskRuntimeRunInvariants,
  phaseId: String,
  recordedOutputs: List<FeatureTaskRuntimePhaseOutput>,
  resolvedBodies: GoalPlanningResolvedBoundaryBodies = GoalPlanningResolvedBoundaryBodies(),
  finalizePayload: (String) -> String = { it },
): GoalPlanningPhaseProduction {
  var priorSchemaFailure: String? = null
  var retryableDeclines = 0
  var attempt = 0
  while (true) {
    attempt += 1
    recordPlanningAttemptStarted(shared, phaseId, subtask, attempt)
    val production = produceAttemptOrStop(
      shared,
      request,
      subtask,
      runInvariants,
      phaseId,
      recordedOutputs,
      priorSchemaFailure,
      resolvedBodies,
    )
    val settled: GoalPlanningPhaseProduction? = when (production) {
      is GoalPlanningPhaseProduction.Stopped -> {
        recordPlanningAttempt(shared, phaseId, subtask, attempt, GoalProgressOutcome.FAILED)
        production
      }

      is GoalPlanningPhaseProduction.SchemaRejected -> {
        recordFailedAttempt(shared, phaseId, subtask, attempt, GoalPlanningSweepConstants.SCHEMA_REJECTED_PLANNING_RULE, production)
        priorSchemaFailure = production.reason
        null
      }

      is GoalPlanningPhaseProduction.UnsuccessfulStatus -> {
        recordFailedAttempt(shared, phaseId, subtask, attempt, GoalPlanningSweepConstants.UNSUCCESSFUL_PLANNING_STATUS_RULE, production)
        GoalPlanningPhaseProduction.Stopped(production.outcome)
      }

      is GoalPlanningPhaseProduction.RetryableDecline -> {
        retryableDeclines += 1
        declineRetryStop(shared, phaseId, subtask, attempt, retryableDeclines, production)
      }

      is GoalPlanningPhaseProduction.EmptyProviderTurn -> {
        recordPlanningAttempt(shared, phaseId, subtask, attempt, GoalProgressOutcome.FAILED)
        recordEmptyProviderTurn(shared, phaseId, subtask, attempt, production)
        backoffStop(shared, phaseId, subtask, attempt)
      }

      is GoalPlanningPhaseProduction.Captured -> {
        val gated = gateCapturedPayload(production, phaseId, finalizePayload)
        if (gated is GoalPlanningPhaseProduction.Captured) {
          recordPlanningAttempt(shared, phaseId, subtask, attempt, GoalProgressOutcome.SUCCEEDED)
          gated
        } else {
          val rejected = gated as GoalPlanningPhaseProduction.SchemaRejected
          recordFailedAttempt(shared, phaseId, subtask, attempt, GoalPlanningSweepConstants.SCHEMA_REJECTED_PLANNING_RULE, rejected)
          priorSchemaFailure = rejected.reason
          null
        }
      }
    }
    if (settled != null) return settled
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

@Suppress("TooGenericExceptionCaught")
internal fun DefaultGoalPlanningSweep.produceAttemptOrStop(
  shared: GoalPlanningSharedContext,
  request: GoalRunnerRunRequest,
  subtask: DecompositionSubtask?,
  runInvariants: FeatureTaskRuntimeRunInvariants,
  phaseId: String,
  recordedOutputs: List<FeatureTaskRuntimePhaseOutput>,
  priorSchemaFailure: String?,
  resolvedBodies: GoalPlanningResolvedBoundaryBodies,
): GoalPlanningPhaseProduction = try {
  produceAttempt(
    shared,
    request,
    subtask,
    runInvariants,
    phaseId,
    recordedOutputs,
    priorSchemaFailure,
    resolvedBodies,
  )
} catch (error: Exception) {
  GoalPlanningPhaseProduction.Stopped(
    stopped(
      shared,
      subtask?.id ?: 0,
      unexpectedPlanningFailureReason(phaseId, error),
      phaseId,
    ),
  )
}

@Suppress("ReturnCount")
internal fun DefaultGoalPlanningSweep.produceAttempt(
  shared: GoalPlanningSharedContext,
  request: GoalRunnerRunRequest,
  subtask: DecompositionSubtask?,
  runInvariants: FeatureTaskRuntimeRunInvariants,
  phaseId: String,
  recordedOutputs: List<FeatureTaskRuntimePhaseOutput>,
  priorSchemaFailure: String?,
  resolvedBodies: GoalPlanningResolvedBoundaryBodies,
): GoalPlanningPhaseProduction {
  val currentSubtaskId = subtask?.id ?: 0
  planningPauseOutcome(shared, currentSubtaskId, phaseId)?.let { return it }
  val prompt = runCatching {
    composePlanningPrompt(
      shared,
      request,
      subtask,
      runInvariants,
      phaseId,
      recordedOutputs,
      priorSchemaFailure,
      resolvedBodies,
    )
  }
    .getOrElse { error ->
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
  val outcome = runCatching { launchPlanningAttempt(shared, request, subtask, phaseId, prompt) }
    .getOrElse { error ->
      if (error is GoalRunnerLaunchAuthorizationDeniedException) {
        return planningPauseOutcome(shared, currentSubtaskId, phaseId, error.controlState.pauseReason)
          ?: error("planning pause outcome was unexpectedly absent")
      }
      throw error
    }
  val durationMs = (System.nanoTime() - startedAtNanos) / GoalPlanningSweepConstants.NANOS_PER_MILLI
  val stdout = stdoutFor(outcome)
    ?: return emptyOrStopped(outcome, shared, request, currentSubtaskId, phaseId, durationMs)
  return validatePlanningAttemptOutput(stdout, shared, currentSubtaskId, phaseId, launchedAgentId(outcome))
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

internal fun DefaultGoalPlanningSweep.composePlanningPrompt(
  shared: GoalPlanningSharedContext,
  request: GoalRunnerRunRequest,
  subtask: DecompositionSubtask?,
  runInvariants: FeatureTaskRuntimeRunInvariants,
  phaseId: String,
  recordedOutputs: List<FeatureTaskRuntimePhaseOutput>,
  priorSchemaFailure: String?,
  resolvedBodies: GoalPlanningResolvedBoundaryBodies,
): String {
  val handoff = FeatureTaskRuntimeHandoffContract.assembleHandoff(
    declaration = FeatureTaskRuntimePhaseWorkflowDefinition.phaseDeclaration(phaseId, runInvariants.featureSize),
    runInvariants = runInvariants,
    recordedOutputs = recordedOutputs,
  )
  val briefing = FeatureTaskRuntimePhaseBriefingAssembler.assemble(
    handoff,
    planningProjectionValidator = planningProjectionValidator,
    agentAddonSelection = request.agentAddonSelection,
  )
  val basePrompt = FeatureTaskRuntimePhasePromptComposer.compose(
    issueKey = request.issueKey,
    briefing = briefing,
    suppressDecomposition = true,
    priorSchemaFailure = priorSchemaFailure,
  )
  return GoalPlanningContextPromptFormatter.append(
    basePrompt,
    shared.planningPacket,
    subtask,
    phaseId,
    resolvedBodies,
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
