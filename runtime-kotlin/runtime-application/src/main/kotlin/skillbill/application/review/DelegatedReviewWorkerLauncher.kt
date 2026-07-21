package skillbill.application.review

import me.tatarka.inject.annotations.Inject
import skillbill.application.review.model.DelegatedReviewWorkerOutcome
import skillbill.application.review.model.DelegatedReviewWorkerRequest
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.agentrun.model.UnsupportedAgentRunLaunch
import skillbill.ports.review.NativeReviewWorkerLauncher
import skillbill.ports.review.NativeReviewWorkerRequest
import skillbill.ports.review.model.ReviewEvidenceBatchRequest
import skillbill.ports.review.model.ReviewEvidenceRequest
import skillbill.review.context.model.ProviderTokenUsage
import skillbill.review.context.model.TokenOwnership

/** Starts only broker-prepared launches and preserves typed budget outcomes through completion. */
@Inject
class DelegatedReviewWorkerLauncher(
  private val launcher: NativeReviewWorkerLauncher,
) {
  fun launch(request: DelegatedReviewWorkerRequest): DelegatedReviewWorkerOutcome {
    val prepared = request.prepared.launch
    val evidence = prepared.evidenceBroker.readBatch(
      ReviewEvidenceBatchRequest(
        prepared.launch.assignment.lane,
        prepared.launch.assignment.assignedPaths.map { path ->
          ReviewEvidenceRequest(prepared.launch.assignment.lane, path)
        },
      ),
    )
    evidence.terminalOutcome?.let { outcome ->
      return DelegatedReviewWorkerOutcome(
        budgetOutcome = outcome,
        accounting = prepared.evidenceBroker.accounting(),
      )
    }
    val boundedPrompt = buildString {
      append(prepared.prompt)
      appendLine()
      appendLine("brokered_evidence:")
      prepared.launch.assignment.assignedPaths.zip(evidence.results).forEach { (path, result) ->
        appendLine("--- $path")
        appendLine(requireNotNull(result.content))
      }
    }
    val outcome = launcher.launch(
      NativeReviewWorkerRequest(
        agentId = request.agentId,
        issueKey = prepared.launch.assignment.reviewId,
        repoRoot = request.repoRoot,
        timeout = request.timeout,
        prompt = boundedPrompt,
        modelOverride = request.modelOverride,
        isolation = prepared.isolation,
        broker = prepared.evidenceBroker,
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
