package skillbill.review

import skillbill.contracts.JsonSupport
import skillbill.review.model.ReviewClaimVerdict
import skillbill.review.model.ReviewFindingCitation
import skillbill.review.model.ReviewScopeDisposition
import skillbill.review.model.ReviewSeverityAdjustment
import skillbill.review.model.ReviewSeverityAdjustmentDirection

object ReviewFindingActionability {
  fun isActionable(
    claimVerdict: ReviewClaimVerdict?,
    scopeDisposition: ReviewScopeDisposition? = null,
  ): Boolean {
    if (claimVerdict == null) return true
    if (claimVerdict != ReviewClaimVerdict.CONFIRMED) return false
    return scopeDisposition == null ||
      scopeDisposition == ReviewScopeDisposition.IN_SCOPE ||
      scopeDisposition == ReviewScopeDisposition.SPEC_DEVIATION
  }

  fun isActionable(finding: Map<String, Any?>): Boolean =
    isActionable(claimVerdictOf(finding["claim_verdict"]), scopeDispositionOf(finding["scope_disposition"]))

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

  fun conservativeClaimVerdict(
    left: ReviewClaimVerdict?,
    right: ReviewClaimVerdict?,
  ): ReviewClaimVerdict? = listOfNotNull(left, right).minByOrNull(::claimVerdictRank)

  fun conservativeScopeDisposition(
    left: ReviewScopeDisposition?,
    right: ReviewScopeDisposition?,
  ): ReviewScopeDisposition? = listOfNotNull(left, right).minByOrNull(::scopeDispositionRank)

  fun registerOutcome(
    claimVerdict: ReviewClaimVerdict?,
    scopeDisposition: ReviewScopeDisposition?,
  ): ReviewFindingRegisterOutcome =
    when {
      claimVerdict == ReviewClaimVerdict.REFUTED -> ReviewFindingRegisterOutcome.REFUTED
      claimVerdict == ReviewClaimVerdict.UNRESOLVED -> ReviewFindingRegisterOutcome.UNRESOLVED
      scopeDisposition == ReviewScopeDisposition.OUT_OF_SCOPE_PREEXISTING ||
        scopeDisposition == ReviewScopeDisposition.SPEC_ACCEPTED_TRADEOFF ->
        ReviewFindingRegisterOutcome.OUT_OF_SCOPE
      else -> ReviewFindingRegisterOutcome.ACTIONABLE
    }

  private fun claimVerdictRank(verdict: ReviewClaimVerdict): Int = when (verdict) {
    ReviewClaimVerdict.UNRESOLVED -> 0
    ReviewClaimVerdict.CONFIRMED -> 1
    ReviewClaimVerdict.REFUTED -> 2
  }

  private fun scopeDispositionRank(disposition: ReviewScopeDisposition): Int = when (disposition) {
    ReviewScopeDisposition.IN_SCOPE, ReviewScopeDisposition.SPEC_DEVIATION -> 0
    ReviewScopeDisposition.SPEC_ACCEPTED_TRADEOFF, ReviewScopeDisposition.OUT_OF_SCOPE_PREEXISTING -> 1
  }
}

enum class ReviewFindingRegisterOutcome(val header: String) {
  ACTIONABLE("Actionable"),
  REFUTED("Refuted"),
  UNRESOLVED("Unresolved"),
  OUT_OF_SCOPE("Out of scope"),
}
