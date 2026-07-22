package skillbill.application.review

import skillbill.boundary.OpenBoundaryMap
import skillbill.contracts.review.REVIEW_CONTEXT_CONTRACT_VERSION
import skillbill.review.context.model.ProviderTokenUsage
import skillbill.review.context.model.ReviewAccountingCounters
import skillbill.review.context.model.ReviewAccountingNode
import skillbill.review.context.model.ReviewAccountingSummary

/** The sole durable/wire projection for review accounting. Content-bearing inputs are intentionally absent. */
@OpenBoundaryMap("Schema-bounded review-accounting wire projection")
fun ReviewAccountingSummary.toBoundedPayload(): Map<String, Any?> = linkedMapOf(
  "contract_version" to REVIEW_CONTEXT_CONTRACT_VERSION,
  "kind" to "accounting_summary",
  "review_id" to reviewId,
  "packet_digest" to packetDigest,
  "parent" to parent.toPayload(),
  "lanes" to lanes.map(ReviewAccountingNode::toPayload),
  "aggregate_counters" to aggregateCounters.toPayload(),
  "aggregate_direct_usage" to aggregateDirectUsage.toPayload(),
  "aggregate_inclusive_usage" to aggregateInclusiveUsage.toPayload(),
  "budget_regression" to budgetRegression,
)

private fun ReviewAccountingNode.toPayload(): Map<String, Any?> = linkedMapOf(
  "lane" to lane,
  "assignment_digest" to assignmentDigest,
  "launch_bytes" to counters.launchBytes,
  "evidence_bytes" to counters.evidenceBytes,
  "result_bytes" to counters.resultBytes,
  "expansions" to counters.expansions.toLong(),
  "tool_calls" to counters.toolCalls.toLong(),
  "model_turns" to counters.modelTurns.toLong(),
  "inclusive_counters" to inclusiveCounters.toPayload(),
  "provider_usage" to providerUsage.toPayload() + ("ownership" to providerUsage.ownership.name.lowercase()),
  "direct_usage" to directUsage.toPayload(),
  "inclusive_usage" to inclusiveUsage.toPayload(),
  "terminal_outcome" to terminalOutcome,
)

private fun ReviewAccountingCounters.toPayload(): Map<String, Long> = linkedMapOf(
  "launch_bytes" to launchBytes,
  "evidence_bytes" to evidenceBytes,
  "result_bytes" to resultBytes,
  "expansions" to expansions.toLong(),
  "tool_calls" to toolCalls.toLong(),
  "model_turns" to modelTurns.toLong(),
)

private fun ProviderTokenUsage.toPayload(): Map<String, Long> = linkedMapOf<String, Long>().apply {
  inputTokens?.let { put("input_tokens", it) }
  cachedInputTokens?.let { put("cached_input_tokens", it) }
  outputTokens?.let { put("output_tokens", it) }
  reasoningTokens?.let { put("reasoning_tokens", it) }
  totalTokens?.let { put("total_tokens", it) }
  freshTokenApproximation?.let { put("fresh_token_approximation", it.coerceAtLeast(0)) }
}
