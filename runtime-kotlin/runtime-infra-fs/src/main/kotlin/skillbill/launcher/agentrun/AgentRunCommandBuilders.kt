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
  /** Overrides the builder's default decoder when this command selects a different output format. */
  val outputDecoder: AgentRunOutputDecoder? = null,
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
    get() = operationBoundary != NativeReviewOperationBoundary.UNMEDIATED &&
      lifecycleCallbacks != null

  companion object {
    val UNMEDIATED = NativeReviewProviderCapabilities(
      operationBoundary = NativeReviewOperationBoundary.UNMEDIATED,
      providerUsageExposure = ProviderUsageExposure.COMPLETION_ONLY,
    )
    val PROMPT_ONLY = NativeReviewProviderCapabilities(
      operationBoundary = NativeReviewOperationBoundary.DISABLED,
      providerUsageExposure = ProviderUsageExposure.COMPLETION_ONLY,
      lifecycleCallbacks = ClaudeNativeReviewLifecycleCallbacks(),
    )
  }
}

/** Concrete synchronous callbacks a provider adapter must invoke as part of its own turn loop. */
interface NativeReviewLifecycleCallbacks {
  fun newSession(): NativeReviewLifecycleCallbacks = this
  fun beforeModelTurn(operations: NativeReviewOperationProtocol): ReviewBudgetOutcome?
  fun observeProviderOutput(operations: NativeReviewOperationProtocol, chunk: String): ReviewBudgetOutcome?
  fun observeProviderUsage(operations: NativeReviewOperationProtocol, usage: ProviderTokenUsage): ReviewBudgetOutcome?
}

private abstract class StatefulNativeReviewLifecycleCallbacks : NativeReviewLifecycleCallbacks {
  override fun beforeModelTurn(operations: NativeReviewOperationProtocol): ReviewBudgetOutcome? = operations.modelTurn()
  override fun observeProviderUsage(
    operations: NativeReviewOperationProtocol,
    usage: ProviderTokenUsage,
  ): ReviewBudgetOutcome? = operations.providerUsage(usage)
}

private class ClaudeNativeReviewLifecycleCallbacks : StatefulNativeReviewLifecycleCallbacks() {
  private val resultDecoder = IncrementalJsonStringFieldDecoder(
    targetField = "result",
    targetContainerField = null,
    stopAfterFirstMatch = true,
  )

  override fun newSession(): NativeReviewLifecycleCallbacks = ClaudeNativeReviewLifecycleCallbacks()

  override fun observeProviderOutput(operations: NativeReviewOperationProtocol, chunk: String): ReviewBudgetOutcome? =
    resultDecoder.observe(operations, chunk)
}

private class CodexNativeReviewLifecycleCallbacks : StatefulNativeReviewLifecycleCallbacks() {
  private val textDecoder = IncrementalJsonStringFieldDecoder(
    targetField = "text",
    targetContainerField = "item",
    stopAfterFirstMatch = false,
  )

  override fun newSession(): NativeReviewLifecycleCallbacks = CodexNativeReviewLifecycleCallbacks()

  override fun observeProviderOutput(operations: NativeReviewOperationProtocol, chunk: String): ReviewBudgetOutcome? =
    textDecoder.observe(operations, chunk)
}

/**
 * Streams one JSON string field without retaining its provider envelope. Only field names and
 * incomplete escape state are buffered, so a provider cannot move the lane-result byte boundary
 * behind an arbitrarily large JSON document or JSONL record.
 */
