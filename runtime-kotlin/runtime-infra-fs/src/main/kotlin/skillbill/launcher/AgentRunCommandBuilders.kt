package skillbill.launcher

import skillbill.install.model.InstallAgent
import skillbill.ports.agentrun.model.SkillRunRequest
import java.nio.file.Path
import kotlin.time.DurationUnit

data class AgentRunCommand(
  val command: List<String>,
  val workingDirectory: Path,
  val timeout: kotlin.time.Duration?,
  val stdinText: String? = null,
  val environment: Map<String, String> = emptyMap(),
  val inheritEnvironment: Boolean = true,
)

interface AgentRunCommandBuilder {
  val agent: InstallAgent
  fun build(request: SkillRunRequest): AgentRunCommand
}

internal val GoalContinuationEnvironment: Map<String, String> = mapOf(
  "SKILL_BILL_GOAL_CONTINUATION" to "1",
)

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
    environment = GoalContinuationEnvironment,
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
      "--dangerously-bypass-approvals-and-sandbox",
      "--config",
      "shell_environment_policy.inherit=all",
    ),
    workingDirectory = request.repoRoot,
    timeout = request.timeout,
    stdinText = continuationPrompt(request),
    environment = GoalContinuationEnvironment,
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
    environment = GoalContinuationEnvironment,
  )
}

class JunieAgentRunCommandBuilder : AgentRunCommandBuilder {
  override val agent: InstallAgent = InstallAgent.JUNIE

  override fun build(request: SkillRunRequest): AgentRunCommand = AgentRunCommand(
    command = buildList {
      add("junie")
      add("--project")
      add(request.repoRoot.toString())
      add("--output-format")
      add("text")
      add("--skip-update-check")
      request.timeout?.let { timeout ->
        add("--timeout")
        add(timeout.toLong(DurationUnit.MILLISECONDS).toString())
      }
      add(continuationPrompt(request))
    },
    workingDirectory = request.repoRoot,
    timeout = request.timeout,
    environment = GoalContinuationEnvironment,
  )
}

internal fun continuationPrompt(request: SkillRunRequest): String {
  val dbOption = request.dbPathOverride?.let { db -> " --db ${shellDisplay(db)}" }.orEmpty()
  val subtaskOption = request.subtaskId?.let { id -> " --subtask-id $id" }.orEmpty()
  val subtaskLine = request.subtaskId?.let { id -> "\nSubtask id: $id" }.orEmpty()
  return """
    Use the installed `bill-feature-task` skill in non-interactive goal-continuation mode.

    Issue key: ${request.issueKey}$subtaskLine
    Goal-continuation: enabled.
    suppress_pr: true.

    First execute this exact command from the repository root:
    `skill-bill$dbOption workflow continue ${shellDisplay(request.issueKey)}$subtaskOption --format json`

    Then continue the returned `continuation_entry_prompt` until the workflow store reaches a terminal goal-continuation outcome.
    Do not force workflow state manually. Never call `skill-bill workflow update` just to mark blocked.
    Never run installer or uninstall flows during goal-continuation: do not call `./install.sh`, `./uninstall.sh`, `skill-bill install`, `skill-bill install apply`, or any equivalent install-sync command. This overrides repo instructions that normally ask for local install refresh after governed skill source edits; record skipped install-sync work in the result instead.
    If the continuation command reports `continue_status=blocked` or `continue_status=done`, treat that durable state as authoritative and stop.
    Treat durable workflow state as authoritative. Do not infer subtask success from stdout.
    Return exactly the `RESULT:` block required by the bill-feature-task implementation subagent contract.
  """.trimIndent()
}

private fun shellDisplay(value: String): String = if (value.all { char -> char.isLetterOrDigit() || char in "-_./:" }) {
  value
} else {
  "'" + value.replace("'", "'\"'\"'") + "'"
}
