package skillbill.application.review

import me.tatarka.inject.annotations.Inject
import skillbill.application.review.model.DelegatedReviewWorkerOutcome
import skillbill.application.review.model.DelegatedReviewWorkerRequest
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.agentrun.model.SkillRunRequest
import skillbill.ports.agentrun.model.UnsupportedAgentRunLaunch
import skillbill.ports.goalrunner.GoalRunnerSubtaskLauncher
import skillbill.ports.goalrunner.model.GoalRunnerSubtaskLaunchRequest
import skillbill.review.context.model.ProviderTokenUsage
import skillbill.review.context.model.TokenOwnership

/** Starts only broker-prepared launches and preserves typed budget outcomes through completion. */
@Inject
class DelegatedReviewWorkerLauncher(
  private val launcher: GoalRunnerSubtaskLauncher,
) {
  fun launch(request: DelegatedReviewWorkerRequest): DelegatedReviewWorkerOutcome {
    val prepared = request.prepared.launch
    val isolation = prepared.isolation.toConversationIsolation()
    val outcome = launcher.launch(
      GoalRunnerSubtaskLaunchRequest(
        invokedAgentId = request.agentId,
        configuredAgentOverrideId = null,
        skillRunRequest = SkillRunRequest(
          issueKey = prepared.launch.assignment.reviewId,
          repoRoot = request.repoRoot,
          timeout = request.timeout,
          promptOverride = prepared.prompt,
          modelOverride = request.modelOverride,
          conversationIsolation = isolation,
          reviewEvidenceBroker = prepared.evidenceBroker,
        ),
      ),
    )
    return when (outcome) {
      is UnsupportedAgentRunLaunch -> DelegatedReviewWorkerOutcome(unsupportedReason = outcome.reason)
      is AgentRunLaunchFacts -> {
        val resultOutcome = prepared.evidenceBroker.validateLaneResult(outcome.stdout)
        val providerOutcome = providerUsage(outcome)?.let {
          prepared.evidenceBroker.evaluateProviderUsage(it, enforceable = false)
        }
        DelegatedReviewWorkerOutcome(
          facts = outcome,
          budgetOutcome = resultOutcome ?: providerOutcome,
          accounting = prepared.evidenceBroker.accounting(),
        )
      }
    }
  }

  private fun providerUsage(facts: AgentRunLaunchFacts): ProviderTokenUsage? {
    if (listOf(
        facts.inputTokens,
        facts.cachedInputTokens,
        facts.outputTokens,
        facts.reasoningTokens,
        facts.totalTokens,
      ).all { it == null }
    ) {
      return null
    }
    return ProviderTokenUsage(
      facts.inputTokens,
      facts.cachedInputTokens,
      facts.outputTokens,
      facts.reasoningTokens,
      facts.totalTokens,
      if (facts.tokenOwnership.name == TokenOwnership.INCLUSIVE.name) {
        TokenOwnership.INCLUSIVE
      } else {
        TokenOwnership.DIRECT
      },
    )
  }
}

private fun skillbill.ports.agentrun.model.ReviewLaunchIsolationStrategy.toConversationIsolation() = when (this) {
  skillbill.ports.agentrun.model.ReviewLaunchIsolationStrategy.CODEX_NATIVE_FORK_TURNS_NONE,
  skillbill.ports.agentrun.model.ReviewLaunchIsolationStrategy.FRESH_PROCESS,
  -> skillbill.ports.agentrun.model.ConversationIsolation.NONE
  skillbill.ports.agentrun.model.ReviewLaunchIsolationStrategy.UNSUPPORTED ->
    error("A governed review worker cannot start without a fresh-context isolation strategy.")
}