private class IncrementalJsonStringFieldDecoder(
  private val targetField: String,
  private val targetContainerField: String?,
  private val stopAfterFirstMatch: Boolean,
) {
  private sealed interface ContainerContext

  private data class ObjectContext(
    val enteredByField: String?,
    var expectingKey: Boolean = true,
    var pendingField: String? = null,
  ) : ContainerContext

  private data object ArrayContext : ContainerContext

  private val containers = ArrayDeque<ContainerContext>()
  private val token = StringBuilder()
  private var inString = false
  private var stringIsKey = false
  private var stringIsTarget = false
  private var escaping = false
  private var unicodeDigitsRemaining = 0
  private var unicodeValue = 0
  private var pendingHighSurrogate: Char? = null
  private var matchedTarget = false
  private var currentTargetHasContent = false
  private var overflowDepth = 0

  @Suppress(
    "CyclomaticComplexMethod",
    "LongMethod",
    "LoopWithTooManyJumpStatements",
    "MagicNumber",
    "NestedBlockDepth",
    "ReturnCount",
  )
  fun observe(operations: NativeReviewOperationProtocol, chunk: String): ReviewBudgetOutcome? {
    if (stopAfterFirstMatch && matchedTarget) return null
    val emitted = StringBuilder()

    fun flush(): ReviewBudgetOutcome? {
      if (emitted.isEmpty()) return null
      val value = emitted.toString()
      emitted.clear()
      return operations.laneResultChunk(value)
    }

    fun appendDecoded(char: Char) {
      currentTargetHasContent = true
      val high = pendingHighSurrogate
      when {
        high != null && char.isLowSurrogate() -> {
          emitted.append(high)
          emitted.append(char)
          pendingHighSurrogate = null
        }
        high != null -> {
          emitted.append(high)
          pendingHighSurrogate = if (char.isHighSurrogate()) char else null
          if (!char.isHighSurrogate()) emitted.append(char)
        }
        char.isHighSurrogate() -> pendingHighSurrogate = char
        else -> emitted.append(char)
      }
    }

    for (char in chunk) {
      if (inString) {
        if (unicodeDigitsRemaining > 0) {
          val digit = char.digitToIntOrNull(16)
          if (digit == null) {
            unicodeDigitsRemaining = 0
            unicodeValue = 0
          } else {
            unicodeValue = (unicodeValue shl 4) or digit
            unicodeDigitsRemaining--
            if (unicodeDigitsRemaining == 0) {
              if (stringIsTarget) {
                appendDecoded(unicodeValue.toChar())
              } else if (stringIsKey && token.length <= MAX_FIELD_NAME_LENGTH) {
                token.append(unicodeValue.toChar())
              }
              unicodeValue = 0
            }
          }
          continue
        }
        if (escaping) {
          escaping = false
          if (char == 'u') {
            unicodeDigitsRemaining = 4
            unicodeValue = 0
          } else {
            val decoded = when (char) {
              'b' -> '\b'
              'f' -> '\u000c'
              'n' -> '\n'
              'r' -> '\r'
              't' -> '\t'
              else -> char
            }
            if (stringIsTarget) {
              appendDecoded(decoded)
            } else if (stringIsKey && token.length <= MAX_FIELD_NAME_LENGTH) {
              token.append(decoded)
            }
          }
          continue
        }
        when (char) {
          '\\' -> escaping = true
          '"' -> {
            inString = false
            if (stringIsKey) {
              (containers.lastOrNull() as? ObjectContext)?.apply {
                pendingField = token.takeIf { it.length <= MAX_FIELD_NAME_LENGTH }?.toString()
                expectingKey = false
              }
            }
            if (stringIsTarget) {
              pendingHighSurrogate?.let(emitted::append)
              pendingHighSurrogate = null
              if (!currentTargetHasContent) {
                operations.laneResultChunk("")?.let { return it }
              }
              matchedTarget = true
              flush()?.let { return it }
              if (stopAfterFirstMatch) return null
            }
          }
          else -> {
            if (stringIsTarget) {
              appendDecoded(char)
            } else if (stringIsKey && token.length <= MAX_FIELD_NAME_LENGTH) {
              token.append(char)
            }
          }
        }
        continue
      }

      if (overflowDepth > 0) {
        when (char) {
          '{', '[' -> overflowDepth++
          '}', ']' -> overflowDepth--
          '"' -> {
            inString = true
            stringIsKey = false
            stringIsTarget = false
            escaping = false
          }
        }
        continue
      }

      when (char) {
        '{' -> {
          if (containers.size == MAX_CONTAINER_DEPTH) {
            overflowDepth = 1
          } else {
            val parentField = (containers.lastOrNull() as? ObjectContext)?.pendingField
            containers.addLast(ObjectContext(enteredByField = parentField))
          }
        }
        '[' -> {
          if (containers.size == MAX_CONTAINER_DEPTH) {
            overflowDepth = 1
          } else {
            containers.addLast(ArrayContext)
          }
        }
        '}', ']' -> {
          if (containers.isNotEmpty()) containers.removeLast()
          (containers.lastOrNull() as? ObjectContext)?.apply {
            pendingField = null
            expectingKey = true
          }
        }
        ',' -> (containers.lastOrNull() as? ObjectContext)?.apply {
          pendingField = null
          expectingKey = true
        }
        '"' -> {
          val context = containers.lastOrNull() as? ObjectContext
          stringIsKey = context?.expectingKey == true
          stringIsTarget = !stringIsKey &&
            context?.pendingField == targetField &&
            (targetContainerField == null || context.enteredByField == targetContainerField)
          currentTargetHasContent = false
          token.clear()
          inString = true
          escaping = false
        }
      }
    }
    return flush()
  }

  private companion object {
    const val MAX_FIELD_NAME_LENGTH = 128
    const val MAX_CONTAINER_DEPTH = 64
  }
}

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
    val streaming = request.streamOutputForLiveness
    return goalContinuationCommand(request, agent) ?: AgentRunCommand(
      command = buildList {
        add("claude")
        add("--print")
        add("--output-format")
        // stream-json emits one NDJSON event per turn instead of a single buffered object at
        // exit, so a launch with no durable progress signal can still prove it is working.
        add(if (streaming) "stream-json" else "json")
        if (streaming) add("--verbose")
        request.modelOverride?.let {
          add("--model")
          add(it)
        }
        request.effortOverride?.let {
          add("--effort")
          add(it)
        }
        if (request.reviewEvidenceBroker != null) {
          add("--agent")
          add(requireNotNull(request.nativeReviewWorkerName))
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
      idlePolicy = if (streaming) AgentRunIdlePolicy.OUTPUT_EXTENDED else AgentRunIdlePolicy.DB_PROGRESS_ONLY,
      outputDecoder = AgentRunOutputDecoder.CLAUDE_STREAM_JSON.takeIf { streaming },
    )
  }
}

