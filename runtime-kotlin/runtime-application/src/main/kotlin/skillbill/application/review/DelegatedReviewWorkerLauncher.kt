package skillbill.application.review

import me.tatarka.inject.annotations.Inject
import skillbill.application.review.model.DelegatedReviewWorkerOutcome
import skillbill.application.review.model.DelegatedReviewWorkerRequest
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.agentrun.model.UnsupportedAgentRunLaunch
import skillbill.ports.review.BrokerBackedNativeReviewOperationProtocol
import skillbill.ports.review.NativeReviewWorkerLauncher
import skillbill.ports.review.model.NativeReviewWorkerRequest
import skillbill.ports.review.model.ReviewEvidenceBatchRequest
import skillbill.ports.review.model.ReviewEvidenceRequest
import skillbill.ports.review.model.ReviewEvidenceResult
import skillbill.review.context.model.ProviderTokenUsage
import skillbill.review.context.model.TokenOwnership

/** Starts only broker-prepared launches and preserves typed budget outcomes through completion. */
@Inject
class DelegatedReviewWorkerLauncher(
  private val launcher: NativeReviewWorkerLauncher,
) {
  fun launch(request: DelegatedReviewWorkerRequest): DelegatedReviewWorkerOutcome {
    val prepared = request.prepared.launch
    val operations = BrokerBackedNativeReviewOperationProtocol(prepared.evidenceBroker)
    val evidencePaths = (
      prepared.launch.assignment.assignedPaths +
        prepared.launch.assignment.expansions.map { expansion -> expansion.requestedPath }
      ).distinct()
    val evidence = operations.read(
      ReviewEvidenceBatchRequest(
        prepared.launch.assignment.lane,
        evidencePaths.map { path ->
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
    val boundedPrompt = boundedPrompt(prepared.prompt, evidencePaths, evidence.results)
    operations.modelTurn()?.let { outcome ->
      return DelegatedReviewWorkerOutcome(
        budgetOutcome = outcome,
        accounting = prepared.evidenceBroker.accounting(),
      )
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
        operations = operations,
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

  private fun boundedPrompt(prompt: String, paths: List<String>, evidence: List<ReviewEvidenceResult>): String =
    buildString {
      append(prompt)
      appendLine()
      appendLine("brokered_evidence:")
      paths.zip(evidence).forEach { (path, result) ->
        appendLine("--- $path")
        appendLine(requireNotNull(result.content))
      }
    }
}
