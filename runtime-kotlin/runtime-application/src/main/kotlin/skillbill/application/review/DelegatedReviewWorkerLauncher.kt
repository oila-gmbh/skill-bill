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
import skillbill.review.context.model.ReviewAssignment
import skillbill.review.context.model.TokenOwnership

/** Starts only broker-prepared launches and preserves typed budget outcomes through completion. */
@Inject
class DelegatedReviewWorkerLauncher(
  private val launcher: NativeReviewWorkerLauncher,
) {
  fun launch(request: DelegatedReviewWorkerRequest): DelegatedReviewWorkerOutcome {
    val prepared = request.prepared.launch
    val operations = BrokerBackedNativeReviewOperationProtocol(prepared.evidenceBroker)
    val evidenceRequests = evidenceRequests(prepared.launch.assignment)
    val evidence = operations.read(
      ReviewEvidenceBatchRequest(
        prepared.launch.assignment.lane,
        evidenceRequests,
      ),
    )
    evidence.terminalOutcome?.let { outcome ->
      return DelegatedReviewWorkerOutcome(
        budgetOutcome = outcome,
        accounting = prepared.evidenceBroker.accounting(),
      )
    }
    val forbidden = evidence.results.firstNotNullOfOrNull { result -> result.forbidden }
    if (forbidden != null) {
      return DelegatedReviewWorkerOutcome(
        forbiddenOperation = forbidden,
        accounting = prepared.evidenceBroker.accounting(),
      )
    }
    val boundedPrompt = boundedPrompt(prepared.prompt, evidenceRequests, evidence.results)
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
          prepared.evidenceBroker.evaluateProviderUsage(it, enforceable = outcome.providerUsageEnforceable)
        }
        DelegatedReviewWorkerOutcome(
          facts = outcome,
          budgetOutcome = resultOutcome ?: providerOutcome,
          accounting = prepared.evidenceBroker.accounting(),
        )
      }
    }
  }

  private fun evidenceRequests(assignment: ReviewAssignment): List<ReviewEvidenceRequest> = (
    assignment.assignedPaths.map { path -> ReviewEvidenceRequest(assignment.lane, path) } +
      assignment.expansions.map { expansion ->
        require(expansion.authorized) {
          "Expansion '${expansion.expansionId}' is not authorized for delegated evidence admission."
        }
        require(expansion.assignmentDigest == assignment.digest) {
          "Expansion '${expansion.expansionId}' does not belong to assignment '${assignment.digest}'."
        }
        ReviewEvidenceRequest(
          assignment.lane,
          expansion.requestedPath,
          expansion.reachabilityReason,
          expansion,
        )
      }
    ).distinctBy { request -> request.path }

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

  private fun boundedPrompt(
    prompt: String,
    requests: List<ReviewEvidenceRequest>,
    evidence: List<ReviewEvidenceResult>,
  ): String = buildString {
    append(prompt)
    appendLine()
    appendLine("brokered_evidence:")
    requests.zip(evidence).forEach { (request, result) ->
      appendLine("--- ${request.path}")
      appendLine(requireNotNull(result.content))
    }
  }
}
