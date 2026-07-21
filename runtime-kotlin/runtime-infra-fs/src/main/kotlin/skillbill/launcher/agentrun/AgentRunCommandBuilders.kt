package skillbill.launcher.agentrun

import com.fasterxml.jackson.databind.ObjectMapper
import skillbill.install.model.InstallAgent
import skillbill.launcher.process.AgentRunIdlePolicy
import skillbill.ports.agentrun.model.ConversationIsolation
import skillbill.ports.agentrun.model.ReviewLaunchIsolationStrategy
import skillbill.ports.agentrun.model.SkillRunGoalContinuationContext
import skillbill.ports.agentrun.model.SkillRunRequest
import skillbill.ports.review.NativeReviewOperationProtocol
import skillbill.review.context.model.ProviderTokenUsage
import skillbill.review.context.model.ReviewBudgetOutcome
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
  val conversationIsolation: ConversationIsolation? = null,
)

interface AgentRunCommandBuilder {
  val agent: InstallAgent
  val outputDecoder: AgentRunOutputDecoder get() = AgentRunOutputDecoder.PLAIN
  val reviewIsolation: ReviewLaunchIsolationStrategy get() = ReviewLaunchIsolationStrategy.UNSUPPORTED
  val nativeReviewCapabilities: NativeReviewProviderCapabilities
    get() = NativeReviewProviderCapabilities.UNMEDIATED
  fun build(request: SkillRunRequest): AgentRunCommand
}

/**
 * Capabilities required to enforce a governed native review at the boundary where work occurs.
 * A provider is launchable only when its adapter synchronously mediates operations and turns;
 * token usage may be streamed (enforceable) or completion-only (regression reporting).
 */
data class NativeReviewProviderCapabilities(
  val operationBoundary: NativeReviewOperationBoundary,
  val providerUsageExposure: ProviderUsageExposure,
  val lifecycleCallbacks: NativeReviewLifecycleCallbacks? = null,
) {
  val supportsGovernedLaunch: Boolean
    get() = operationBoundary == NativeReviewOperationBoundary.SYNCHRONOUS_BROKER &&
      lifecycleCallbacks != null

  companion object {
    val UNMEDIATED = NativeReviewProviderCapabilities(
      operationBoundary = NativeReviewOperationBoundary.UNMEDIATED,
      providerUsageExposure = ProviderUsageExposure.COMPLETION_ONLY,
    )
    val PROMPT_ONLY = NativeReviewProviderCapabilities(
      operationBoundary = NativeReviewOperationBoundary.DISABLED,
      providerUsageExposure = ProviderUsageExposure.COMPLETION_ONLY,
    )
  }
}

/** Concrete synchronous callbacks a provider adapter must invoke as part of its own turn loop. */
interface NativeReviewLifecycleCallbacks {
  fun newSession(): NativeReviewLifecycleCallbacks = this
  fun beforeModelTurn(operations: NativeReviewOperationProtocol): ReviewBudgetOutcome?
  fun observeProviderOutput(operations: NativeReviewOperationProtocol, chunk: String): ReviewBudgetOutcome?
  fun observeProviderUsage(operations: NativeReviewOperationProtocol, usage: ProviderTokenUsage): ReviewBudgetOutcome?

  companion object {
    val BROKERED = object : NativeReviewLifecycleCallbacks {
      override fun newSession(): NativeReviewLifecycleCallbacks = BufferedCodexLifecycleCallbacks()
      override fun beforeModelTurn(operations: NativeReviewOperationProtocol) = operations.modelTurn()
      override fun observeProviderOutput(operations: NativeReviewOperationProtocol, chunk: String) =
        decodeCodexUsageChunk(chunk)?.let(operations::providerUsage)
      override fun observeProviderUsage(operations: NativeReviewOperationProtocol, usage: ProviderTokenUsage) =
        operations.providerUsage(usage)
    }
  }
}

private class BufferedCodexLifecycleCallbacks : NativeReviewLifecycleCallbacks {
  private val pending = StringBuilder()

  // Codex reports the real turn boundary on stdout. Process startup is not a model turn: the
  // process can fail before the provider accepts any work.
  override fun beforeModelTurn(operations: NativeReviewOperationProtocol): ReviewBudgetOutcome? = null

  override fun observeProviderOutput(operations: NativeReviewOperationProtocol, chunk: String): ReviewBudgetOutcome? {
    pending.append(chunk)
    var outcome: ReviewBudgetOutcome? = null
    while (outcome == null) {
      val newline = pending.indexOf("\n")
      if (newline < 0) break
      val line = pending.substring(0, newline)
      pending.delete(0, newline + 1)
      outcome = decodeCodexTurnStart(line)?.let { operations.modelTurn() }
        ?: decodeCodexUsageChunk(line)?.let(operations::providerUsage)
    }
    return outcome
  }

  override fun observeProviderUsage(operations: NativeReviewOperationProtocol, usage: ProviderTokenUsage) =
    operations.providerUsage(usage)
}

private fun decodeCodexTurnStart(line: String): Boolean? = runCatching {
  ObjectMapper().readTree(line).path("type").asText() == "turn.started"
}.getOrNull()?.takeIf { it }

