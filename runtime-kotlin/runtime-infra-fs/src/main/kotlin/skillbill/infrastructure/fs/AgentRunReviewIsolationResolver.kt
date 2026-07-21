package skillbill.infrastructure.fs

import me.tatarka.inject.annotations.Inject
import skillbill.install.model.InstallAgent
import skillbill.launcher.agentrun.ClaudeAgentRunCommandBuilder
import skillbill.launcher.agentrun.CodexAgentRunCommandBuilder
import skillbill.launcher.agentrun.JunieAgentRunCommandBuilder
import skillbill.ports.agentrun.model.ReviewLaunchIsolationStrategy
import skillbill.ports.review.ReviewLaunchIsolationResolver

@Inject
class AgentRunReviewIsolationResolver : ReviewLaunchIsolationResolver {
  private val strategies: Map<InstallAgent, ReviewLaunchIsolationStrategy> = listOf(
    ClaudeAgentRunCommandBuilder(),
    CodexAgentRunCommandBuilder(),
    JunieAgentRunCommandBuilder(),
  ).associate { builder -> builder.agent to builder.reviewIsolation }

  override fun isolationFor(agentId: String): ReviewLaunchIsolationStrategy {
    val agent = InstallAgent.supportedIds.firstOrNull { it == agentId }
      ?.let { id -> strategies.keys.firstOrNull { it.id == id } }
    return agent?.let(strategies::get) ?: ReviewLaunchIsolationStrategy.UNSUPPORTED
  }
}
