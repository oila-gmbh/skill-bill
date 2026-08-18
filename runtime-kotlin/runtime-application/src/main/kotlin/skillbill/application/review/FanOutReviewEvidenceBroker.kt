package skillbill.application.review

import skillbill.ports.review.ReviewEvidenceBroker
import skillbill.ports.review.model.ReviewEvidenceBatchRequest
import skillbill.ports.review.model.ReviewEvidenceBatchResult
import skillbill.ports.review.model.ReviewExpansionAuthorizationRequest
import skillbill.ports.review.model.ReviewLaneAccounting
import skillbill.ports.review.model.ReviewToolCall
import skillbill.ports.review.model.ReviewToolCallResult
import skillbill.review.context.model.ProviderTokenUsage
import skillbill.review.context.model.ReviewBudgetOutcome
import skillbill.review.context.model.ReviewExpansionRecord

internal class FanOutReviewEvidenceBroker(
  private val brokers: Map<String, ReviewEvidenceBroker>,
) : ReviewEvidenceBroker {
  init {
    require(brokers.isNotEmpty()) { "A fan-out evidence broker must bind at least one assignment." }
  }

  override fun authorizeExpansion(request: ReviewExpansionAuthorizationRequest): ReviewExpansionRecord =
    brokerForLane(brokers, request.lane).authorizeExpansion(request)

  override fun readBatch(request: ReviewEvidenceBatchRequest): ReviewEvidenceBatchResult =
    brokerForLane(brokers, request.lane).readBatch(request)

  override fun recordToolCall(call: ReviewToolCall): ReviewToolCallResult =
    brokerForLane(brokers, call.lane).recordToolCall(call)

  override fun recordModelTurn(): ReviewBudgetOutcome? =
    brokers.values.mapNotNull { it.recordModelTurn() }.firstOrNull()

  override fun validateLaneResult(result: String): ReviewBudgetOutcome? =
    brokers.values.mapNotNull { it.validateLaneResult(result) }.firstOrNull()

  override fun observeLaneResultChunk(chunk: String): ReviewBudgetOutcome? =
    brokers.values.mapNotNull { it.observeLaneResultChunk(chunk) }.firstOrNull()

  override fun hasObservedLaneResult(): Boolean = brokers.values.any { it.hasObservedLaneResult() }

  override fun evaluateProviderUsage(usage: ProviderTokenUsage, enforceable: Boolean): ReviewBudgetOutcome? =
    brokers.values.mapNotNull { it.evaluateProviderUsage(usage, enforceable) }.firstOrNull()

  override fun accounting(): ReviewLaneAccounting {
    val parts = brokers.values.map { it.accounting() }
    val first = parts.first()
    return first.copy(
      authorizedReadCount = parts.sumOf { it.authorizedReadCount },
      evidenceBytes = parts.sumOf { it.evidenceBytes },
      expansions = parts.flatMap { it.expansions },
      toolCalls = parts.sumOf { it.toolCalls },
    )
  }

  override fun terminalOutcome(): ReviewBudgetOutcome? = null
}

private fun brokerForLane(brokers: Map<String, ReviewEvidenceBroker>, lane: String): ReviewEvidenceBroker =
  requireNotNull(brokers[lane]) { "Evidence lane '$lane' is not bound to this parent session." }
