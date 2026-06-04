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

internal fun goalContinuationEnvironment(request: SkillRunRequest): Map<String, String> =
  GoalContinuationEnvironment + buildMap {
    val context = request.goalContinuation
    if (context != null) {
      put("SKILL_BILL_GOAL_PARENT_ISSUE_KEY", context.parentIssueKey)
      put("SKILL_BILL_GOAL_SUBTASK_ID", context.subtaskId.toString())
      put("SKILL_BILL_GOAL_BRANCH", context.goalBranch)
      put("SKILL_BILL_SUPPRESS_PR", context.suppressPr.toString())
      context.parentWorkflowId?.let { put("SKILL_BILL_GOAL_PARENT_WORKFLOW_ID", it) }
      context.lastResumableStep?.let { put("SKILL_BILL_GOAL_LAST_RESUMABLE_STEP", it) }
    }
  }

class ClaudeAgentRunCommandBuilder : AgentRunCommandBuilder {
  override val agent: InstallAgent = InstallAgent.CLAUDE

  override fun build(request: SkillRunRequest): AgentRunCommand = AgentRunCommand(
    // The prompt is delivered via stdin, not as a trailing argv token: `--add-dir`
    // is variadic and would otherwise swallow the prompt as an extra directory,
    // leaving `claude --print` with no input and blocking forever on stdin.
    command = listOf(
      "claude",
      "--print",
      "--output-format",
      "text",
      "--dangerously-skip-permissions",
      "--add-dir",
      request.repoRoot.toString(),
    ),
    workingDirectory = request.repoRoot,
    timeout = request.timeout,
    stdinText = launchPrompt(request),
    environment = goalContinuationEnvironment(request),
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
    stdinText = launchPrompt(request),
    environment = goalContinuationEnvironment(request),
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
      launchPrompt(request),
    ),
    workingDirectory = request.repoRoot,
    timeout = request.timeout,
    environment = goalContinuationEnvironment(request),
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
      add(launchPrompt(request))
    },
    workingDirectory = request.repoRoot,
    timeout = request.timeout,
    environment = goalContinuationEnvironment(request),
  )
}

// A caller-supplied prompt override (e.g. a feature-task-runtime phase briefing) replaces the
// default goal-continuation prompt wholesale; the delivery mechanics (stdin vs argv) stay
// per-agent.
internal fun launchPrompt(request: SkillRunRequest): String = request.promptOverride ?: continuationPrompt(request)

internal fun continuationPrompt(request: SkillRunRequest): String {
  val dbOption = request.dbPathOverride?.let { db -> " --db ${shellDisplay(db)}" }.orEmpty()
  val subtaskOption = request.subtaskId?.let { id -> " --subtask-id $id" }.orEmpty()
  val subtaskLine = request.subtaskId?.let { id -> "\nSubtask id: $id" }.orEmpty()
  val runtimeContext = request.goalContinuation?.let { context ->
    """
    Runtime goal-continuation context:
    parent_issue_key: ${context.parentIssueKey}
    subtask_id: ${context.subtaskId}
    goal_branch: ${context.goalBranch}
    suppress_pr: ${context.suppressPr}
    parent_workflow_id: ${context.parentWorkflowId.orEmpty()}
    last_resumable_step: ${context.lastResumableStep.orEmpty()}
    """.trimIndent()
  }.orEmpty()
  // SKILL-64 Subtask 3 (AC1-AC3, AC12, AC13): the compact continuation output is
  // the normal activation contract. Still run `workflow continue` first (AC1);
  // do not imply reasoning over full workflow JSON by default (AC2); fetch the
  // read-only full-state command only when compact output says required context
  // is missing/truncated (AC3); bound broad tool output (AC13); and classify
  // diagnostic/manual `continue` calls so they are not mistaken for retries (AC12).
  return """
    Use the installed `bill-feature-task` skill in non-interactive goal-continuation mode.

    Issue key: ${request.issueKey}$subtaskLine
    Goal-continuation: enabled.
    suppress_pr: true.
    $runtimeContext

    First execute this exact command from the repository root:
    `skill-bill$dbOption workflow continue ${shellDisplay(request.issueKey)}$subtaskOption --format json`

    The continuation output is compact and is your normal activation contract. Act on the returned
    `continuation_entry_prompt` directly. Do NOT inspect or reason over full workflow JSON by default, and do
    NOT request the full durable state just to orient yourself.

    Only if the compact continuation output explicitly reports that required context is missing or truncated,
    fetch read-only full state once with:
    `skill-bill$dbOption workflow show ${shellDisplay(request.issueKey)} --format json`
    Never call `workflow continue` a second time merely to inspect state; a second `continue` is a retry of the
    activation contract, not a read. Any diagnostic or manual-inspection `continue` you make is classified as
    diagnostic and must not be treated as a retry of the subtask.

    Bound broad tool output by default: prefer narrow, scoped follow-up reads over dumping large command output
    into the conversation. Do not paste large file or command dumps into history; request the specific lines or
    fields you need.

    Then continue until the workflow store reaches a terminal goal-continuation outcome.
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
