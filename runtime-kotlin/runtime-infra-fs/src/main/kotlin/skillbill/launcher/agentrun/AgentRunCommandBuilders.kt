package skillbill.launcher.agentrun

import com.fasterxml.jackson.databind.ObjectMapper
import skillbill.install.model.InstallAgent
import skillbill.launcher.process.AgentRunIdlePolicy
import skillbill.ports.agentrun.model.ReviewLaunchIsolationStrategy
import skillbill.ports.agentrun.model.SkillRunGoalContinuationContext
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
  val outputDecoder: AgentRunOutputDecoder get() = AgentRunOutputDecoder.PLAIN
  val reviewIsolation: ReviewLaunchIsolationStrategy get() = ReviewLaunchIsolationStrategy.UNSUPPORTED
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
      put("SKILL_BILL_CODE_REVIEW_MODE", context.codeReviewMode.wireValue)
    }
  }.orEmpty()

class ClaudeAgentRunCommandBuilder : AgentRunCommandBuilder {
  override val agent: InstallAgent = InstallAgent.CLAUDE
  override val outputDecoder: AgentRunOutputDecoder = AgentRunOutputDecoder.CLAUDE_JSON
  override val reviewIsolation: ReviewLaunchIsolationStrategy = ReviewLaunchIsolationStrategy.FRESH_NATIVE_SUBAGENT

  override fun build(request: SkillRunRequest): AgentRunCommand {
    requireProcessLaunch(request)
    return goalContinuationCommand(request, agent) ?: AgentRunCommand(
      command = buildList {
        add("claude")
        add("--print")
        add("--output-format")
        add("json")
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
}

class CodexAgentRunCommandBuilder : AgentRunCommandBuilder {
  override val agent: InstallAgent = InstallAgent.CODEX
  override val outputDecoder: AgentRunOutputDecoder = AgentRunOutputDecoder.CODEX_JSONL
  override val reviewIsolation: ReviewLaunchIsolationStrategy = ReviewLaunchIsolationStrategy.CODEX_FORK_TURNS_NONE

  override fun build(request: SkillRunRequest): AgentRunCommand {
    requireProcessLaunch(request)
    return goalContinuationCommand(request, agent) ?: AgentRunCommand(
      command = buildList {
        add("codex")
        add("exec")
        add("--json")
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
}

class JunieAgentRunCommandBuilder : AgentRunCommandBuilder {
  override val agent: InstallAgent = InstallAgent.JUNIE

  override fun build(request: SkillRunRequest): AgentRunCommand {
    requireProcessLaunch(request)
    return goalContinuationCommand(request, agent) ?: AgentRunCommand(
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
}

internal fun launchPrompt(request: SkillRunRequest): String = requireNotNull(request.promptOverride) {
  "launchPrompt requires a promptOverride; goal-continuation runs spawn skill-bill directly."
}

private fun requireProcessLaunch(request: SkillRunRequest) {
  require(request.conversationIsolation == null) {
    "Governed specialist isolation cannot be projected by a headless process launch; " +
      "use the native specialist-launch adapter."
  }
}

internal fun goalContinuationCommand(request: SkillRunRequest, agent: InstallAgent): AgentRunCommand? {
  val context = request.goalContinuation ?: return null
  if (request.promptOverride != null) return null
  return AgentRunCommand(
    command = goalContinuationArguments(request, agent),
    workingDirectory = request.repoRoot,
    timeout = request.timeout,
    environment = goalContinuationEnvironment(request),
  )
}

private fun goalContinuationArguments(request: SkillRunRequest, agent: InstallAgent): List<String> {
  val context = requireNotNull(request.goalContinuation)
  val childWorkflowId = context.childWorkflowId?.takeIf(String::isNotBlank)
  val assignedWorkflowId = context.assignedWorkflowId?.takeIf(String::isNotBlank)
  return buildList {
    add("skill-bill")
    request.dbPathOverride?.let { db ->
      add("--db")
      add(db)
    }
    add("feature-task")
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
    addGoalContinuationArguments(context)
    add("--agent")
    add(agent.id)
  }
}

private fun MutableList<String>.addGoalContinuationArguments(context: SkillRunGoalContinuationContext) {
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
  add("--code-review-mode")
  add(context.codeReviewMode.wireValue)
  context.parallelReviewAgent?.takeIf(String::isNotBlank)?.let { parallelAgent ->
    add("--parallel-review-agent")
    add(parallelAgent)
  }
  context.reviewBaseline?.let { baseline ->
    add("--goal-review-base-sha")
    add(baseline.reviewBaseSha)
    baseline.baselineUntrackedPaths.forEach { path ->
      add("--goal-baseline-untracked-path")
      add(path)
    }
  }
  if (context.agentAddonSelection.entries.isNotEmpty()) {
    add("--agent-addon-selection-json")
    add(
      ObjectMapper().writeValueAsString(
        linkedMapOf(
          "contract_version" to "0.1",
          "entries" to context.agentAddonSelection.entries.map { entry ->
            linkedMapOf(
              "slug" to entry.slug,
              "source_identity" to entry.sourceIdentity,
              "content_sha256" to entry.contentSha256,
            )
          },
        ),
      ),
    )
  }
}
