package skillbill.launcher.agentrun

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
import skillbill.ports.review.model.NativeReviewWorkerRequest
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
    val capabilities = adapter.nativeReviewCapabilities
    if (!capabilities.supportsGovernedLaunch) {
      return UnsupportedAgentRunLaunch(
        agent,
        "Agent '${agent.id}' cannot start a governed native review because its provider adapter " +
          "neither disables nor mediates every requested operation, or does not observe every " +
          "model turn through the review " +
          "budget broker before execution (provider usage exposure: " +
          "${capabilities.providerUsageExposure.name.lowercase()}).",
      )
    }
    val isolatedRoot = Files.createTempDirectory("skill-bill-native-review-")
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
          nativeReviewOperations = request.operations,
          outputSink = { stream, text ->
            if (stream == AgentRunOutputStream.STDOUT) {
              request.broker.observeLaneResultChunk(text)
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
