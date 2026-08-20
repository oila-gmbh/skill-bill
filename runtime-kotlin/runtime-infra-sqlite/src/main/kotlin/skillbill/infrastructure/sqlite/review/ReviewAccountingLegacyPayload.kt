package skillbill.infrastructure.sqlite.review

private const val LEGACY_EVIDENCE_UNREVIEWABLE_SEGMENT_ID: String = "evidence-unreviewable"

internal fun payloadCarriesLegacyEvidenceUnreviewableSegment(payload: Map<String, Any?>): Boolean {
  fun Map<*, *>.segmentIds(): List<String> {
    val ids = this["unreviewed_segment_ids"] as? List<*> ?: return emptyList()
    return ids.filterIsInstance<String>()
  }
  fun Any?.walkNodes(): Sequence<Map<*, *>> = sequence {
    when (this@walkNodes) {
      is Map<*, *> -> {
        yield(this@walkNodes)
        this@walkNodes.values.forEach { value -> yieldAll(value.walkNodes()) }
      }
      is List<*> -> this@walkNodes.forEach { item -> yieldAll(item.walkNodes()) }
    }
  }
  return payload.walkNodes().any { node ->
    LEGACY_EVIDENCE_UNREVIEWABLE_SEGMENT_ID in node.segmentIds()
  }
}
