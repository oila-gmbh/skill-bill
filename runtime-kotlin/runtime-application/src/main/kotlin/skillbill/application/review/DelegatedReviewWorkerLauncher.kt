package skillbill.application.review

import me.tatarka.inject.annotations.Inject
import skillbill.application.review.model.DelegatedReviewWorkerOutcome
import skillbill.application.review.model.DelegatedReviewWorkerRequest
import skillbill.contracts.JsonSupport
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.agentrun.model.UnsupportedAgentRunLaunch
import skillbill.ports.review.BrokerBackedNativeReviewOperationProtocol
import skillbill.ports.review.NativeReviewWorkerLauncher
import skillbill.ports.review.model.NativeReviewWorkerRequest
import skillbill.ports.review.model.ReviewEvidenceBatchRequest
import skillbill.ports.review.model.ReviewEvidenceRequest
import skillbill.ports.review.model.ReviewLaneAccounting
import skillbill.review.context.ReviewContextEnvelopeValidator
import skillbill.review.context.model.ProviderTokenUsage
import skillbill.review.context.model.ReviewAssignment
import skillbill.review.context.model.ReviewBudgetEvaluator
import skillbill.review.context.model.ReviewLaneIdentity
import skillbill.review.context.model.TokenOwnership

/** Starts only broker-prepared launches and preserves typed budget outcomes through completion. */
@Inject
class DelegatedReviewWorkerLauncher(
  private val launcher: NativeReviewWorkerLauncher,
  private val envelopeValidator: ReviewContextEnvelopeValidator,
) {
  @Suppress("LongMethod")
  fun launch(request: DelegatedReviewWorkerRequest): DelegatedReviewWorkerOutcome {
    val prepared = request.prepared.launch
    val operations = BrokerBackedNativeReviewOperationProtocol(prepared.evidenceBroker)
    val evidenceRequests = (
      evidenceRequests(prepared.launch.assignment) + prepared.preauthorizedEvidenceRequests
      ).distinctBy { evidenceRequest -> evidenceRequest.path }
    val evidence = if (evidenceRequests.isEmpty()) {
      null
    } else {
      operations.read(
        ReviewEvidenceBatchRequest(prepared.launch.assignment.lane, evidenceRequests),
      )
    }
    evidence?.terminalOutcome?.let { outcome ->
      return DelegatedReviewWorkerOutcome(
        budgetOutcome = outcome,
        accounting = completeAccounting(request, prepared.evidenceBroker.accounting(), prepared.prompt, outcome.type),
      )
    }
    val forbidden = evidence?.results?.firstNotNullOfOrNull { result -> result.forbidden }
    if (forbidden != null) {
      return DelegatedReviewWorkerOutcome(
        forbiddenOperation = forbidden,
        accounting = completeAccounting(
          request,
          prepared.evidenceBroker.accounting(),
          prepared.prompt,
          "forbidden_operation",
        ),
      )
    }
    val brokeredEvidence = evidenceRequests.zip(evidence?.results.orEmpty()).mapNotNull { (request, result) ->
      result.content?.let { content -> request.path to content }
    }
    val finalEnvelope = prepared.launch.toLaunchEnvelope(brokeredEvidence).asWireMap()
    envelopeValidator.validate(finalEnvelope, "delegated review final launch '${prepared.launch.assignment.lane}'")
    val boundedPrompt = JsonSupport.mapToJsonString(finalEnvelope)
    finalLaunchOutcome(prepared.launch.assignment, prepared.launch.budget.maxLaneLaunchBytes, boundedPrompt)
      ?.let { outcome ->
        return DelegatedReviewWorkerOutcome(
          budgetOutcome = outcome,
          accounting = completeAccounting(request, prepared.evidenceBroker.accounting(), boundedPrompt, outcome.type),
        )
      }
    val outcome = launcher.launch(
      NativeReviewWorkerRequest(
        agentId = request.agentId,
        logicalWorkerName = request.logicalWorkerName,
        issueKey = prepared.launch.assignment.reviewId,
        repoRoot = request.repoRoot,
        timeout = request.timeout,
        progressIdleTimeout = request.progressIdleTimeout,
        // MCP startup is counted only when the provider launcher supplies an explicit observation;
        // a broker model-turn callback is not evidence that MCP startup occurred.
        mcpStartupProbe = request.mcpStartupProbe,
        prompt = boundedPrompt,
        modelOverride = request.modelOverride,
        isolation = prepared.isolation,
        broker = prepared.evidenceBroker,
        operations = operations,
      ),
    )
    return when (outcome) {
      is UnsupportedAgentRunLaunch -> DelegatedReviewWorkerOutcome(
        unsupportedReason = outcome.reason,
        accounting = completeAccounting(
          request,
          prepared.evidenceBroker.accounting(),
          boundedPrompt,
          "unsupported_provider",
        ),
      )
      is AgentRunLaunchFacts -> {
        val resultOutcome = if (!prepared.evidenceBroker.hasObservedLaneResult()) {
          prepared.evidenceBroker.validateLaneResult(outcome.stdout)
        } else {
          prepared.evidenceBroker.accounting().terminalOutcome
        }
        val providerOutcome = providerUsage(outcome)?.let {
          prepared.evidenceBroker.evaluateProviderUsage(it, enforceable = outcome.providerUsageEnforceable)
        }
        DelegatedReviewWorkerOutcome(
          facts = outcome,
          budgetOutcome = resultOutcome ?: providerOutcome,
          accounting = completeAccounting(
            request,
            prepared.evidenceBroker.accounting(),
            boundedPrompt,
            resultOutcome?.type ?: providerOutcome?.type ?: terminalStatus(outcome),
            providerUsage(outcome),
          ),
        )
      }
    }
  }

  private fun completeAccounting(
    request: DelegatedReviewWorkerRequest,
    accounting: ReviewLaneAccounting,
    finalPrompt: String,
    terminalStatus: String,
    usage: ProviderTokenUsage? = null,
  ) = accounting.copy(
    reviewId = request.prepared.launch.launch.assignment.reviewId,
    packetDigest = request.prepared.launch.launch.assignment.packetDigest,
    assignmentDigest = request.prepared.launch.launch.assignment.digest,
    launchBytes = finalPrompt.toByteArray(Charsets.UTF_8).size.toLong(),
    providerUsage = usage,
    terminalStatus = terminalStatus,
  )

  private fun terminalStatus(facts: AgentRunLaunchFacts): String = when {
    facts.timedOut -> "timeout"
    facts.interrupted -> "interrupted"
    facts.spawnFailed -> "spawn_failure"
    facts.exitStatus != 0 -> "process_failure"
    else -> "completed"
  }

  private fun finalLaunchOutcome(assignment: ReviewAssignment, limit: Long, prompt: String) =
    ReviewBudgetEvaluator.exceededOrNull(
      ReviewLaneIdentity.of(assignment),
      "lane_launch_bytes",
      limit,
      prompt.toByteArray(Charsets.UTF_8).size.toLong(),
    )

  private fun evidenceRequests(assignment: ReviewAssignment): List<ReviewEvidenceRequest> =
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
    }.distinctBy { request -> request.path }

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