private fun decodeCodexUsageChunk(chunk: String): ProviderTokenUsage? = chunk.lineSequence()
  .mapNotNull { line -> runCatching { ObjectMapper().readTree(line) }.getOrNull() }
  .map { event -> event.path("usage") }
  .filterNot { usage -> usage.isMissingNode || usage.isNull }
  .map { usage ->
    ProviderTokenUsage(
      inputTokens = usage.path("input_tokens").takeIf { it.isIntegralNumber }?.longValue(),
      cachedInputTokens = usage.path("cached_input_tokens").takeIf { it.isIntegralNumber }?.longValue(),
      outputTokens = usage.path("output_tokens").takeIf { it.isIntegralNumber }?.longValue(),
      reasoningTokens = usage.path("reasoning_tokens").takeIf { it.isIntegralNumber }?.longValue(),
      totalTokens = usage.path("total_tokens").takeIf { it.isIntegralNumber }?.longValue(),
    )
  }
  .lastOrNull()

enum class NativeReviewOperationBoundary { SYNCHRONOUS_BROKER, DISABLED, UNMEDIATED }

enum class ProviderUsageExposure { IN_FLIGHT_ENFORCEABLE, COMPLETION_ONLY }

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
  override val reviewIsolation: ReviewLaunchIsolationStrategy = ReviewLaunchIsolationStrategy.FRESH_PROCESS
  override val nativeReviewCapabilities: NativeReviewProviderCapabilities =
    NativeReviewProviderCapabilities.PROMPT_ONLY

  override fun build(request: SkillRunRequest): AgentRunCommand {
    requireProcessLaunch(request, reviewIsolation)
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
        if (request.reviewEvidenceBroker != null) {
          add("--tools")
          add("")
        }
        add("--dangerously-skip-permissions")
        add("--add-dir")
        add(request.repoRoot.toString())
      },
      workingDirectory = request.repoRoot,
      timeout = request.timeout,
      stdinText = launchPrompt(request),
      environment = goalContinuationEnvironment(request),
      inheritEnvironment = request.reviewEvidenceBroker == null,
      conversationIsolation = request.conversationIsolation,
    )
  }
}

class CodexAgentRunCommandBuilder : AgentRunCommandBuilder {
  override val agent: InstallAgent = InstallAgent.CODEX
  override val outputDecoder: AgentRunOutputDecoder = AgentRunOutputDecoder.CODEX_JSONL
  override val reviewIsolation: ReviewLaunchIsolationStrategy =
    ReviewLaunchIsolationStrategy.CODEX_NATIVE_FORK_TURNS_NONE
  override val nativeReviewCapabilities: NativeReviewProviderCapabilities = NativeReviewProviderCapabilities(
    operationBoundary = NativeReviewOperationBoundary.SYNCHRONOUS_BROKER,
    providerUsageExposure = ProviderUsageExposure.IN_FLIGHT_ENFORCEABLE,
    lifecycleCallbacks = NativeReviewLifecycleCallbacks.BROKERED,
  )

  override fun build(request: SkillRunRequest): AgentRunCommand {
    requireProcessLaunch(request, reviewIsolation)
    return goalContinuationCommand(request, agent) ?: AgentRunCommand(
      command = buildList {
        add("codex")
        add("exec")
        add("--json")
        add("--cd")
        add(request.repoRoot.toString())
        if (request.reviewEvidenceBroker == null) {
          add("--dangerously-bypass-approvals-and-sandbox")
          add("--config")
          add("shell_environment_policy.inherit=all")
        } else {
          add("--ignore-user-config")
          add("--sandbox")
          add("read-only")
          add("--config")
          add("shell_environment_policy.inherit=none")
          add("--config")
          add("fork_turns=none")
          add("--config")
          add("tools.web_search=false")
          add("--config")
          add("tools.shell=false")
        }
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
      inheritEnvironment = request.reviewEvidenceBroker == null,
      conversationIsolation = request.conversationIsolation,
    )
  }
}

class JunieAgentRunCommandBuilder : AgentRunCommandBuilder {
  override val agent: InstallAgent = InstallAgent.JUNIE
  override val reviewIsolation: ReviewLaunchIsolationStrategy = ReviewLaunchIsolationStrategy.FRESH_PROCESS

  override fun build(request: SkillRunRequest): AgentRunCommand {
    requireProcessLaunch(request, reviewIsolation)
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
      inheritEnvironment = request.reviewEvidenceBroker == null,
      conversationIsolation = request.conversationIsolation,
    )
  }
}

internal fun launchPrompt(request: SkillRunRequest): String = requireNotNull(request.promptOverride) {
  "launchPrompt requires a promptOverride; goal-continuation runs spawn skill-bill directly."
}

private fun requireProcessLaunch(request: SkillRunRequest, strategy: ReviewLaunchIsolationStrategy) {
  request.conversationIsolation?.let { isolation ->
    require(strategy.supported && isolation == ConversationIsolation.NONE) {
      "Governed specialist launches require a supported fresh-context strategy."
    }
    if (strategy == ReviewLaunchIsolationStrategy.CODEX_NATIVE_FORK_TURNS_NONE) {
      require(strategy.forkTurns == isolation.forkTurns) {
        "Governed Codex review launches require fork_turns none."
      }
    }
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
