package skillbill.launcher.agentrun

import skillbill.install.model.AGENT_LAUNCHER_CLIS
import skillbill.install.model.AgentLauncherCli
import skillbill.install.model.InstallAgent
import skillbill.launcher.mcp.GovernedReviewMcpConfigWriter
import skillbill.launcher.mcp.McpRegistrationOperations
import skillbill.launcher.process.AgentRunIdlePolicy
import skillbill.ports.agentrun.model.ConversationIsolation
import skillbill.ports.agentrun.model.ReviewLaunchIsolationStrategy
import skillbill.ports.agentrun.model.SkillRunRequest
import skillbill.ports.review.model.GovernedReviewEvidenceCodec
import java.nio.file.Path
import kotlin.time.Duration

data class AgentRunCommand(
  val command: List<String>,
  val workingDirectory: Path,
  val timeout: Duration?,
  val stdinText: String? = null,
  val environment: Map<String, String> = emptyMap(),
  val inheritEnvironment: Boolean = true,
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
  val governedReviewLaunchCapability: GovernedReviewLaunchCapability

  /** The headless CLI this builder's command execs, resolved against PATH before every spawn. */
  val launcherCli: AgentLauncherCli get() = requireNotNull(AGENT_LAUNCHER_CLIS[agent]) {
    "Agent '${agent.id}' has a headless command builder but no declared launcher CLI."
  }

  fun build(request: SkillRunRequest): AgentRunCommand
}

internal val GoalContinuationEnvironment: Map<String, String> = mapOf(
  "SKILL_BILL_GOAL_CONTINUATION" to "1",
)

// Provider credentials and endpoint overrides the Claude CLI reads from the environment. Passed
// through during isolated review launches so the delegated worker authenticates via the same
// provider configuration as the parent process, regardless of what is on disk.
internal val PROXY_PASSTHROUGH_KEYS: Set<String> = setOf(
  "HTTP_PROXY",
  "HTTPS_PROXY",
  "NO_PROXY",
  "http_proxy",
  "https_proxy",
  "no_proxy",
)

internal val CLAUDE_PROVIDER_PASSTHROUGH_KEYS: Set<String> = setOf(
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

internal val CODEX_PROVIDER_PASSTHROUGH_KEYS: Set<String> = setOf(
  "OPENAI_API_KEY",
  "OPENAI_BASE_URL",
  "CODEX_HOME",
) + PROXY_PASSTHROUGH_KEYS

internal val JUNIE_PROVIDER_PASSTHROUGH_KEYS: Set<String> = PROXY_PASSTHROUGH_KEYS

internal val CURSOR_PROVIDER_PASSTHROUGH_KEYS: Set<String> = setOf(
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
      put("SKILL_BILL_VALIDATION_DEPTH", context.validationDepth.wireValue)
      put("SKILL_BILL_QUALITY_GATE_SELECTION", context.qualityGateSelection.wireValue)
    }
  }.orEmpty()

/**
 * Resolves a feature-task model directive for a claude child against the provider the parent
 * process was launched with. A directive naming an Anthropic model (`claude-*` or an
 * opus/sonnet/haiku alias) is only servable by the official Anthropic endpoint. A non-Anthropic
 * endpoint (for example `api.deepseek.com`) does not serve those names and silently substitutes
 * its own model, so the child would run on a model the operator never chose. In that case the
 * child falls back to the model the parent process itself was launched with — the only model the
 * operator actually selected. A directive naming an explicit model the endpoint serves (for
 * example `deepseek-v4-flash`) passes through unchanged.
 */
internal fun resolveClaudeModelDirective(directive: String?, providerEnvironment: Map<String, String>): String? {
  if (directive == null) return null
  val endpoint = providerEnvironment["ANTHROPIC_BASE_URL"]
  if (endpoint != null && !isOfficialAnthropicEndpoint(endpoint) && isAnthropicModelReference(directive)) {
    return providerEnvironment["ANTHROPIC_MODEL"]?.takeIf(String::isNotBlank) ?: directive
  }
  return directive
}

internal fun isOfficialAnthropicEndpoint(baseUrl: String): Boolean = baseUrl.contains("anthropic.com")

internal val ANTHROPIC_MODEL_ALIASES = setOf("opus", "sonnet", "haiku")

internal fun isAnthropicModelReference(model: String): Boolean =
  model.startsWith("claude-") || model in ANTHROPIC_MODEL_ALIASES

