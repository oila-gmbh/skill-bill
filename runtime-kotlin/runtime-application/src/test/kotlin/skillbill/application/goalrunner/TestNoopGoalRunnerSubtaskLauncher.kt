package skillbill.application.goalrunner

import skillbill.install.model.InstallAgent
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.agentrun.model.AgentRunLaunchOutcome
import skillbill.ports.goalrunner.runner.GoalRunnerSubtaskLauncher
import skillbill.ports.goalrunner.runner.model.GoalRunnerSubtaskLaunchRequest

internal object TestNoopGoalRunnerSubtaskLauncher : GoalRunnerSubtaskLauncher {
  override fun launch(request: GoalRunnerSubtaskLaunchRequest): AgentRunLaunchOutcome = AgentRunLaunchFacts(
    agent = InstallAgent.CLAUDE,
    exitStatus = 0,
    stdout = "",
    stderr = "",
    timedOut = false,
    spawnFailed = false,
  )
}
