package skillbill.launcher

import skillbill.install.model.InstallAgent
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.agentrun.model.SkillRunRequest

interface AgentRunAdapter {
  val agent: InstallAgent
  fun launch(request: SkillRunRequest): AgentRunLaunchFacts
}

class ProcessAgentRunAdapter(
  override val agent: InstallAgent,
  private val commandBuilder: AgentRunCommandBuilder,
  private val processRunner: AgentRunProcessRunner,
) : AgentRunAdapter {
  override fun launch(request: SkillRunRequest): AgentRunLaunchFacts {
    val command = commandBuilder.build(request)
    val result = processRunner.run(
      AgentRunProcessRequest(
        command = command.command,
        workingDirectory = command.workingDirectory,
        timeout = command.timeout,
        environment = command.environment,
        inheritEnvironment = command.inheritEnvironment,
        outputSink = request.outputSink,
      ),
    )
    return AgentRunLaunchFacts(
      agent = agent,
      exitStatus = result.exitStatus,
      stdout = result.stdout,
      stderr = result.stderr,
      timedOut = result.timedOut,
      spawnFailed = result.spawnFailed,
    )
  }
}

fun headlessAgentRunAdapters(processRunner: AgentRunProcessRunner): Map<InstallAgent, AgentRunAdapter> = listOf(
  ClaudeAgentRunCommandBuilder(),
  CodexAgentRunCommandBuilder(),
  OpencodeAgentRunCommandBuilder(),
).associate { builder ->
  builder.agent to ProcessAgentRunAdapter(
    agent = builder.agent,
    commandBuilder = builder,
    processRunner = processRunner,
  )
}
