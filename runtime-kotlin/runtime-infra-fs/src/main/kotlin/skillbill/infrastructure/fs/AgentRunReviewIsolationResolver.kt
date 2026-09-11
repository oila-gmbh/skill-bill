package skillbill.infrastructure.fs

import me.tatarka.inject.annotations.Inject
import skillbill.infrastructure.fs.launcher.agentrun.ClaudeAgentRunCommandBuilder
import skillbill.infrastructure.fs.launcher.agentrun.CodexAgentRunCommandBuilder
import skillbill.infrastructure.fs.launcher.agentrun.CursorAgentRunCommandBuilder
import skillbill.infrastructure.fs.launcher.agentrun.JunieAgentRunCommandBuilder
import skillbill.install.model.InstallAgent
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

  override fun isolationFor(agentId: String): ReviewLaunchIsolationStrategy {
    val agent = InstallAgent.supportedIds.firstOrNull { it == agentId }
      ?.let { id -> strategies.keys.firstOrNull { it.id == id } }
    return agent?.let(strategies::get) ?: ReviewLaunchIsolationStrategy.UNSUPPORTED
  }
}
