package skillbill.infrastructure.fs

import me.tatarka.inject.annotations.Inject
import skillbill.agent.model.AgentId
import skillbill.install.model.InstallAgent
import skillbill.launcher.agentrun.ClaudeAgentRunCommandBuilder
import skillbill.launcher.agentrun.CodexAgentRunCommandBuilder
import skillbill.launcher.agentrun.CursorAgentRunCommandBuilder
import skillbill.launcher.agentrun.JunieAgentRunCommandBuilder
import skillbill.ports.review.ReviewLaunchIsolationResolver
import skillbill.ports.review.model.ReviewLaunchIsolationStrategy

@Inject
class AgentRunReviewIsolationResolver : ReviewLaunchIsolationResolver {
  private val strategies: Map<InstallAgent, ReviewLaunchIsolationStrategy> = listOf(
    ClaudeAgentRunCommandBuilder(),
    CodexAgentRunCommandBuilder(),
    CursorAgentRunCommandBuilder(),
    JunieAgentRunCommandBuilder(),
  ).associate { builder -> builder.agent to builder.reviewIsolation }

  override fun isolationFor(agentId: AgentId): ReviewLaunchIsolationStrategy {
    val agent = InstallAgent.supportedIds.firstOrNull { it == agentId.value }
      ?.let { id -> strategies.keys.firstOrNull { it.id == id } }
    return agent?.let(strategies::get) ?: ReviewLaunchIsolationStrategy.UNSUPPORTED
  }
}
