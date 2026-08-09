package skillbill.launcher.agentrun

import me.tatarka.inject.annotations.Inject
import skillbill.install.model.InstallAgent
import skillbill.launcher.process.AgentRunProcessRunner
import skillbill.launcher.process.JvmAgentRunProcessRunner
import skillbill.ports.agentrun.AgentRunLauncher
import skillbill.ports.agentrun.ExecutableLookup
import skillbill.ports.agentrun.model.AgentRunLaunchOutcome
import skillbill.ports.agentrun.model.AgentRunLaunchRequest
import skillbill.ports.agentrun.model.UnsupportedAgentRunLaunch

class FileSystemAgentRunLauncher internal constructor(
  processRunner: AgentRunProcessRunner,
  executableLookup: ExecutableLookup = PathExecutableLookup(),
) : AgentRunLauncher {
  @Inject
  constructor(processRunner: JvmAgentRunProcessRunner) : this(processRunner as AgentRunProcessRunner)

  private val adapters: Map<InstallAgent, AgentRunAdapter> =
    headlessAgentRunAdapters(processRunner, executableLookup)

  override fun launch(request: AgentRunLaunchRequest): AgentRunLaunchOutcome {
    val agent = InstallAgent.fromNormalizedId(request.agentId)
    val adapter = adapters[agent]
      ?: return UnsupportedAgentRunLaunch(
        agent = agent,
        reason = "Agent '${agent.id}' does not have a supported headless bill-feature-task launch path.",
      )
    return adapter.launch(request.skillRunRequest)
  }
}
