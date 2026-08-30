package skillbill.ports.review.model

import skillbill.contracts.JsonSupport
import skillbill.review.context.model.ReviewExpansionRecord

internal object GovernedReviewEvidenceCodecWireParsing {
  fun evidenceRequest(
    lane: String,
    raw: Any?,
    expansionById: (String) -> ReviewExpansionRecord?,
  ): ReviewEvidenceRequest {
    val map = asMap(raw)
    val expansionId = optionalString(map, "expansion_id")
    val authorized = expansionId?.let { id ->
      requireNotNull(expansionById(id)) { "Unknown expansion id '$id' for this lane." }
    }
    return ReviewEvidenceRequest(
      lane = lane,
      path = requiredString(map, "path"),
      reachabilityReason = optionalString(map, "reachability_reason") ?: authorized?.reachabilityReason,
      authorizedExpansion = authorized,
      offset = optionalLong(map, "offset"),
      limit = optionalLong(map, "limit"),
      paginationToken = optionalString(map, "pagination_token"),
    )
  }

  fun requiredString(source: Map<String, Any?>, key: String): String {
    val value = source[key]?.toString()
    require(!value.isNullOrBlank()) { "Governed evidence operation requires '$key'." }
    return value
  }
  private fun asMap(raw: Any?): Map<String, Any?> = requireNotNull(JsonSupport.anyToStringAnyMap(requireNotNull(raw))) {
    "Each governed evidence request must be an object."
  }

  private fun optionalString(source: Map<String, Any?>, key: String): String? =
    source[key]?.toString()?.takeIf(String::isNotBlank)

  private fun optionalLong(source: Map<String, Any?>, key: String): Long? = when (val value = source[key]) {
    null -> null
    is Number -> value.toLong()
    else -> value.toString().toLongOrNull()
  }
}
