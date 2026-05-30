package skillbill.launcher

import skillbill.install.model.InstallAgent
import skillbill.ports.agentrun.model.SkillRunRequest
import java.nio.file.Path

data class AgentRunCommand(
  val command: List<String>,
  val workingDirectory: Path,
  val timeout: kotlin.time.Duration,
  val environment: Map<String, String> = emptyMap(),
  val inheritEnvironment: Boolean = true,
)

interface AgentRunCommandBuilder {
  val agent: InstallAgent
  fun build(request: SkillRunRequest): AgentRunCommand
}

class ClaudeAgentRunCommandBuilder : AgentRunCommandBuilder {
  override val agent: InstallAgent = InstallAgent.CLAUDE

  override fun build(request: SkillRunRequest): AgentRunCommand = AgentRunCommand(
    command = listOf(
      "claude",
      "--print",
      "--output-format",
      "text",
      "--dangerously-skip-permissions",
      "--add-dir",
      request.repoRoot.toString(),
      continuationPrompt(request),
    ),
    workingDirectory = request.repoRoot,
    timeout = request.timeout,
  )
}

class CodexAgentRunCommandBuilder : AgentRunCommandBuilder {
  override val agent: InstallAgent = InstallAgent.CODEX

  override fun build(request: SkillRunRequest): AgentRunCommand = AgentRunCommand(
    command = listOf(
      "codex",
      "exec",
      "--cd",
      request.repoRoot.toString(),
      "--sandbox",
      "danger-full-access",
      "--ask-for-approval",
      "never",
      "--config",
      "shell_environment_policy.inherit=\"all\"",
      continuationPrompt(request),
    ),
    workingDirectory = request.repoRoot,
    timeout = request.timeout,
  )
}

class OpencodeAgentRunCommandBuilder : AgentRunCommandBuilder {
  override val agent: InstallAgent = InstallAgent.OPENCODE

  override fun build(request: SkillRunRequest): AgentRunCommand = AgentRunCommand(
    command = listOf(
      "opencode",
      "run",
      "--dir",
      request.repoRoot.toString(),
      "--dangerously-skip-permissions",
      continuationPrompt(request),
    ),
    workingDirectory = request.repoRoot,
    timeout = request.timeout,
  )
}

internal fun continuationPrompt(request: SkillRunRequest): String {
  val dbOption = request.dbPathOverride?.let { db -> " --db ${shellDisplay(db)}" }.orEmpty()
  val subtaskOption = request.subtaskId?.let { id -> " --subtask-id $id" }.orEmpty()
  val subtaskLine = request.subtaskId?.let { id -> "\nSubtask id: $id" }.orEmpty()
  return """
    Use the installed `bill-feature-implement` skill in non-interactive goal-continuation mode.

    Issue key: ${request.issueKey}$subtaskLine
    Goal-continuation: enabled.
    suppress_pr: true.

    Recover the durable workflow state with:
    `skill-bill$dbOption workflow continue ${shellDisplay(request.issueKey)}$subtaskOption`

    Treat durable workflow state as authoritative. Do not infer subtask success from stdout.
    Return exactly the `RESULT:` block required by the bill-feature-implement implementation subagent contract.
  """.trimIndent()
}

private fun shellDisplay(value: String): String = if (value.all { char -> char.isLetterOrDigit() || char in "-_./:" }) {
  value
} else {
  "'" + value.replace("'", "'\"'\"'") + "'"
}
