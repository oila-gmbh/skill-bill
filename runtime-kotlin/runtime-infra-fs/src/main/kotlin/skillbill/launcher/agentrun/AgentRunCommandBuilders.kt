package skillbill.launcher.agentrun

import skillbill.install.model.InstallAgent
import skillbill.launcher.process.AgentRunIdlePolicy
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
  val usePtyStdio: Boolean = false,
  val idlePolicy: AgentRunIdlePolicy = AgentRunIdlePolicy.DB_PROGRESS_ONLY,
)

interface AgentRunCommandBuilder {
  val agent: InstallAgent
  fun build(request: SkillRunRequest): AgentRunCommand
}

internal val GoalContinuationEnvironment: Map<String, String> = mapOf(
  "SKILL_BILL_GOAL_CONTINUATION" to "1",
)

internal fun goalContinuationEnvironment(request: SkillRunRequest): Map<String, String> =
  request.goalContinuation?.let { context ->
    GoalContinuationEnvironment + buildMap {
      put("SKILL_BILL_GOAL_PARENT_ISSUE_KEY", context.parentIssueKey)
      put("SKILL_BILL_GOAL_SUBTASK_ID", context.subtaskId.toString())
      put("SKILL_BILL_GOAL_BRANCH", context.goalBranch)
      put("SKILL_BILL_SUPPRESS_PR", context.suppressPr.toString())
      context.parentWorkflowId?.let { put("SKILL_BILL_GOAL_PARENT_WORKFLOW_ID", it) }
      context.lastResumableStep?.let { put("SKILL_BILL_GOAL_LAST_RESUMABLE_STEP", it) }
    }
  }.orEmpty()

class ClaudeAgentRunCommandBuilder : AgentRunCommandBuilder {
  override val agent: InstallAgent = InstallAgent.CLAUDE

  override fun build(request: SkillRunRequest): AgentRunCommand =
    goalContinuationCommand(request, agent) ?: AgentRunCommand(
      command = buildList {
        add("claude")
        add("--print")
        add("--output-format")
        add("text")
        request.modelOverride?.let {
          add("--model")
          add(it)
        }
        request.effortOverride?.let {
          add("--effort")
          add(it)
        }
        add("--dangerously-skip-permissions")
        add("--add-dir")
        add(request.repoRoot.toString())
      },
      workingDirectory = request.repoRoot,
      timeout = request.timeout,
      stdinText = launchPrompt(request),
      environment = goalContinuationEnvironment(request),
    )
}

class CodexAgentRunCommandBuilder : AgentRunCommandBuilder {
  override val agent: InstallAgent = InstallAgent.CODEX

  override fun build(request: SkillRunRequest): AgentRunCommand =
    goalContinuationCommand(request, agent) ?: AgentRunCommand(
      command = buildList {
        add("codex")
        add("exec")
        add("--cd")
        add(request.repoRoot.toString())
        add("--dangerously-bypass-approvals-and-sandbox")
        add("--config")
        add("shell_environment_policy.inherit=all")
        request.modelOverride?.let {
          add("--model")
          add(it)
        }
        request.effortOverride?.let {
          add("--config")
          add("model_reasoning_effort=$it")
        }
      },
      workingDirectory = request.repoRoot,
      timeout = request.timeout,
      stdinText = launchPrompt(request),
      environment = goalContinuationEnvironment(request),
    )
}

class JunieAgentRunCommandBuilder : AgentRunCommandBuilder {
  override val agent: InstallAgent = InstallAgent.JUNIE

  override fun build(request: SkillRunRequest): AgentRunCommand =
    goalContinuationCommand(request, agent) ?: AgentRunCommand(
      command = buildList {
        require(request.modelOverride == null && request.effortOverride == null) {
          "junie cannot honor a model/effort directive; remove its execution_matrix entry or --phase-model assignment."
        }
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

internal fun launchPrompt(request: SkillRunRequest): String = requireNotNull(request.promptOverride) {
  "launchPrompt requires a promptOverride; goal-continuation runs spawn skill-bill directly."
}

internal fun goalContinuationCommand(request: SkillRunRequest, agent: InstallAgent): AgentRunCommand? {
  val context = request.goalContinuation
  return if (context == null || request.promptOverride != null) {
    null
  } else {
    AgentRunCommand(
      command = buildList {
        add("skill-bill")
        request.dbPathOverride?.let { db ->
          add("--db")
          add(db)
        }
        add("feature-task")
        val childWorkflowId = context.childWorkflowId?.takeIf(String::isNotBlank)
        val assignedWorkflowId = context.assignedWorkflowId?.takeIf(String::isNotBlank)
        if (childWorkflowId != null) {
          add("resume")
          add(childWorkflowId)
        } else {
          add("run")
        }
        add(request.issueKey)
        add(context.specPath)
        if (childWorkflowId == null && assignedWorkflowId != null) {
          add("--workflow-id")
          add(assignedWorkflowId)
        }
        add("--goal-parent-issue-key")
        add(context.parentIssueKey)
        add("--goal-subtask-id")
        add(context.subtaskId.toString())
        add("--goal-branch")
        add(context.goalBranch)
        add("--suppress-pr")
        context.parentWorkflowId?.takeIf(String::isNotBlank)?.let { parentWorkflowId ->
          add("--goal-parent-workflow-id")
          add(parentWorkflowId)
        }
        context.lastResumableStep?.takeIf(String::isNotBlank)?.let { step ->
          add("--goal-last-resumable-step")
          add(step)
        }
        add("--agent")
        add(agent.id)
      },
      workingDirectory = request.repoRoot,
      timeout = request.timeout,
      environment = goalContinuationEnvironment(request),
    )
  }
}
