package skillbill.ports.review.model

import skillbill.contracts.JsonSupport
import skillbill.error.InvalidReviewContextSchemaError
import skillbill.review.context.model.ReviewEvidenceLimits
import skillbill.review.context.model.ReviewExpansionRecord

internal object GovernedReviewEvidenceCodecWireParsing {
  fun evidenceRequest(
    lane: String,
    raw: Any?,
    expansionById: (String) -> ReviewExpansionRecord?,
  ): ReviewEvidenceRequest {
    val map = asMap(raw)
    if (map.keys.any { it !in setOf("path", "selector", "expansion_id", "reachability_reason") }) {
      throw InvalidReviewContextSchemaError("review-evidence", "Unknown read selector field.")
    }
    val expansionId = optionalString(map, "expansion_id")
    val authorized = expansionId?.let { id ->
      expansionById(id) ?: throw InvalidReviewContextSchemaError(
        "review-evidence",
        "Unknown expansion id for this assignment.",
      )
    }
    return ReviewEvidenceRequest(
      lane = lane,
      selector = optionalString(map, "selector"),
      path = requiredString(map, "path"),
      reachabilityReason = optionalString(map, "reachability_reason") ?: authorized?.reachabilityReason,
      authorizedExpansion = authorized,
    )
  }

  fun requiredString(source: Map<String, Any?>, key: String): String {
    val value = source[key] as? String
    if (value.isNullOrBlank()) {
      throw InvalidReviewContextSchemaError(
        "review-evidence",
        "Operation requires string '$key'.",
      )
    }
    ReviewEvidenceLimits.field(value)
    return value
  }
  private fun asMap(raw: Any?): Map<String, Any?> = raw?.let(JsonSupport::anyToStringAnyMap)
    ?: throw InvalidReviewContextSchemaError("review-evidence", "Each read selector must be an object.")

  private fun optionalString(source: Map<String, Any?>, key: String): String? = source[key]?.let { value ->
    (value as? String)?.takeIf(String::isNotBlank)?.also(ReviewEvidenceLimits::field)
      ?: throw InvalidReviewContextSchemaError("review-evidence", "Optional selector must be a nonblank string.")
  }
}