internal val GOVERNED_REVIEW_TOOLS: List<String> = GovernedReviewEvidenceCodec.OPERATIONS.map { operation ->
  "mcp__${GovernedReviewEvidenceCodec.SERVER_NAME}__$operation"
}

internal val REVIEW_FAN_OUT_TOOLS = (listOf("Agent", "Task") + GOVERNED_REVIEW_TOOLS).joinToString(",")

internal fun governedReviewToolList(fanOut: Boolean): String =
  if (fanOut) REVIEW_FAN_OUT_TOOLS else GOVERNED_REVIEW_TOOLS.joinToString(",")

class ClaudeAgentRunCommandBuilder(
  internal val providerEnvironment: Map<String, String> = System.getenv(),
  override val governedReviewLaunchCapability: GovernedReviewLaunchCapability = GovernedReviewLaunchCapability(
    governedOnlyTooling = true,
    mcpIsolation = true,
    configFormat = McpRegistrationOperations.configFormatFor(InstallAgent.CLAUDE),
  ),
) : AgentRunCommandBuilder {
  override val agent: InstallAgent = InstallAgent.CLAUDE
  override val outputDecoder: AgentRunOutputDecoder = AgentRunOutputDecoder.CLAUDE_JSON
  override val reviewIsolation: ReviewLaunchIsolationStrategy = ReviewLaunchIsolationStrategy.FRESH_PROCESS

  override fun build(request: SkillRunRequest): AgentRunCommand {
    requireProcessLaunch(request, reviewIsolation)
    requireGovernedReviewLaunch(request, agent, governedReviewLaunchCapability)
    val streaming = request.streamProviderOutput || request.streamOutputForLiveness
    return goalContinuationCommand(request, agent) ?: AgentRunCommand(
      command = buildList {
        add("claude")
        add("--print")
        add("--output-format")
        // stream-json emits one NDJSON event per turn instead of a single buffered object at
        // exit, so a launch with no durable progress signal can still prove it is working.
        add(if (streaming) "stream-json" else "json")
        if (streaming) add("--verbose")
        resolveClaudeModelDirective(request.modelOverride, providerEnvironment)?.let {
          add("--model")
          add(it)
        }
        request.effortOverride?.let {
          add("--effort")
          add(it)
        }
        request.reviewEvidenceEndpoint?.let { endpoint ->
          request.nativeReviewWorkerName?.let { worker ->
            add("--agent")
            add(worker)
          }
          add("--mcp-config")
          add(endpoint.descriptor.mcpConfigPath.toString())
          add("--strict-mcp-config")
          add("--tools")
          add(governedReviewToolList(request.reviewFanOut))
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
        request.streamOutputForLiveness -> AgentRunIdlePolicy.OUTPUT_EXTENDED
        request.readOnlyPhase -> AgentRunIdlePolicy.HEARTBEAT_EXTENDED
        else -> AgentRunIdlePolicy.DB_PROGRESS_ONLY
      },
      outputDecoder = AgentRunOutputDecoder.CLAUDE_STREAM_JSON.takeIf { streaming },
      environmentPassthroughKeys =
      if (request.reviewEvidenceBroker != null) CLAUDE_PROVIDER_PASSTHROUGH_KEYS else emptySet(),
    )
  }
}

class CodexAgentRunCommandBuilder(
  override val governedReviewLaunchCapability: GovernedReviewLaunchCapability = GovernedReviewLaunchCapability(
    governedOnlyTooling = true,
    mcpIsolation = true,
    configFormat = McpRegistrationOperations.configFormatFor(InstallAgent.CODEX),
  ),
) : AgentRunCommandBuilder {
  override val agent: InstallAgent = InstallAgent.CODEX
  override val outputDecoder: AgentRunOutputDecoder = AgentRunOutputDecoder.CODEX_JSONL
  override val reviewIsolation: ReviewLaunchIsolationStrategy =
    ReviewLaunchIsolationStrategy.CODEX_NATIVE_FORK_TURNS_NONE

  override fun build(request: SkillRunRequest): AgentRunCommand {
    requireProcessLaunch(request, reviewIsolation)
    requireGovernedReviewLaunch(request, agent, governedReviewLaunchCapability)
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
          request.reviewEvidenceEndpoint?.let { endpoint ->
            GovernedReviewMcpConfigWriter.codexConfigOverrides(
              mcpConfigPath = endpoint.descriptor.mcpConfigPath,
              socketPath = endpoint.descriptor.socketPath,
              token = endpoint.descriptor.token,
              lane = endpoint.descriptor.lane,
            ).forEach { override ->
              add("--config")
              add(override)
            }
          }
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
