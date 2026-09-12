package skillbill.ports.review.model

import skillbill.contracts.SharedPayloadKeys

import skillbill.boundary.OpenBoundaryMap
import skillbill.contracts.review.REVIEW_CONTEXT_CONTRACT_VERSION
import skillbill.error.InvalidReviewContextSchemaError

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
  val legacy = payload[SharedPayloadKeys.CONTRACT_VERSION] == LEGACY_REVIEW_CONTEXT_CONTRACT_VERSION
  val topKeys = if (legacy) {
    setOf(
      "contract_version", "kind", "review_id", "packet_digest", "parent", "lanes", "aggregate_counters",
      "aggregate_direct_usage", "aggregate_inclusive_usage", "budget_regression",
    )
  } else {
    setOf("contract_version", "kind", "review_id", "packet_digest", "parent", "lanes", "aggregate_counters")
  }
  require(payload.keys - COMMIT_FOCUSED_KEYS == topKeys) {
    "Review accounting must match the bounded projection contract."
  }
  COMMIT_FOCUSED_KEYS.forEach { key ->
    payload[key]?.let { require(it is Map<*, *>) { "Review accounting '$key' must be an object when present." } }
  }
  require(
    payload[SharedPayloadKeys.CONTRACT_VERSION] in setOf(REVIEW_CONTEXT_CONTRACT_VERSION, LEGACY_REVIEW_CONTEXT_CONTRACT_VERSION) &&
      payload["kind"] == "accounting_summary",
  )
  require(payload["review_id"] is String && payload["packet_digest"] is String)
  requireAccountingNode(payload["parent"], legacy)
  require(
    (payload["lanes"] as? List<*>)?.all { runCatching { requireAccountingNode(it, legacy) }.isSuccess } == true,
  )
  requireCounters(payload["aggregate_counters"])
  if (legacy) {
    require(payload["aggregate_direct_usage"] is Map<*, *>)
    require(payload["aggregate_inclusive_usage"] is Map<*, *>)
    require(payload["budget_regression"] is Boolean)
  }
}

private val COMMIT_FOCUSED_KEYS =
  setOf("commit_routing_accounting", "parent_analysis_consumption", "integration")

private fun requireAccountingNode(value: Any?, legacy: Boolean) {
  val node = value as? Map<*, *> ?: error("Review accounting node must be an object.")
  val keys = setOf(
    "lane", "assignment_digest", "launch_bytes", "evidence_bytes", "result_bytes", "expansions",
    "tool_calls", "model_turns", "inclusive_counters", "terminal_outcome",
  ) + if (legacy) setOf("provider_usage", "direct_usage", "inclusive_usage") else emptySet()
  require(node.keys.containsAll(keys))
  require((BUNDLE_KEYS + "evidence_delivery").containsAll(node.keys - keys))
  if ("evidence_delivery" in node) requireEvidenceDelivery(node["evidence_delivery"])
  require(node["lane"] is String && node["assignment_digest"] is String && node["terminal_outcome"] is String)
  COUNTER_KEYS.forEach { key -> require((node[key] as? Number)?.toLong()?.let { it >= 0 } == true) }
  requireCounters(node["inclusive_counters"])
  if (legacy) {
    require(node["provider_usage"] is Map<*, *>)
    require(node["direct_usage"] is Map<*, *>)
    require(node["inclusive_usage"] is Map<*, *>)
  }
  requireBundleAccounting(node)
}

private val BUNDLE_KEYS = setOf("bundle_composition_digest", "segment_accounting", "unreviewed_segment_ids")

private fun requireEvidenceDelivery(value: Any?) {
  val delivery = value as? Map<*, *>
    ?: throw InvalidReviewContextSchemaError("review-accounting", "Evidence delivery must be an object.")
  val keys = setOf("required_units", "delivered_units", "remaining_units", "request_count")
  if (delivery.keys != keys || delivery.values.any { it !is Int && it !is Long }) {
    throw InvalidReviewContextSchemaError("review-accounting", "Evidence delivery requires integer counters.")
  }
  val counts = keys.associateWith { (delivery[it] as Number).toLong() }
  requireDeliveryCounts(counts)
}

private fun requireDeliveryCounts(counts: Map<String, Long>) {
  if (counts.values.any { it !in 0..Int.MAX_VALUE.toLong() } ||
    counts.getValue("required_units") != counts.getValue("delivered_units") + counts.getValue("remaining_units")
  ) {
    throw InvalidReviewContextSchemaError("review-accounting", "Evidence delivery counters are inconsistent.")
  }
}

private fun requireBundleAccounting(node: Map<*, *>) {
  node["bundle_composition_digest"]?.let { require(it is String && it.isNotBlank()) }
  node["segment_accounting"]?.let { segments ->
    val entries = segments as? List<*> ?: error("Review segment accounting must be a list.")
    require(entries.isNotEmpty())
    entries.forEach { requireSegmentAccounting(it) }
  }
  node["unreviewed_segment_ids"]?.let { ids ->
    val list = ids as? List<*> ?: error("Unreviewed segment ids must be a list.")
    require(list.isNotEmpty() && list.all { it is String && it.isNotBlank() })
  }
}

private fun requireSegmentAccounting(value: Any?) {
  val segment = value as? Map<*, *> ?: error("Review segment accounting entry must be an object.")
  require(segment.keys == setOf("segment_id", "measured_bytes", "entry_count", "composition_digest"))
  require(segment["segment_id"] is String && segment["composition_digest"] is String)
  listOf("measured_bytes", "entry_count").forEach { key ->
    require((segment[key] as? Number)?.toLong()?.let { it >= 0 } == true)
  }
}

private fun requireCounters(value: Any?) {
  val counters = value as? Map<*, *> ?: error("Review accounting counters must be an object.")
  require(counters.keys == COUNTER_KEYS)
  require(counters.values.all { (it as? Number)?.toLong()?.let { count -> count >= 0 } == true })
}

private val COUNTER_KEYS = setOf(
  "launch_bytes",
  "evidence_bytes",
  "result_bytes",
  "expansions",
  "tool_calls",
  "model_turns",
)

private const val LEGACY_REVIEW_CONTEXT_CONTRACT_VERSION: String = "2.1"
