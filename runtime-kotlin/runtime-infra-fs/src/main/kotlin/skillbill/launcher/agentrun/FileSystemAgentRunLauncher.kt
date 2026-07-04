package skillbill.launcher.agentrun

import me.tatarka.inject.annotations.Inject
import skillbill.install.model.InstallAgent
import skillbill.install.model.RUNTIME_REFUSED_AGENT_MESSAGE
import skillbill.install.model.isRuntimeRefusedAgent
import skillbill.launcher.process.JvmAgentRunProcessRunner
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
        // A runtime-refused agent (opencode, zcode) reaching this deep path — e.g. a future caller that
        // resolves an agent past the CLI preflight — gets the same actionable prose guidance as the
        // preflight, not a generic "no launch path" line.
        reason = if (isRuntimeRefusedAgent(agent.id)) {
          RUNTIME_REFUSED_AGENT_MESSAGE
        } else {
          "Agent '${agent.id}' does not have a supported headless bill-feature-task launch path."
        },
      )
    return adapter.launch(request.skillRunRequest)
  }
}
