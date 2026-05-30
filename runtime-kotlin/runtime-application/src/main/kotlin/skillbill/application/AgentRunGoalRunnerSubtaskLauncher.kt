package skillbill.application

import me.tatarka.inject.annotations.Inject
import skillbill.application.model.AgentRunStartRequest
import skillbill.ports.agentrun.model.AgentRunLaunchOutcome
import skillbill.ports.goalrunner.GoalRunnerSubtaskLauncher
import skillbill.ports.goalrunner.model.GoalRunnerSubtaskLaunchRequest

@Inject
class AgentRunGoalRunnerSubtaskLauncher(
  private val agentRunService: AgentRunService,
) : GoalRunnerSubtaskLauncher {
  override fun launch(request: GoalRunnerSubtaskLaunchRequest): AgentRunLaunchOutcome = agentRunService.launch(
    AgentRunStartRequest(
      invokedAgentId = request.invokedAgentId,
      configuredAgentOverrideId = request.configuredAgentOverrideId,
      skillRunRequest = request.skillRunRequest,
    ),
  ).launchOutcome
}
