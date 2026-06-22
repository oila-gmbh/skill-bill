package skillbill.launcher.agentrun

import skillbill.install.model.InstallAgent
import skillbill.launcher.process.AgentRunProcessRequest
import skillbill.launcher.process.AgentRunProcessRunner
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.agentrun.model.SkillRunRequest
import java.nio.file.Path

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
        stdinText = command.stdinText,
        progressIdleTimeout = request.progressIdleTimeout,
        progressProbe = request.progressProbe,
        declaredProgressProbe = request.declaredProgressProbe,
        progressEmitter = request.progressEmitter,
        activityProbe = WorktreeActivityProbe(command.workingDirectory),
        environment = command.environment,
        inheritEnvironment = command.inheritEnvironment,
        outputSink = request.outputSink,
        usePtyStdio = command.usePtyStdio,
      ),
    )
    return AgentRunLaunchFacts(
      agent = agent,
      exitStatus = result.exitStatus,
      stdout = result.stdout,
      stderr = result.stderr,
      timedOut = result.timedOut,
      interrupted = result.interrupted,
      spawnFailed = result.spawnFailed,
      liveness = result.liveness,
      // SKILL-64 Subtask 3 (AC6, AC11): provider-neutral child-session
      // descriptors derived from launch context the launcher controls — the
      // child working directory (session path) and a deterministic, non-secret
      // session marker (agent + subtask + working dir). No provider-private
      // token-log format is consulted (Non-Goal).
      childSessionPath = command.workingDirectory.toString(),
      childSessionId = childSessionId(agent, request, command.workingDirectory),
    )
  }

  private fun childSessionId(agent: InstallAgent, request: SkillRunRequest, workingDirectory: Path): String =
    buildString {
      append(agent.id)
      append(':')
      append(request.issueKey)
      request.subtaskId?.let { id ->
        append(":subtask-")
        append(id)
      }
      append(':')
      append(workingDirectory.fileName?.toString() ?: workingDirectory.toString())
    }
}

fun headlessAgentRunAdapters(processRunner: AgentRunProcessRunner): Map<InstallAgent, AgentRunAdapter> = listOf(
  ClaudeAgentRunCommandBuilder(),
  CodexAgentRunCommandBuilder(),
  OpencodeAgentRunCommandBuilder(),
  JunieAgentRunCommandBuilder(),
).associate { builder ->
  builder.agent to ProcessAgentRunAdapter(
    agent = builder.agent,
    commandBuilder = builder,
    processRunner = processRunner,
  )
}
