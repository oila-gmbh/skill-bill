package skillbill.infrastructure.fs.launcher.agentrun

import skillbill.infrastructure.fs.launcher.mcp.McpRegistrationOperations
import skillbill.infrastructure.fs.launcher.process.AgentRunIdlePolicy
import skillbill.install.model.InstallAgent
import skillbill.ports.agentrun.model.SkillRunRequest
import skillbill.ports.review.model.ReviewLaunchIsolationStrategy
import java.nio.file.Path

class CursorAgentRunCommandBuilder(
  override val governedReviewLaunchCapability: GovernedReviewLaunchCapability = GovernedReviewLaunchCapability(
    governedOnlyTooling = true,
    mcpIsolation = true,
    configFormat = McpRegistrationOperations.configFormatFor(InstallAgent.CURSOR),
  ),
  private val databasePath: Path? = null,
) : AgentRunCommandBuilder {
  override val agent: InstallAgent = InstallAgent.CURSOR
  override val outputDecoder: AgentRunOutputDecoder = AgentRunOutputDecoder.CURSOR_STREAM_JSON
  override val reviewIsolation: ReviewLaunchIsolationStrategy = ReviewLaunchIsolationStrategy.FRESH_PROCESS

  override fun build(request: SkillRunRequest): AgentRunCommand {
    requireProcessLaunch(request, reviewIsolation)
    requireGovernedReviewLaunch(request, agent, governedReviewLaunchCapability)
    val streamPartialOutput = request.streamProviderOutput
    val streaming = streamPartialOutput || request.streamOutputForLiveness || request.auditRepairExecutionId != null
    val isReviewLaunch = request.reviewEvidenceBroker != null
    val reviewLaunchDirectory = request.reviewEvidenceEndpoint?.descriptor?.mcpConfigPath?.parent

    return goalContinuationCommand(request, agent, databasePath) ?: AgentRunCommand(
      command = buildCursorCommand(
        request,
        isReviewLaunch,
        reviewLaunchDirectory,
        streamPartialOutput,
        streaming,
      ),
      workingDirectory = if (isReviewLaunch) {
        reviewLaunchDirectory ?: request.repoRoot
      } else {
        request.repoRoot
      },
      timeout = request.timeout,
      stdinText = launchPrompt(request),
      environment = GoalContinuationEnvironment + goalContinuationEnvironment(request),
      inheritEnvironment = !isReviewLaunch,
      conversationIsolation = request.conversationIsolation,
      idlePolicy = when {
        streamPartialOutput && request.streamOutputForLiveness -> AgentRunIdlePolicy.OUTPUT_EXTENDED
        else -> unstreamedLivenessPolicy(request)
      },
      environmentPassthroughKeys = if (isReviewLaunch) CURSOR_PROVIDER_PASSTHROUGH_KEYS else emptySet(),
    )
  }

  internal fun buildCursorCommand(
    request: SkillRunRequest,
    isReviewLaunch: Boolean,
    reviewLaunchDirectory: Path?,
    streamPartialOutput: Boolean,
    streaming: Boolean,
  ): List<String> = buildList {
    add("agent")
    if (request.auditRepairResume) {
      add("--resume")
      add(requireNotNull(request.auditRepairSessionId))
    }
    add("--print")

    if (isReviewLaunch) {
      add("--force")
      add("--trust")
      add("--approve-mcps")
      add("--workspace")
      add((reviewLaunchDirectory ?: request.repoRoot).toString())
    } else {
      add("--force")
      add("--trust")
      add("--approve-mcps")
      add("--workspace")
      add(request.repoRoot.toString())
    }

    add("--output-format")
    add(if (streaming) "stream-json" else "json")
    if (streamPartialOutput) add("--stream-partial-output")

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

  internal fun mergeModelEffort(model: String, effort: String): String {
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

internal fun codexLivenessPolicy(request: SkillRunRequest): AgentRunIdlePolicy = if (request.streamOutputForLiveness) {
  AgentRunIdlePolicy.HEARTBEAT_EXTENDED
} else {
  AgentRunIdlePolicy.DB_PROGRESS_ONLY
}

internal fun unstreamedLivenessPolicy(request: SkillRunRequest): AgentRunIdlePolicy =
  if (request.streamOutputForLiveness || request.readOnlyPhase) {
    AgentRunIdlePolicy.HEARTBEAT_EXTENDED
  } else {
    AgentRunIdlePolicy.DB_PROGRESS_ONLY
  }
