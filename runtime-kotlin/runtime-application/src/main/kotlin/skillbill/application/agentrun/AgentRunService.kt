package skillbill.application.agentrun

import me.tatarka.inject.annotations.Inject
import skillbill.application.model.AgentRunAgentResolution
import skillbill.application.model.AgentRunResult
import skillbill.application.model.AgentRunStartRequest
import skillbill.install.model.InstallAgent
import skillbill.ports.agentrun.AgentRunLauncher
import skillbill.ports.agentrun.model.AgentRunLaunchRequest

@Inject
class AgentRunService(
  private val agentRunLauncher: AgentRunLauncher,
) {
  fun launch(request: AgentRunStartRequest): AgentRunResult {
    val invokedAgent = InstallAgent.fromNormalizedId(request.invokedAgentId, label = "invokedAgentId")
    val overrideAgent = request.configuredAgentOverrideId
      ?.let { id -> InstallAgent.fromNormalizedId(id, label = "configuredAgentOverrideId") }
    val effectiveAgent = overrideAgent ?: invokedAgent
    val resolution = AgentRunAgentResolution(
      invokedAgent = invokedAgent,
      configuredOverrideAgent = overrideAgent,
      effectiveAgent = effectiveAgent,
    )
    return AgentRunResult(
      resolution = resolution,
      launchOutcome = agentRunLauncher.launch(
        AgentRunLaunchRequest(
          agentId = effectiveAgent.id,
          skillRunRequest = request.skillRunRequest,
        ),
      ),
    )
  }
}
