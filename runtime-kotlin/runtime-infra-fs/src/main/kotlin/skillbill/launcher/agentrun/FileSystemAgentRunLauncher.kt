package skillbill.launcher.agentrun

import com.fasterxml.jackson.databind.ObjectMapper
import me.tatarka.inject.annotations.Inject
import skillbill.install.model.InstallAgent
import skillbill.install.model.RUNTIME_REFUSED_AGENT_MESSAGE
import skillbill.install.model.isRuntimeRefusedAgent
import skillbill.launcher.process.JvmAgentRunProcessRunner
import skillbill.ports.agentrun.AgentRunLauncher
import skillbill.ports.agentrun.model.AgentRunLaunchOutcome
import skillbill.ports.agentrun.model.AgentRunLaunchRequest
import skillbill.ports.agentrun.model.AgentRunOutputStream
import skillbill.ports.agentrun.model.ConversationIsolation
import skillbill.ports.agentrun.model.SkillRunRequest
import skillbill.ports.agentrun.model.UnsupportedAgentRunLaunch
import skillbill.ports.review.NativeReviewWorkerRequest
import skillbill.ports.review.model.ReviewToolCall
import skillbill.review.context.model.ProviderTokenUsage
import skillbill.review.context.model.ReviewOperationKind
import skillbill.review.context.model.TokenOwnership
import java.nio.file.Files
import java.util.Comparator

@Inject
class FileSystemAgentRunLauncher(
  processRunner: JvmAgentRunProcessRunner,
) : AgentRunLauncher {
  private val adapters: Map<InstallAgent, AgentRunAdapter> = headlessAgentRunAdapters(processRunner)

  override fun launch(request: AgentRunLaunchRequest): AgentRunLaunchOutcome {
    val agent = InstallAgent.fromNormalizedId(request.agentId)
    val adapter = adapters[agent]
      ?: return UnsupportedAgentRunLaunch(
        agent = agent,
        // A runtime-refused agent (opencode, zcode) reaching this deep path — e.g. a future caller that
        // resolves an agent past the CLI preflight — gets the same actionable prose guidance as the
        // preflight, not a generic "no launch path" line.
        reason = if (isRuntimeRefusedAgent(agent.id)) {
          RUNTIME_REFUSED_AGENT_MESSAGE
        } else {
          "Agent '${agent.id}' does not have a supported headless bill-feature-task launch path."
        },
      )
    return adapter.launch(request.skillRunRequest)
  }

  override fun launchNativeReview(request: NativeReviewWorkerRequest): AgentRunLaunchOutcome {
    val agent = InstallAgent.fromNormalizedId(request.agentId)
    val adapter = adapters[agent] ?: return UnsupportedAgentRunLaunch(
      agent,
      "Agent '${agent.id}' does not expose a provider-native governed review start.",
    )
    require(request.isolation == adapterReviewIsolation(agent)) {
      "Native review isolation does not match the provider strategy."
    }
    val isolatedRoot = Files.createTempDirectory("skill-bill-native-review-")
    val observer = NativeReviewEventObserver(request)
    return try {
      adapter.launch(
        SkillRunRequest(
          issueKey = request.issueKey,
          repoRoot = isolatedRoot,
          timeout = request.timeout,
          promptOverride = request.prompt,
          modelOverride = request.modelOverride,
          conversationIsolation = ConversationIsolation.NONE,
          reviewEvidenceBroker = request.broker,
          outputSink = { stream, text ->
            if (stream == AgentRunOutputStream.STDOUT) {
              request.broker.observeLaneResultChunk(text)
              observer.observe(text)
            }
          },
        ),
      )
    } finally {
      Files.walk(isolatedRoot).use { paths ->
        paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
      }
    }
  }

  private fun adapterReviewIsolation(agent: InstallAgent) = when (agent) {
    InstallAgent.CODEX -> skillbill.ports.agentrun.model.ReviewLaunchIsolationStrategy.CODEX_NATIVE_FORK_TURNS_NONE
    InstallAgent.CLAUDE, InstallAgent.JUNIE ->
      skillbill.ports.agentrun.model.ReviewLaunchIsolationStrategy.FRESH_PROCESS
    else -> skillbill.ports.agentrun.model.ReviewLaunchIsolationStrategy.UNSUPPORTED
  }
}

private class NativeReviewEventObserver(private val request: NativeReviewWorkerRequest) {
  private val mapper = ObjectMapper()
  private val pending = StringBuilder()

  @Synchronized
  fun observe(chunk: String) {
    pending.append(chunk)
    while (true) {
      val newline = pending.indexOf("\n")
      if (newline < 0) return
      val line = pending.substring(0, newline).trim()
      pending.delete(0, newline + 1)
      if (line.isNotEmpty()) observeLine(line)
    }
  }

  private fun observeLine(line: String) {
    val event = runCatching { mapper.readTree(line) }.getOrNull() ?: return
    val type = event.path("type").asText()
    if (type == "turn.completed" || type == "message.completed") request.broker.recordModelTurn()
    val item = event.path("item")
    val itemType = item.path("type").asText()
    if (itemType == "command_execution" || itemType == "mcp_tool_call" || itemType == "web_search") {
      val kind = when (itemType) {
        "mcp_tool_call" -> ReviewOperationKind.MCP_TOOL
        "web_search" -> ReviewOperationKind.SEARCH
        else -> ReviewOperationKind.SHELL_COMMAND
      }
      val target = item.path("command").asText()
        .ifBlank { item.path("tool").asText() }
        .ifBlank { itemType }
      request.broker.recordToolCall(ReviewToolCall(request.broker.accounting().lane, kind, target))
    }
    val usage = event.path("usage")
    if (usage.isObject) {
      request.broker.evaluateProviderUsage(
        ProviderTokenUsage(
          usage.longOrNull("input_tokens"),
          usage.longOrNull("cached_input_tokens"),
          usage.longOrNull("output_tokens"),
          usage.longOrNull("reasoning_tokens"),
          usage.longOrNull("total_tokens"),
          TokenOwnership.DIRECT,
        ),
        enforceable = true,
      )
    }
  }

  private fun com.fasterxml.jackson.databind.JsonNode.longOrNull(name: String): Long? =
    path(name).takeIf { it.isIntegralNumber && it.canConvertToLong() }?.longValue()
}
