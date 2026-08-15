package skillbill.review

import skillbill.contracts.JsonSupport
import skillbill.review.model.RecordedVerdictFields
import skillbill.review.model.ReviewClaimVerdict
import skillbill.review.model.ReviewFindingCitation
import skillbill.review.model.ReviewScopeDisposition
import skillbill.review.model.ReviewSeverityAdjustment
import skillbill.review.model.ReviewSeverityAdjustmentDirection

object ReviewFindingFieldCodec {
  fun findingRefOf(id: Any?, findingId: Any?, fNumber: Any?): String? = sequenceOf(id, findingId, fNumber)
    .filterIsInstance<String>()
    .map(String::trim)
    .firstOrNull(String::isNotBlank)

  fun recordedFieldsOf(
    claimVerdict: Any?,
    scopeDisposition: Any?,
    citations: Any?,
    severityAdjustment: Any?,
  ): RecordedVerdictFields = RecordedVerdictFields(
    claimVerdict = claimVerdictOf(claimVerdict),
    scopeDisposition = scopeDispositionOf(scopeDisposition),
    citations = citationsOf(citations),
    severityAdjustment = severityAdjustmentOf(severityAdjustment),
  )

  fun claimVerdictOf(raw: Any?): ReviewClaimVerdict? =
    (raw as? String)?.trim()?.takeIf(String::isNotBlank)?.let(ReviewClaimVerdict::fromWire)

  fun scopeDispositionOf(raw: Any?): ReviewScopeDisposition? =
    (raw as? String)?.trim()?.takeIf(String::isNotBlank)?.let(ReviewScopeDisposition::fromWire)

  fun citationsOf(raw: Any?): List<ReviewFindingCitation> {
    val items = raw as? List<*> ?: return emptyList()
    return items.mapNotNull { item ->
      val map = JsonSupport.anyToStringAnyMap(item) ?: return@mapNotNull null
      val path = (map["path"] as? String)?.trim()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
      val line = when (val value = map["line"]) {
        is Number -> value.toInt()
        is String -> value.trim().toIntOrNull()
        else -> null
      } ?: return@mapNotNull null
      ReviewFindingCitation(path, line)
    }
  }

  fun severityAdjustmentOf(raw: Any?): ReviewSeverityAdjustment? {
    val map = JsonSupport.anyToStringAnyMap(raw) ?: return null
    val direction = (map["direction"] as? String)?.trim()?.takeIf(String::isNotBlank)
      ?.let(ReviewSeverityAdjustmentDirection::fromWire)
      ?: return null
    val justification = (map["justification"] as? String)?.trim()?.takeIf(String::isNotBlank) ?: return null
    return ReviewSeverityAdjustment(direction, justification)
  }
}
