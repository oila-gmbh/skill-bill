package skillbill.review.model

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
