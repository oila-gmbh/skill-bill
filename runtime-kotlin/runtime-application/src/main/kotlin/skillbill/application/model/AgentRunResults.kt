package skillbill.application.model

import skillbill.install.model.InstallAgent
import skillbill.ports.agentrun.model.AgentRunLaunchOutcome
import skillbill.ports.agentrun.model.SkillRunRequest

data class AgentRunStartRequest(
  val invokedAgentId: String,
  val configuredAgentOverrideId: String? = null,
  val skillRunRequest: SkillRunRequest,
) {
  init {
    require(invokedAgentId.isNotBlank()) { "invokedAgentId is required." }
    configuredAgentOverrideId?.let { overrideId ->
      require(overrideId.isNotBlank()) { "configuredAgentOverrideId must not be blank when provided." }
    }
  }
}

data class AgentRunAgentResolution(
  val invokedAgent: InstallAgent,
  val configuredOverrideAgent: InstallAgent?,
  val effectiveAgent: InstallAgent,
)

data class AgentRunResult(
  val resolution: AgentRunAgentResolution,
  val launchOutcome: AgentRunLaunchOutcome,
)
