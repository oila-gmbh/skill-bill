package skillbill.review

import skillbill.contracts.JsonSupport
import skillbill.review.model.ReviewClaimVerdict
import skillbill.review.model.ReviewFindingCitation
import skillbill.review.model.ReviewFindingVerdict
import skillbill.review.model.ReviewScopeDisposition
import skillbill.review.model.ReviewSeverityAdjustment
import skillbill.review.model.ReviewSeverityAdjustmentDirection
import skillbill.review.model.ReviewStage

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

  fun isActionable(
    finding: Map<String, Any?>,
    recordedVerdicts: List<ReviewFindingVerdict> = emptyList(),
  ): Boolean {
    val overlay = overlayOf(finding, recordedVerdicts)
    return isActionable(overlay.claimVerdict, overlay.scopeDisposition)
  }

  fun overlayOf(
    finding: Map<String, Any?>,
    recordedVerdicts: List<ReviewFindingVerdict>,
  ): RecordedVerdictFields {
    val byRef = recordedVerdicts.groupBy(ReviewFindingVerdict::findingRef)
    val recorded = recordedFields(byRef[findingRefOf(finding)].orEmpty())
    return RecordedVerdictFields(
      claimVerdict = recorded?.claimVerdict ?: claimVerdictOf(finding["claim_verdict"]),
      scopeDisposition = recorded?.scopeDisposition ?: scopeDispositionOf(finding["scope_disposition"]),
      citations = recorded?.citations?.takeIf { it.isNotEmpty() } ?: citationsOf(finding["citations"]),
      severityAdjustment = recorded?.severityAdjustment ?: severityAdjustmentOf(finding["severity_adjustment"]),
    )
  }

  fun recordedFields(recorded: List<ReviewFindingVerdict>): RecordedVerdictFields? {
    if (recorded.isEmpty()) return null
    val verification = recorded.firstOrNull { it.stage == ReviewStage.VERIFICATION }
    val adjudication = recorded.firstOrNull { it.stage == ReviewStage.ADJUDICATION }
    return RecordedVerdictFields(
      claimVerdict = verification?.claimVerdict ?: adjudication?.claimVerdict,
      scopeDisposition = adjudication?.scopeDisposition,
      citations = adjudication?.citations?.takeIf { it.isNotEmpty() } ?: verification?.citations.orEmpty(),
      severityAdjustment = adjudication?.severityAdjustment ?: verification?.severityAdjustment,
    )
  }

  fun verificationVerdict(recorded: List<ReviewFindingVerdict>): ReviewFindingVerdict? =
    recorded.firstOrNull { it.stage == ReviewStage.VERIFICATION }

  fun findingRefOf(finding: Map<String, Any?>): String? =
    sequenceOf(finding["id"], finding["finding_id"], finding["f_number"])
      .filterIsInstance<String>()
      .map(String::trim)
      .firstOrNull(String::isNotBlank)

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

data class RecordedVerdictFields(
  val claimVerdict: ReviewClaimVerdict?,
  val scopeDisposition: ReviewScopeDisposition?,
  val citations: List<ReviewFindingCitation>,
  val severityAdjustment: ReviewSeverityAdjustment?,
)

enum class ReviewFindingRegisterOutcome(val header: String) {
  ACTIONABLE("Actionable"),
  REFUTED("Refuted"),
  UNRESOLVED("Unresolved"),
  OUT_OF_SCOPE("Out of scope"),
}
