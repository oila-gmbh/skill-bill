package skillbill.application.goalrunner.planning

import skillbill.application.featuretask.FeatureTaskRuntimePhaseBriefingAssembler
import skillbill.application.featuretask.FeatureTaskRuntimePhasePromptComposer
import skillbill.application.featuretask.model.FeatureTaskRuntimePhasePromptComposeInputs
import skillbill.ports.agentrun.model.AgentRunLaunchOutcome
import skillbill.ports.agentrun.model.AgentRunOutputStream
import skillbill.ports.agentrun.model.SkillRunRequest
import skillbill.ports.goalrunner.runner.model.GoalRunnerSubtaskLaunchRequest
import skillbill.workflow.taskruntime.FeatureTaskRuntimeHandoffContract
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowQueries
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffAssemblyRequest

internal fun DefaultGoalPlanningSweep.launchPlanningAttempt(
  phase: GoalPlanningPhaseContext,
  prompt: String,
): AgentRunLaunchOutcome {
  val shared = phase.shared
  val request = phase.request
  val sink = phase.outputSink
  sink.write(AgentRunOutputStream.STDERR, planningProgressMessage(phase.phaseId, phase.subtask))
  return subtaskLauncher.launch(
    GoalRunnerSubtaskLaunchRequest(
      invokedAgentId = shared.invokedAgentId,
      configuredAgentOverrideId = shared.configuredAgentOverrideId,
      skillRunRequest = SkillRunRequest(
        issueKey = request.issueKey,
        repoRoot = shared.repoRoot,
        subtaskId = phase.subtask?.id,
        dbPathOverride = shared.dbPathOverride,
        timeout = request.planningBudget,
        progressIdleTimeout = request.progressIdleTimeout,
        outputSink = sink,
        promptOverride = prompt,
        streamOutputForLiveness = true,
        spawnAuthorization = manifestStore.authorizePlanningLaunch(shared.parentWorkflowId, shared.dbPathOverride),
      ),
    ),
  )
}

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
