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
import skillbill.ports.db.DatabaseSessionFactory
import java.nio.file.Path

class FileSystemAgentRunLauncher internal constructor(
  processRunner: AgentRunProcessRunner,
  executableLookup: ExecutableLookup = PathExecutableLookup(),
  databasePath: Path? = null,
) : AgentRunLauncher {
  @Inject
  constructor(
    processRunner: JvmAgentRunProcessRunner,
    databaseSessionFactory: DatabaseSessionFactory,
  ) : this(
    processRunner = processRunner as AgentRunProcessRunner,
    databasePath = databaseSessionFactory.resolveDbPath(),
  )

  private val adapters: Map<InstallAgent, AgentRunAdapter> =
    headlessAgentRunAdapters(processRunner, executableLookup, databasePath)

  override fun launch(request: AgentRunLaunchRequest): AgentRunLaunchOutcome {
    val agent = InstallAgent.fromNormalizedId(request.agentId)
    val adapter = adapters[agent]
      ?: return UnsupportedAgentRunLaunch(
        agent = agent,
        reason = "Agent '${agent.id}' does not have a supported headless feature runtime launch path.",
      )
    return adapter.launch(request.skillRunRequest)
  }
}
