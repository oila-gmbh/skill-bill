package skillbill.ports.persistence.model

import skillbill.boundary.OpenBoundaryMap

data class ReviewAccountingRecord(
  val reviewId: String,
  val packetDigest: String,
  @OpenBoundaryMap("Schema-bounded review-accounting persistence payload")
  val boundedPayload: Map<String, Any?>,
) {
  init {
    require(reviewId.isNotBlank() && packetDigest.isNotBlank())
    requireBoundedAccountingPayload(boundedPayload)
  }
}

private fun requireBoundedAccountingPayload(payload: Map<String, Any?>) {
  val topKeys = setOf(
    "contract_version", "kind", "review_id", "packet_digest", "parent", "lanes",
    "aggregate_direct_usage", "aggregate_inclusive_usage", "budget_regression",
  )
  require(payload.keys == topKeys) { "Review accounting must match the bounded projection contract." }
  require(payload["contract_version"] == "0.5" && payload["kind"] == "accounting_summary")
  require(payload["review_id"] is String && payload["packet_digest"] is String)
  requireAccountingNode(payload["parent"])
  require((payload["lanes"] as? List<*>)?.all { runCatching { requireAccountingNode(it) }.isSuccess } == true)
  requireUsage(payload["aggregate_direct_usage"], ownershipRequired = false)
  requireUsage(payload["aggregate_inclusive_usage"], ownershipRequired = false)
  require(payload["budget_regression"] is Boolean)
}

private fun requireAccountingNode(value: Any?) {
  val node = value as? Map<*, *> ?: error("Review accounting node must be an object.")
  val keys = setOf(
    "lane", "assignment_digest", "launch_bytes", "evidence_bytes", "result_bytes", "expansions",
    "tool_calls", "model_turns", "provider_usage", "direct_usage", "inclusive_usage", "terminal_outcome",
  )
  require(node.keys == keys)
  require(node["lane"] is String && node["assignment_digest"] is String && node["terminal_outcome"] is String)
  listOf("launch_bytes", "evidence_bytes", "result_bytes", "expansions", "tool_calls", "model_turns")
    .forEach { key -> require((node[key] as? Number)?.toLong()?.let { it >= 0 } == true) }
  requireUsage(node["provider_usage"], ownershipRequired = true)
  requireUsage(node["direct_usage"], ownershipRequired = false)
  requireUsage(node["inclusive_usage"], ownershipRequired = false)
}

private fun requireUsage(value: Any?, ownershipRequired: Boolean) {
  val usage = value as? Map<*, *> ?: error("Review accounting usage must be an object.")
  val tokenKeys = setOf(
    "input_tokens",
    "cached_input_tokens",
    "output_tokens",
    "reasoning_tokens",
    "total_tokens",
    "fresh_token_approximation",
  )
  require(usage.keys.all { it in tokenKeys || it == "ownership" })
  val tokenCounts = usage.filterKeys { it != "ownership" }.values
  require(tokenCounts.all { (it as? Number)?.toLong()?.let { count -> count >= 0 } == true })
  if (ownershipRequired) require(usage["ownership"] in setOf("direct", "inclusive"))
}
