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
  /**
   * Additional parent-environment keys to pass through during an isolated launch
   * (inheritEnvironment = false). Builders declare the keys their agent CLI needs from the ambient
   * environment (provider credentials, endpoint overrides, proxy settings) so the infra runner does
   * not need per-agent knowledge. Has no effect when inheritEnvironment is true.
   */
  val environmentPassthroughKeys: Set<String> = emptySet(),
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

private class CursorNativeReviewLifecycleCallbacks : StatefulNativeReviewLifecycleCallbacks() {
  private val resultDecoder = IncrementalJsonStringFieldDecoder(
    targetField = "result",
    targetContainerField = null,
    stopAfterFirstMatch = true,
  )

  override fun newSession(): NativeReviewLifecycleCallbacks = CursorNativeReviewLifecycleCallbacks()

  override fun observeProviderOutput(operations: NativeReviewOperationProtocol, chunk: String): ReviewBudgetOutcome? =
    resultDecoder.observe(operations, chunk)
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

// Provider credentials and endpoint overrides the Claude CLI reads from the environment. Passed
// through during isolated review launches so the delegated worker authenticates via the same
// provider configuration as the parent process, regardless of what is on disk.
private val PROXY_PASSTHROUGH_KEYS: Set<String> = setOf(
  "HTTP_PROXY",
  "HTTPS_PROXY",
  "NO_PROXY",
  "http_proxy",
  "https_proxy",
  "no_proxy",
)

private val CLAUDE_PROVIDER_PASSTHROUGH_KEYS: Set<String> = setOf(
  "ANTHROPIC_API_KEY",
  "ANTHROPIC_AUTH_TOKEN",
  "ANTHROPIC_BASE_URL",
  "CLAUDE_CODE_USE_BEDROCK",
  "AWS_ACCESS_KEY_ID",
  "AWS_SECRET_ACCESS_KEY",
  "AWS_SESSION_TOKEN",
  "AWS_REGION",
  "CLAUDE_CODE_USE_VERTEX",
  "ANTHROPIC_VERTEX_PROJECT_ID",
  "CLOUD_ML_REGION",
  "GOOGLE_APPLICATION_CREDENTIALS",
) + PROXY_PASSTHROUGH_KEYS

private val CODEX_PROVIDER_PASSTHROUGH_KEYS: Set<String> = setOf(
  "OPENAI_API_KEY",
  "OPENAI_BASE_URL",
  "CODEX_HOME",
) + PROXY_PASSTHROUGH_KEYS

private val JUNIE_PROVIDER_PASSTHROUGH_KEYS: Set<String> = PROXY_PASSTHROUGH_KEYS

private val CURSOR_PROVIDER_PASSTHROUGH_KEYS: Set<String> = setOf(
  "CURSOR_API_KEY",
) + PROXY_PASSTHROUGH_KEYS

/**
 * Claude Code sizes its own auto-compaction trigger against the model's context window, so a phase
 * on a 1M-context model never compacts at the few-hundred-thousand tokens a phase actually reaches.
 * These variables re-point that trigger at the window the runtime chose for the phase.
 */
internal fun compactionEnvironment(request: SkillRunRequest): Map<String, String> =
  request.compaction?.let { directive ->
    mapOf(
      "CLAUDE_CODE_AUTO_COMPACT_WINDOW" to directive.windowTokens.toString(),
      "CLAUDE_AUTOCOMPACT_PCT_OVERRIDE" to directive.triggerPct.toString(),
    )
  }.orEmpty()

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
      environment = goalContinuationEnvironment(request) + compactionEnvironment(request),
      inheritEnvironment = request.reviewEvidenceBroker == null,
      conversationIsolation = request.conversationIsolation,
      idlePolicy = when {
        streaming -> AgentRunIdlePolicy.OUTPUT_EXTENDED
        request.readOnlyPhase -> AgentRunIdlePolicy.HEARTBEAT_EXTENDED
        else -> AgentRunIdlePolicy.DB_PROGRESS_ONLY
      },
      outputDecoder = AgentRunOutputDecoder.CLAUDE_STREAM_JSON.takeIf { streaming },
      environmentPassthroughKeys =
      if (request.reviewEvidenceBroker != null) CLAUDE_PROVIDER_PASSTHROUGH_KEYS else emptySet(),
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
      idlePolicy = codexLivenessPolicy(request),
      environmentPassthroughKeys =
      if (request.reviewEvidenceBroker != null) CODEX_PROVIDER_PASSTHROUGH_KEYS else emptySet(),
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
      environmentPassthroughKeys =
      if (request.reviewEvidenceBroker != null) JUNIE_PROVIDER_PASSTHROUGH_KEYS else emptySet(),
    )
  }
}

