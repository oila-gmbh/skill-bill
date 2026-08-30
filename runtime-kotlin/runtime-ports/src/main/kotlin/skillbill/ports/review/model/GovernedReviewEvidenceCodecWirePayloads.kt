package skillbill.ports.review.model

import skillbill.review.context.model.ForbiddenReviewOperation
import skillbill.review.context.model.ReviewBudgetOutcome
import skillbill.review.context.model.ReviewExpansionRecord

internal object GovernedReviewEvidenceCodecWirePayloads {
  fun expansionPayload(record: ReviewExpansionRecord): Map<String, Any?> = linkedMapOf(
    "expansion_id" to record.expansionId,
    "requested_path" to record.requestedPath,
    "reachability_reason" to record.reachabilityReason,
    "authorized" to record.authorized,
    "sequence" to record.sequence,
  )

  fun resultPayload(result: ReviewEvidenceResult): Map<String, Any?> {
    val refusal = refusal(result)
    if (refusal != null) return refusal
    return linkedMapOf(
      "refused" to false,
      "content" to result.content,
      "bytes" to result.bytes,
      "cumulative_bytes" to result.cumulativeBytes,
      "expansion_count" to result.expansionCount,
    )
  }

  fun budgetPayload(outcome: ReviewBudgetOutcome): Map<String, Any?> = linkedMapOf(
    "refused" to true,
    "refusal_kind" to "budget_exceeded",
    "reason" to outcome.type,
    "budget_kind" to outcome.budgetKind,
    "configured_limit" to outcome.configuredLimit,
    "observed_value" to outcome.observedValue,
  )

  private fun refusal(result: ReviewEvidenceResult): Map<String, Any?>? {
    result.forbidden?.let { return forbiddenPayload(it) }
    result.budgetExceeded?.let { return budgetPayload(it) }
    return null
  }

  private fun forbiddenPayload(forbidden: ForbiddenReviewOperation): Map<String, Any?> = linkedMapOf(
    "refused" to true,
    "refusal_kind" to "forbidden",
    "reason" to forbidden.reason,
    "category" to forbidden.category,
    "target" to forbidden.target,
  )
}
