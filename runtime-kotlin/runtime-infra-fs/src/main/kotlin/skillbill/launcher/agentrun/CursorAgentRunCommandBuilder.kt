package skillbill.launcher.agentrun

import skillbill.install.model.InstallAgent
import skillbill.launcher.mcp.McpRegistrationOperations
import skillbill.launcher.process.AgentRunIdlePolicy
import skillbill.ports.agentrun.model.SkillRunRequest
import skillbill.ports.review.model.ReviewLaunchIsolationStrategy
import java.nio.file.Path

class CursorAgentRunCommandBuilder(
  override val governedReviewLaunchCapability: GovernedReviewLaunchCapability = GovernedReviewLaunchCapability(
    governedOnlyTooling = true,
    mcpIsolation = true,
    configFormat = McpRegistrationOperations.configFormatFor(InstallAgent.CURSOR),
  ),
) : AgentRunCommandBuilder {
  override val agent: InstallAgent = InstallAgent.CURSOR
  override val outputDecoder: AgentRunOutputDecoder = AgentRunOutputDecoder.CURSOR_STREAM_JSON
  override val reviewIsolation: ReviewLaunchIsolationStrategy = ReviewLaunchIsolationStrategy.FRESH_PROCESS

  override fun build(request: SkillRunRequest): AgentRunCommand {
    requireProcessLaunch(request, reviewIsolation)
    requireGovernedReviewLaunch(request, agent, governedReviewLaunchCapability)
    // --stream-partial-output turns one answer into a run of incremental assistant deltas. That is
    // what a caller asking for provider output wants, and precisely what a caller asking only for a
    // liveness signal does not: the deltas are indistinguishable from finished turns at harvest
    // time. Liveness falls back to process heartbeat instead, as Codex already does.
    val streamPartialOutput = request.streamProviderOutput
    // stream-json carries the whole session — every turn, tool call and tool result — and its only
    // harvestable event is the terminal one. A launch nobody is streaming pays that transport cost
    // to have the answer arrive last, behind a capped drain that keeps the head. Buffer instead,
    // exactly as Claude does, so an unstreamed launch harvests one small object.
    val streaming = streamPartialOutput || request.streamOutputForLiveness
    val isReviewLaunch = request.reviewEvidenceBroker != null
    val reviewLaunchDirectory = request.reviewEvidenceEndpoint?.descriptor?.mcpConfigPath?.parent

    return goalContinuationCommand(request, agent) ?: AgentRunCommand(
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
    add("--print")

    if (isReviewLaunch) {
      // --approve-mcps only admits the server; every tools/call still needs approval, and a
      // --print launch auto-rejects what it cannot prompt for. Without --force the governed lane
      // loads the evidence server, lists its tools, and is refused every read it attempts.
      //
      // --force also unlocks this agent's own file and shell tools, and the CLI honours no
      // workspace-scoped permission file that could deny them back, so unlike the other agents
      // this lane cannot be confined to broker-supplied evidence. What keeps it honest is the
      // evidence accounting: a lane that answers without reading fails as unread.
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

/**
 * Fallback for a builder that cannot honor [SkillRunRequest.streamOutputForLiveness]. Such a launch
 * can never satisfy a durable-progress watchdog, so process liveness stands in and its wall-clock
 * budget remains the real bound. A read-only phase also qualifies for heartbeat extension because it
 * produces no durable workflow rows by construction.
 */
internal fun unstreamedLivenessPolicy(request: SkillRunRequest): AgentRunIdlePolicy =
  if (request.streamOutputForLiveness || request.readOnlyPhase) {
    AgentRunIdlePolicy.HEARTBEAT_EXTENDED
  } else {
    AgentRunIdlePolicy.DB_PROGRESS_ONLY
  }