class CursorAgentRunCommandBuilder : AgentRunCommandBuilder {
  override val agent: InstallAgent = InstallAgent.CURSOR
  override val outputDecoder: AgentRunOutputDecoder = AgentRunOutputDecoder.CURSOR_STREAM_JSON
  override val reviewIsolation: ReviewLaunchIsolationStrategy = ReviewLaunchIsolationStrategy.FRESH_PROCESS
  override val nativeReviewCapabilities: NativeReviewProviderCapabilities = NativeReviewProviderCapabilities(
    operationBoundary = NativeReviewOperationBoundary.DISABLED,
    providerUsageExposure = ProviderUsageExposure.COMPLETION_ONLY,
    lifecycleCallbacks = CursorNativeReviewLifecycleCallbacks(),
  )

  override fun build(request: SkillRunRequest): AgentRunCommand {
    requireProcessLaunch(request, reviewIsolation)
    val streaming = request.streamOutputForLiveness
    val isReviewLaunch = request.reviewEvidenceBroker != null

    return goalContinuationCommand(request, agent) ?: AgentRunCommand(
      command = buildCursorCommand(request, isReviewLaunch, streaming),
      workingDirectory = request.repoRoot,
      timeout = request.timeout,
      stdinText = launchPrompt(request),
      environment = GoalContinuationEnvironment + goalContinuationEnvironment(request),
      inheritEnvironment = !isReviewLaunch,
      conversationIsolation = request.conversationIsolation,
      idlePolicy = when {
        streaming -> AgentRunIdlePolicy.OUTPUT_EXTENDED
        request.readOnlyPhase -> AgentRunIdlePolicy.HEARTBEAT_EXTENDED
        else -> AgentRunIdlePolicy.DB_PROGRESS_ONLY
      },
      environmentPassthroughKeys = if (isReviewLaunch) CURSOR_PROVIDER_PASSTHROUGH_KEYS else emptySet(),
    )
  }

  private fun buildCursorCommand(request: SkillRunRequest, isReviewLaunch: Boolean, streaming: Boolean): List<String> =
    buildList {
      add("agent")
      add("--print")

      if (isReviewLaunch) {
        request.nativeReviewWorkerName?.let { worker ->
          add("/$worker")
        }
        add("--workspace")
        add(request.repoRoot.toString())
      } else {
        add("--force")
        add("--trust")
        add("--approve-mcps")
        add("--workspace")
        add(request.repoRoot.toString())
      }

      add("--output-format")
      add("stream-json")
      if (streaming) add("--stream-partial-output")

      request.modelOverride?.let { model ->
        val modelArg = request.effortOverride?.let { effort ->
          mergeModelEffort(model, effort)
        } ?: model
        add("--model")
        add(modelArg)
      }
      request.effortOverride?.let { effort ->
        if (request.modelOverride == null) {
          require(false) {
            "Cursor effort directive requires a model directive; add a model directive or remove the effort assignment."
          }
        }
      }
    }

  private fun mergeModelEffort(model: String, effort: String): String {
    val effortPrefix = "[effort="
    val effortSuffix = "]"

    fun extractExistingEffort(modelString: String): String? {
      val effortStart = modelString.indexOf(effortPrefix)
      if (effortStart == -1) return null
      val effortEnd = modelString.indexOf(effortSuffix, effortStart)
      if (effortEnd == -1) return null
      return modelString.substring(effortStart + effortPrefix.length, effortEnd)
    }

    val existingEffort = extractExistingEffort(model)
    return when {
      existingEffort == null -> "$model$effortPrefix$effort$effortSuffix"
      existingEffort == effort -> model
      else ->
        error(
          "Conflicting effort directive: model string '$model' declares effort='$existingEffort', but " +
            "directive specifies effort='$effort'. Remove the conflict from the execution_matrix or " +
            "phase assignment.",
        )
    }
  }
}

private fun codexLivenessPolicy(request: SkillRunRequest): AgentRunIdlePolicy = if (request.streamOutputForLiveness) {
  AgentRunIdlePolicy.HEARTBEAT_EXTENDED
} else {
  AgentRunIdlePolicy.DB_PROGRESS_ONLY
}

/**
 * Fallback for a builder that cannot honor [SkillRunRequest.streamOutputForLiveness]. Such a launch
 * can never satisfy a durable-progress watchdog, so process liveness stands in and its wall-clock
 * budget remains the real bound. A read-only phase also qualifies for heartbeat extension because it
 * produces no durable workflow rows by construction.
 */
private fun unstreamedLivenessPolicy(request: SkillRunRequest): AgentRunIdlePolicy =
  if (request.streamOutputForLiveness || request.readOnlyPhase) {
    AgentRunIdlePolicy.HEARTBEAT_EXTENDED
  } else {
    AgentRunIdlePolicy.DB_PROGRESS_ONLY
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
    idlePolicy = unstreamedLivenessPolicy(request),
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
