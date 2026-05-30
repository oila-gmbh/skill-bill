package skillbill.launcher

import me.tatarka.inject.annotations.Inject
import skillbill.install.model.InstallAgent
import skillbill.ports.agentrun.AgentRunLauncher
import skillbill.ports.agentrun.model.AgentRunLaunchOutcome
import skillbill.ports.agentrun.model.AgentRunLaunchRequest
import skillbill.ports.agentrun.model.UnsupportedAgentRunLaunch

@Inject
class FileSystemAgentRunLauncher(
  processRunner: JvmAgentRunProcessRunner,
) : AgentRunLauncher {
  private val adapters: Map<InstallAgent, AgentRunAdapter> = headlessAgentRunAdapters(processRunner)

  override fun launch(request: AgentRunLaunchRequest): AgentRunLaunchOutcome {
    val agent = InstallAgent.fromNormalizedId(request.agentId)
    val adapter = adapters[agent]
      ?: return UnsupportedAgentRunLaunch(
        agent = agent,
        reason = "Agent '${agent.id}' does not have a supported headless bill-feature-implement launch path.",
      )
    return adapter.launch(request.skillRunRequest)
  }
}