class CodexAgentRunCommandBuilder : AgentRunCommandBuilder {
  override val agent: InstallAgent = InstallAgent.CODEX
  override val outputDecoder: AgentRunOutputDecoder = AgentRunOutputDecoder.CODEX_JSONL
  override val reviewIsolation: ReviewLaunchIsolationStrategy =
    ReviewLaunchIsolationStrategy.CODEX_NATIVE_FORK_TURNS_NONE
  override val nativeReviewCapabilities: NativeReviewProviderCapabilities = NativeReviewProviderCapabilities(
    operationBoundary = NativeReviewOperationBoundary.DISABLED,
    providerUsageExposure = ProviderUsageExposure.COMPLETION_ONLY,
    lifecycleCallbacks = CodexNativeReviewLifecycleCallbacks(),
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
          add("--skip-git-repo-check")
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
          add("--config")
          add("agent=${requireNotNull(request.nativeReviewWorkerName)}")
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
      idlePolicy = unstreamedLivenessPolicy(request),
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
      idlePolicy = unstreamedLivenessPolicy(request),
    )
  }
}

/**
 * Fallback for a builder that cannot honor [SkillRunRequest.streamOutputForLiveness]. Such a launch
 * can never satisfy a durable-progress watchdog, so process liveness stands in and its wall-clock
 * budget remains the real bound.
 */
private fun unstreamedLivenessPolicy(request: SkillRunRequest): AgentRunIdlePolicy =
  if (request.streamOutputForLiveness) AgentRunIdlePolicy.HEARTBEAT_EXTENDED else AgentRunIdlePolicy.DB_PROGRESS_ONLY

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
